// Docker-focused restore pipeline: downloads a selected Docker backup from NAS storage, copies every
// volume/bind mount back to its original location on the target host with rsync (ownership comes from
// xattrs written by the backup), uploads the compose
// file and starts all containers. The target must already have docker and docker compose working.
// Runs a script (read from stdin) on TARGET_HOST over SSH with a working `sudo` even though the session
// has no TTY: the SSH password is sent as the first stdin line and fed to `sudo -S` by a shell function.
// Usage inside an sh step: rsh [script args...] <<'EOF' ... EOF
def RSH = '''
SUDO_PRELUDE='if [ "$(id -u)" = 0 ]; then sudo() { "$@"; }; else sudo() { printf "%s\\n" "$SUDO_PASS" | command sudo -S -p "" "$@"; }; fi'
REMOTE_WRAPPER="sh -c 'IFS= read -r SUDO_PASS; eval \\"\\$(cat)\\"'"
rsh() {
    # every arg is single-quoted (embedded quotes escaped) before it reaches the remote shell
    RSH_ARGS=""
    for a in "$@"; do
        esc=$(printf '%s' "$a" | sed "s/'/'\\\\\\\\''/g")
        RSH_ARGS="${RSH_ARGS} '${esc}'"
    done
    {
        printf '%s\\n' "${SSH_PASS}"
        printf '%s\\n' "${SUDO_PRELUDE}"
        printf '%s\\n' "set -- ${RSH_ARGS}"
        cat
    } | sshpass -e ssh ${SSH_OPTS} "${SSH_USER}@${REMOTE_HOST}" "${REMOTE_WRAPPER}"
}

# rsync owns the SSH stdin, so `sudo -S` cannot be used for the remote `sudo rsync`. Instead sudo gets an askpass
# helper kept in a private (mode 700) temp dir on the target, removed on exit by the trap rsync_setup installs. Sets ASKPASS_DIR and RSYNC_PATH.
rsync_setup() {
    command -v rsync > /dev/null || { echo "rsync is required on the Jenkins agent" >&2; return 1; }
    INFO=$(rsh <<'SETUP'
set -eu
command -v rsync > /dev/null || { echo "rsync is required on the target host" >&2; exit 1; }
umask 077
d=$(mktemp -d /var/tmp/rsync-work.XXXXXX)
printf '%s\\n' "$SUDO_PASS" > "$d/pw"
printf '#!/bin/sh\\ncat "%s/pw"\\n' "$d" > "$d/askpass"
chmod 700 "$d/askpass"
echo "$d"
id -u
SETUP
)
    ASKPASS_DIR=$(echo "${INFO}" | sed -n 1p)
    # also clean up when the build is aborted (SIGTERM/SIGINT), not just on normal exit
    trap 'exit 1' INT TERM HUP
    trap rsync_cleanup EXIT
    if [ "$(echo "${INFO}" | sed -n 2p)" = 0 ]; then
        RSYNC_PATH="rsync"
    else
        RSYNC_PATH="SUDO_ASKPASS=${ASKPASS_DIR}/askpass sudo -A rsync"
    fi
}
rsync_cleanup() {
    if [ -n "${ASKPASS_DIR:-}" ]; then
        echo 'rm -rf "$1"' | rsh "${ASKPASS_DIR}" || true
    fi
}
'''

