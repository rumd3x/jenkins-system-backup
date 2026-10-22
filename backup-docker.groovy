// Docker-focused backup pipeline: generates a compose file that recreates every container on the target
// host (red5d/docker-autocompose), copies all container volumes/bind mounts uncompressed with rsync,
// uploads the result to NAS storage, and rotates old backups.
//
// Backup layout (YYYY_MM_DD-<host>/):
//   docker-compose.yaml  recreates all containers
//   mounts.tsv           type, volume name, host source path, kind (d/f), backup folder
//   networks.tsv         user-defined networks to pre-create on restore (name, driver, subnet)
//   volumes/<name>/      content of each named volume
//   binds/<abs path>/    content of each bind mount
// Real ownership, modes, symlinks and special files are stored in xattrs (rsync --fake-super), so the
// agent and NAS users need not be root, but their filesystems must support user xattrs.

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
        // IP or hostname of the machine to be backed up
        string(
            name: 'TARGET_HOST',
            defaultValue: '',
            trim: true,
            description: 'Target host IP or hostname to backup'
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
            description: 'Path to the backup file on the NAS storage'
        )
        string(
            name: 'DAYS_TO_KEEP',
            defaultValue: '7',
            trim: true,
            description: 'Maximum number of backup files to retain per host before oldest are deleted'
        )
    }

    environment {
        REMOTE_HOST  = "${params.TARGET_HOST}"
        NAS_HOST     = "${params.NAS_HOST}"
        NAS_PATH     = "${params.BACKUP_PATH}"
        DAYS_TO_KEEP = "${params.DAYS_TO_KEEP}"
        SSH_OPTS     = '-o StrictHostKeyChecking=no -o ServerAliveInterval=60 -o ServerAliveCountMax=10'
        // Run with the Docker socket mounted (root-equivalent on the target): pin to a digest if you need reproducibility
        AUTOCOMPOSE_IMAGE = 'ghcr.io/red5d/docker-autocompose:latest'
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
                    // 0 would make rotation delete the backup that was just uploaded
                    if (!(params.DAYS_TO_KEEP ==~ /^[1-9][0-9]*$/)) {
                        error "DAYS_TO_KEEP must be a positive integer"
                    }
                }
            }
        }

        // Remove any stale backup directories left from a previous failed run
        stage('Cleanup Workspace') {
            steps {
                sh 'rm -rf */ || true'
            }
        }

        // Computes the backup name, then asks docker-autocompose (run on the target) for a compose file of ALL containers
        stage('Generate Compose File') {
            steps {
                script {
                    env.BACKUP_NAME = sh(
                        script: '''
                            DATE=$(date +%Y_%m_%d)
                            HOST_NAME=$(echo "${REMOTE_HOST}" | tr '.' '_')
                            echo "${DATE}-${HOST_NAME}"
                        ''',
                        returnStdout: true
                    ).trim()
                }
                withCredentials([usernamePassword(
                    credentialsId: params.SSH_CREDENTIAL,
                    usernameVariable: 'SSH_USER',
                    passwordVariable: 'SSH_PASS'
                )]) {
                    sh(RSH + '''
                        export SSHPASS="${SSH_PASS}"
                        mkdir -p "${BACKUP_NAME}"
                        # stdout carries the YAML only; docker pull progress and errors go to stderr
                        rsh "${AUTOCOMPOSE_IMAGE}" > "${BACKUP_NAME}/docker-compose.yaml" <<'EOF'
set -eu
NAMES=$(sudo docker ps -a --format '{{.Names}}')
if [ -z "$NAMES" ]; then
    echo "No containers found on the target host" >&2
    exit 1
fi
# the Docker Hub image is stale and crashes on current Docker engines (KeyError: 'MacAddress'); GHCR is maintained
sudo docker run --rm -v /var/run/docker.sock:/var/run/docker.sock "$1" $NAMES
EOF
                        grep -q '^services:' "${BACKUP_NAME}/docker-compose.yaml"
                        echo "Generated compose file:"
                        grep -c 'container_name:' "${BACKUP_NAME}/docker-compose.yaml" | sed 's/$/ container(s)/'
                    ''')
                }
            }
        }

        // Lists every volume/bind mount and user-defined network used by the containers
        stage('Inspect Mounts and Networks') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.SSH_CREDENTIAL,
                    usernameVariable: 'SSH_USER',
                    passwordVariable: 'SSH_PASS'
                )]) {
                    sh(RSH + '''
                        export SSHPASS="${SSH_PASS}"

                        # mounts.tsv: type <TAB> volume name (or -) <TAB> host source <TAB> kind (d=dir, f=file) <TAB> backup folder
                        rsh > "${BACKUP_NAME}/mounts.tsv" <<'EOF'
set -eu
TAB=$(printf '\\t')
IDS=$(sudo docker ps -aq)
OUT=$(sudo docker inspect --format '{{range .Mounts}}{{.Type}}{{"\\t"}}{{if .Name}}{{.Name}}{{else}}-{{end}}{{"\\t"}}{{.Source}}{{"\\n"}}{{end}}' $IDS)
printf '%s\\n' "$OUT" | sort -u | while IFS="$TAB" read -r type name src; do
    case "$type" in bind|volume) ;; *) continue ;; esac
    # paths containing a tab/newline cannot be stored in the TSV
    case "$src" in *"$TAB"*) echo "WARNING: skipping mount with tab in path: $src" >&2; continue ;; esac
    # runtime sockets and kernel/device paths are not data
    case "$src" in
        /var/run|/var/run/*|/run|/run/*|/proc|/proc/*|/sys|/sys/*|/dev|/dev/*) continue ;;
    esac
    if sudo test -d "$src"; then kind=d
    elif sudo test -e "$src"; then kind=f
    else echo "WARNING: skipping missing mount source $src" >&2; continue
    fi
    if [ "$type" = volume ]; then dir="volumes/$name"; else dir="binds$src"; fi
    printf '%s\\t%s\\t%s\\t%s\\t%s\\n' "$type" "$name" "$src" "$kind" "$dir"
done
EOF

                        # networks.tsv: name <TAB> driver <TAB> subnet (or -). Internal networks are recreated by compose itself
                        rsh > "${BACKUP_NAME}/networks.tsv" <<'EOF'
set -eu
IDS=$(sudo docker ps -aq)
OUT=$(sudo docker inspect --format '{{range $n, $v := .NetworkSettings.Networks}}{{$n}}{{"\\n"}}{{end}}' $IDS)
printf '%s\\n' "$OUT" | sort -u | while read -r net; do
    case "$net" in bridge|host|none|"") continue ;; esac
    INFO=$(sudo docker network inspect --format '{{.Internal}} {{.Driver}} {{range .IPAM.Config}}{{.Subnet}} {{end}}' "$net")
    set -- $INFO
    internal=$1; driver=$2; subnet=${3:--}
    [ "$internal" = true ] && continue
    printf '%s\\t%s\\t%s\\n' "$net" "$driver" "$subnet"
done
EOF
                        echo "Mounts to back up:"
                        cat "${BACKUP_NAME}/mounts.tsv"
                    ''')
                }
            }
        }

        // For each mount: rsync the data (uncompressed) from the target, which runs `sudo rsync` on its side
        stage('Copy Volumes and Mounts') {
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
                            echo "Copying ${src} -> ${dir}"

                            # a parent mount copied earlier may already contain this path
                            rm -rf "${BACKUP_NAME}/${dir}"
                            if [ "${kind}" = d ]; then
                                mkdir -p "${BACKUP_NAME}/${dir}"
                                FROM="${TARGET}:${src}/"; TO="${BACKUP_NAME}/${dir}/"
                            else
                                mkdir -p "$(dirname "${BACKUP_NAME}/${dir}")"
                                FROM="${TARGET}:${src}"; TO="${BACKUP_NAME}/${dir}"
                            fi

                            # The sender is root on the target, the receiver (this agent) is not: --fake-super keeps the real
                            # uid/gid/mode and symlink/fifo/device info in xattrs. -s protects paths with spaces
                            rc=0
                            rsync -aHXs --numeric-ids --fake-super -e "sshpass -e ssh ${SSH_OPTS}" --rsync-path="${RSYNC_PATH}" "${FROM}" "${TO}" < /dev/null || rc=$?
                            # 24 = source files vanished during the transfer, expected with live data
                            if [ "${rc}" -ne 0 ] && [ "${rc}" -ne 24 ]; then
                                exit "${rc}"
                            fi
                        done < "${BACKUP_NAME}/mounts.tsv"
                    ''')
                }
            }
        }

        // Checks the compose file and every mount folder are present
        stage('Verify Backup Integrity') {
            steps {
                sh '''
                    echo "Verifying backup integrity..."
                    TAB=$(printf '\\t')
                    test -s "${BACKUP_NAME}/docker-compose.yaml"
                    grep -q '^services:' "${BACKUP_NAME}/docker-compose.yaml"

                    while IFS="${TAB}" read -r type name src kind dir; do
                        if [ ! -e "${BACKUP_NAME}/${dir}" ]; then
                            echo "ERROR: ${dir} is missing from the backup"
                            exit 1
                        fi
                    done < "${BACKUP_NAME}/mounts.tsv"
                    echo "Backup integrity OK"
                '''
            }
        }

        // Directory name YYYY_MM_DD-<host> ensures alphabetical sort equals chronological order for rotation
        stage('Upload to NAS') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.NAS_SSH_CREDENTIAL,
                    usernameVariable: 'NAS_USER',
                    passwordVariable: 'NAS_PASS'
                )]) {
                    sh '''
                        export SSHPASS="${NAS_PASS}"
                        # --fake-super on both ends: ownership/special-file info lives in xattrs, so the NAS user need not be root
                        # --delete makes a same-day re-run replace the earlier upload instead of merging into it
                        rsync -aHXs --delete --numeric-ids --fake-super -e "sshpass -e ssh ${SSH_OPTS}" \
                            --rsync-path="mkdir -p ${NAS_PATH} && rsync --fake-super" \
                            "${BACKUP_NAME}/" "${NAS_USER}@${NAS_HOST}:${NAS_PATH}/${BACKUP_NAME}/"
                    '''
                }
            }
        }

        // Keeps only the N most recent backups for the host; relies on date-prefixed names for sort order
        stage('Rotate Old Backups') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.NAS_SSH_CREDENTIAL,
                    usernameVariable: 'NAS_USER',
                    passwordVariable: 'NAS_PASS'
                )]) {
                    sh '''
                        export SSHPASS="${NAS_PASS}"
                        HOST_NAME=$(echo "${REMOTE_HOST}" | tr '.' '_')

                        # The date prefix is part of the glob so that host "web" never matches backups of host "my-web".
                        # || true prevents pipeline failure when no backups exist yet for this host
                        DIRS=$(sshpass -e ssh ${SSH_OPTS} ${NAS_USER}@${NAS_HOST} \
                            "ls -1d ${NAS_PATH}/[0-9][0-9][0-9][0-9]_[0-9][0-9]_[0-9][0-9]-${HOST_NAME} 2>/dev/null | sort" || true)

                        if [ -z "$DIRS" ]; then
                            echo "No backups found for ${HOST_NAME}, skipping rotation."
                            exit 0
                        fi

                        COUNT=$(echo "$DIRS" | wc -l)
                        echo "Found ${COUNT} backup(s) for ${HOST_NAME}, keeping ${DAYS_TO_KEEP}."

                        if [ "$COUNT" -gt "$DAYS_TO_KEEP" ]; then
                            DELETE_COUNT=$(( COUNT - DAYS_TO_KEEP ))
                            TO_DELETE=$(echo "$DIRS" | head -n "$DELETE_COUNT")
                            echo "Deleting ${DELETE_COUNT} old backup(s):"
                            echo "$TO_DELETE"
                            sshpass -e ssh ${SSH_OPTS} ${NAS_USER}@${NAS_HOST} \
                                "rm -rf $(echo "$TO_DELETE" | tr '\\n' ' ')"
                        else
                            echo "No rotation needed."
                        fi
                    '''
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
