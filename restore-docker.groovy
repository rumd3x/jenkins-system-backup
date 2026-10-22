// Docker-focused restore pipeline: downloads a selected Docker backup from NAS storage, copies every
// volume/bind mount back to its original location on the target host over SCP, uploads the compose
// file and starts all containers. The target must already have docker and docker compose working.
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
                                sshpass -p "${NAS_PASS}" ssh -o StrictHostKeyChecking=no \
                                    ${NAS_USER}@${NAS_HOST} \
                                    "ls -1 ${NAS_PATH}"
                            ''',
                            returnStdout: true
                        ).trim()

                        // filter to entries matching the YYYY_MM_DD-<host> naming pattern
                        def backupList = rawList.split('\n').findAll { it.trim() =~ /^\d{4}_\d{2}_\d{2}-/ }

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
                        sshpass -p "${NAS_PASS}" scp -o StrictHostKeyChecking=no \
                            -o ServerAliveInterval=60 -o ServerAliveCountMax=10 -r \
                            ${NAS_USER}@${NAS_HOST}:${NAS_PATH}/${SELECTED_BACKUP} \
                            "${SELECTED_BACKUP}"
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
                    SEP=$(printf '\\001')
                    for f in docker-compose.yaml mounts.tsv networks.tsv ownership.tsv; do
                        if [ ! -e "${SELECTED_BACKUP}/${f}" ]; then
                            echo "ERROR: ${f} is missing from the backup"
                            exit 1
                        fi
                    done
                    test -s "${SELECTED_BACKUP}/docker-compose.yaml"
                    grep -q '^services:' "${SELECTED_BACKUP}/docker-compose.yaml"

                    while IFS="${TAB}" read -r type name src kind dir; do
                        if [ ! -e "${SELECTED_BACKUP}/${dir}" ]; then
                            echo "ERROR: ${dir} is missing from the backup"
                            exit 1
                        fi
                        expected=$(awk -F"${SEP}" -v d="${dir}" '$1==d && ($5=="f" || $5=="d")' "${SELECTED_BACKUP}/ownership.tsv" | wc -l)
                        actual=$(find "${SELECTED_BACKUP}/${dir}" \\( -type f -o -type d \\) | wc -l)
                        if [ "${expected}" -ne "${actual}" ]; then
                            echo "WARNING: ${dir}: expected ${expected} entries, found ${actual}"
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
                    sh '''
                        export SSHPASS="${SSH_PASS}"
                        TAB=$(printf '\\t')
                        TARGET="${SSH_USER}@${REMOTE_HOST}"

                        # fixed directory name keeps the compose project name stable across restores
                        sshpass -e ssh ${SSH_OPTS} "${TARGET}" "mkdir -p docker-restore" < /dev/null
                        sshpass -e scp ${SSH_OPTS} "${SELECTED_BACKUP}/docker-compose.yaml" \
                            "${TARGET}:docker-restore/docker-compose.yaml" < /dev/null

                        while IFS="${TAB}" read -r net driver subnet; do
                            echo "Ensuring network ${net}"
                            sshpass -e ssh ${SSH_OPTS} "${TARGET}" "sh -s -- '${net}' '${driver}' '${subnet}'" <<'EOF'
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
                    '''
                }
            }
        }

        // For each mount: SCP the folder to a temp dir on the target, copy it into place with sudo, re-apply ownership
        stage('Restore Volumes and Mounts') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.SSH_CREDENTIAL,
                    usernameVariable: 'SSH_USER',
                    passwordVariable: 'SSH_PASS'
                )]) {
                    sh '''
                        export SSHPASS="${SSH_PASS}"
                        TAB=$(printf '\\t')
                        SEP=$(printf '\\001')
                        TARGET="${SSH_USER}@${REMOTE_HOST}"

                        cleanup() {
                            sshpass -e ssh ${SSH_OPTS} "${TARGET}" "sudo rm -rf '$1'" < /dev/null || true
                        }

                        while IFS="${TAB}" read -r type name src kind dir; do
                            echo "Restoring ${dir} -> ${src}"
                            # /var/tmp instead of /tmp: /tmp is often a RAM-backed tmpfs
                            STAGE=$(sshpass -e ssh ${SSH_OPTS} "${TARGET}" "mktemp -d /var/tmp/restore-stage.XXXXXX" < /dev/null)

                            # manifest entries of this mount only, with the leading folder column removed
                            awk -F"${SEP}" -v d="${dir}" -v s="${SEP}" \
                                'index($0, d s) == 1 { print substr($0, length(d) + 2) }' \
                                "${SELECTED_BACKUP}/ownership.tsv" > own.tsv

                            sshpass -e scp ${SSH_OPTS} -r "${SELECTED_BACKUP}/${dir}" "${TARGET}:${STAGE}/data" < /dev/null \
                                || { cleanup "${STAGE}"; exit 1; }
                            sshpass -e scp ${SSH_OPTS} own.tsv "${TARGET}:${STAGE}/own.tsv" < /dev/null \
                                || { cleanup "${STAGE}"; exit 1; }

                            sshpass -e ssh ${SSH_OPTS} "${TARGET}" "sudo sh -s -- '${type}' '${name}' '${src}' '${kind}' '${STAGE}'" <<'EOF' || { cleanup "${STAGE}"; exit 1; }
set -eu
type=$1; name=$2; dest=$3; kind=$4; stage=$5
SEP=$(printf '\\001')

# compose declares named volumes as external, so they must exist; the mountpoint is resolved by docker
if [ "$type" = volume ]; then
    docker volume inspect "$name" > /dev/null 2>&1 || docker volume create "$name" > /dev/null
    dest=$(docker volume inspect --format '{{.Mountpoint}}' "$name")
fi

if [ "$kind" = d ]; then
    mkdir -p "$dest"
    cp -a "$stage/data/." "$dest/"
else
    mkdir -p "$(dirname "$dest")"
    cp -f "$stage/data" "$dest"
fi

# SCP cannot carry ownership/permissions, so they are re-applied from the manifest (chown before chmod: chown clears suid/sgid)
while IFS="$SEP" read -r uid gid mode ftype link rel; do
    if [ -n "$rel" ]; then p="$dest/$rel"; else p="$dest"; fi
    case "$ftype" in
        l)
            ln -sfn "$link" "$p"
            chown -h "$uid:$gid" "$p"
            ;;
        f|d)
            if [ -e "$p" ]; then
                chown "$uid:$gid" "$p"
                chmod "$mode" "$p"
            fi
            ;;
    esac
done < "$stage/own.tsv"

rm -rf "$stage"
EOF
                        done < "${SELECTED_BACKUP}/mounts.tsv"
                    '''
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
                    sh '''
                        export SSHPASS="${SSH_PASS}"
                        sshpass -e ssh ${SSH_OPTS} "${SSH_USER}@${REMOTE_HOST}" 'sh -s' <<'EOF'
set -eu
cd docker-restore
# supports both the compose v2 plugin and the standalone docker-compose binary
if sudo docker compose version > /dev/null 2>&1; then
    sudo docker compose -f docker-compose.yaml up -d
else
    sudo docker-compose -f docker-compose.yaml up -d
fi
EOF
                    '''
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