pipeline {
    agent { label 'docker' }

    parameters {
        string(
            name: 'TARGET_HOST',
            defaultValue: '',
            trim: true,
            description: 'IP or hostname of the machine that will receive the restored containers'
        )
        credentials(
            name: 'SSH_CREDENTIAL',
            description: 'SSH username and password for the target host',
            credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl',
            required: true
        )
        string(
            name: 'NAS_HOST',
            defaultValue: '',
            trim: true,
            description: 'Target host IP or hostname of the NAS storage'
        )
        credentials(
            name: 'NAS_SSH_CREDENTIAL',
            description: 'SSH username and password for the NAS storage',
            credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl',
            required: true
        )
        string(
            name: 'BACKUP_PATH',
            defaultValue: '',
            trim: true,
            description: 'Full path to the NAS directory that contains the backup directories'
        )
    }

    environment {
        REMOTE_HOST = "${params.TARGET_HOST}"
        NAS_HOST    = "${params.NAS_HOST}"
        NAS_PATH    = "${params.BACKUP_PATH}"
        SSH_OPTS    = '-o StrictHostKeyChecking=no -o ServerAliveInterval=60 -o ServerAliveCountMax=10'
    }

    stages {

        // Parameters end up in shell commands and paths on three machines: reject anything but plain values
        stage('Validate Parameters') {
            steps {
                script {
                    if (!(params.TARGET_HOST ==~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/)) {
                        error "TARGET_HOST must be a hostname or IPv4 address (letters, digits, '.', '_', '-')"
                    }
                    if (!(params.NAS_HOST ==~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/)) {
                        error "NAS_HOST must be a hostname or IPv4 address (letters, digits, '.', '_', '-')"
                    }
                    if (!(params.BACKUP_PATH ==~ /^\/[A-Za-z0-9._\/+@=-]+$/)) {
                        error "BACKUP_PATH must be an absolute path without spaces or shell characters"
                    }
                }
            }
        }

        // Pauses the pipeline with a human-approval prompt listing all available backups
        stage('Select Backup from NAS') {
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: params.NAS_SSH_CREDENTIAL,
                        usernameVariable: 'NAS_USER',
                        passwordVariable: 'NAS_PASS'
                    )]) {
                        def rawList = sh(
                            script: '''
                                export SSHPASS="${NAS_PASS}"
                                sshpass -e ssh ${SSH_OPTS} ${NAS_USER}@${NAS_HOST} "ls -1 ${NAS_PATH}"
                            ''',
                            returnStdout: true
                        ).trim()

                        // filter to entries matching the YYYY_MM_DD-<host> naming pattern
                        def backupList = rawList.split('\n').findAll { it.trim() ==~ /^\d{4}_\d{2}_\d{2}-[A-Za-z0-9._-]+$/ }

                        if (backupList.isEmpty()) {
                            error "No backup directories found on NAS at ${env.NAS_PATH}"
                        }

                        def userChoice = input(
                            message: 'Select the backup to restore:',
                            parameters: [
                                choice(
                                    name: 'BACKUP_FILE',
                                    choices: backupList,
                                    description: "Available backups in ${env.NAS_HOST}:${env.NAS_PATH}"
                                )
                            ]
                        )

                        env.SELECTED_BACKUP = userChoice
                    }
                }
            }
        }

        stage('Download Backup from NAS') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.NAS_SSH_CREDENTIAL,
                    usernameVariable: 'NAS_USER',
                    passwordVariable: 'NAS_PASS'
                )]) {
                    sh '''
                        command -v rsync > /dev/null || { echo "rsync is required on the Jenkins agent" >&2; exit 1; }
                        export SSHPASS="${NAS_PASS}"
                        # --fake-super on both ends: the NAS stores ownership/special-file info in xattrs
                        rsync -aHXs --numeric-ids --fake-super -e "sshpass -e ssh ${SSH_OPTS}" \
                            --rsync-path="rsync --fake-super" \
                            "${NAS_USER}@${NAS_HOST}:${NAS_PATH}/${SELECTED_BACKUP}" .
                    '''
                }
            }
        }

        // Checks the backup is complete before the target host is touched
        stage('Verify Backup Integrity') {
            steps {
                sh '''
                    echo "Verifying backup integrity..."
                    TAB=$(printf '\\t')
                    for f in docker-compose.yaml mounts.tsv networks.tsv; do
                        if [ ! -e "${SELECTED_BACKUP}/${f}" ]; then
                            echo "ERROR: ${f} is missing from the backup"
                            exit 1
                        fi
                    done
                    test -s "${SELECTED_BACKUP}/docker-compose.yaml"
                    grep -q '^services:' "${SELECTED_BACKUP}/docker-compose.yaml"

                    # mounts.tsv decides where root writes on the target: refuse malformed or dangerous entries
                    while IFS="${TAB}" read -r type name src kind dir; do
                        case "${type}" in bind|volume) ;; *) echo "ERROR: invalid mount type '${type}'"; exit 1 ;; esac
                        case "${kind}" in d|f) ;; *) echo "ERROR: invalid kind '${kind}'"; exit 1 ;; esac
                        case "${src}" in
                            /|/proc|/proc/*|/sys|/sys/*|/dev|/dev/*|/run|/run/*|/var/run|/var/run/*) echo "ERROR: refusing to restore to '${src}'"; exit 1 ;;
                            /*) ;;
                            *) echo "ERROR: mount source must be absolute: '${src}'"; exit 1 ;;
                        esac
                        case "${dir}" in
                            */../*|*/..) echo "ERROR: invalid backup folder '${dir}'"; exit 1 ;;
                            volumes/*|binds/*) ;;
                            *) echo "ERROR: invalid backup folder '${dir}'"; exit 1 ;;
                        esac
                        if [ ! -e "${SELECTED_BACKUP}/${dir}" ]; then
                            echo "ERROR: ${dir} is missing from the backup"
                            exit 1
                        fi
                    done < "${SELECTED_BACKUP}/mounts.tsv"
                    echo "Backup integrity OK"
                '''
            }
        }

        // The generated compose file declares non-internal networks as external, so they must exist before 'up'
        stage('Upload Compose File and Create Networks') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.SSH_CREDENTIAL,
                    usernameVariable: 'SSH_USER',
                    passwordVariable: 'SSH_PASS'
                )]) {
                    sh(RSH + '''
                        export SSHPASS="${SSH_PASS}"
                        TAB=$(printf '\\t')
                        TARGET="${SSH_USER}@${REMOTE_HOST}"

                        # fixed directory name keeps the compose project name stable across restores
                        echo 'mkdir -p docker-restore' | rsh
                        sshpass -e scp ${SSH_OPTS} "${SELECTED_BACKUP}/docker-compose.yaml" \
                            "${TARGET}:docker-restore/docker-compose.yaml" < /dev/null

                        while IFS="${TAB}" read -r net driver subnet; do
                            echo "Ensuring network ${net}"
                            rsh "${net}" "${driver}" "${subnet}" <<'EOF'
set -eu
net=$1; driver=$2; subnet=$3
if sudo docker network inspect "$net" > /dev/null 2>&1; then
    echo "Network $net already exists"
elif [ "$subnet" = "-" ]; then
    sudo docker network create --driver "$driver" "$net"
else
    sudo docker network create --driver "$driver" --subnet "$subnet" "$net"
fi
EOF
                        done < "${SELECTED_BACKUP}/networks.tsv"
                    ''')
                }
            }
        }

        // For each mount: resolve the destination and rsync the folder there with `sudo rsync` (root applies ownership)
        stage('Restore Volumes and Mounts') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.SSH_CREDENTIAL,
                    usernameVariable: 'SSH_USER',
                    passwordVariable: 'SSH_PASS'
                )]) {
                    sh(RSH + '''
                        export SSHPASS="${SSH_PASS}"
                        TAB=$(printf '\\t')
                        TARGET="${SSH_USER}@${REMOTE_HOST}"

                        rsync_setup

                        while IFS="${TAB}" read -r type name src kind dir; do
                            echo "Restoring ${dir} -> ${src}"

                            # compose declares named volumes as external, so they must exist; the mountpoint is resolved by docker
                            DEST=$(rsh "${type}" "${name}" "${src}" "${kind}" <<'EOF'
set -eu
type=$1; name=$2; dest=$3; kind=$4
if [ "$type" = volume ]; then
    sudo docker volume inspect "$name" > /dev/null 2>&1 || sudo docker volume create "$name" > /dev/null
    dest=$(sudo docker volume inspect --format '{{.Mountpoint}}' "$name")
fi
if [ "$kind" = d ]; then sudo mkdir -p "$dest"; else sudo mkdir -p "$(dirname "$dest")"; fi
echo "$dest"
EOF
)

                            if [ "${kind}" = d ]; then
                                FROM="${SELECTED_BACKUP}/${dir}/"; TO="${TARGET}:${DEST}/"
                            else
                                FROM="${SELECTED_BACKUP}/${dir}"; TO="${TARGET}:${DEST}"
                            fi

                            # The sender (this agent) reads the real uid/gid/mode and symlink/special-file info from xattrs
                            # (--fake-super); the receiver is root on the target, so it applies them for real
                            rsync -aHXs --numeric-ids --fake-super -e "sshpass -e ssh ${SSH_OPTS}" --rsync-path="${RSYNC_PATH}" "${FROM}" "${TO}" < /dev/null
                        done < "${SELECTED_BACKUP}/mounts.tsv"
                    ''')
                }
            }
        }

        stage('Start Containers') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.SSH_CREDENTIAL,
                    usernameVariable: 'SSH_USER',
                    passwordVariable: 'SSH_PASS'
                )]) {
                    sh(RSH + '''
                        export SSHPASS="${SSH_PASS}"
                        rsh <<'EOF'
set -eu
cd docker-restore
# supports both the compose v2 plugin and the standalone docker-compose binary
if sudo docker compose version > /dev/null 2>&1; then
    sudo docker compose -f docker-compose.yaml up -d
else
    sudo docker-compose -f docker-compose.yaml up -d
fi
EOF
                    ''')
                }
            }
        }
    }

    post {
        always {
            sh 'rm -rf */ || true'
            cleanWs()
        }
    }
}
