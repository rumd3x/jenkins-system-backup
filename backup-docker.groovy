// Docker-focused backup pipeline: generates a compose file that recreates every container on the target
// host (red5d/docker-autocompose), copies all container volumes/bind mounts uncompressed over SCP,
// uploads the result to NAS storage, and rotates old backups.
//
// Backup layout (YYYY_MM_DD-<host>/):
//   docker-compose.yaml  recreates all containers
//   mounts.tsv           type, volume name, host source path, kind (d/f), backup folder
//   networks.tsv         user-defined networks to pre-create on restore (name, driver, subnet)
//   ownership.tsv        uid/gid/mode of every copied entry, since SCP cannot preserve ownership
//   volumes/<name>/      content of each named volume
//   binds/<abs path>/    content of each bind mount

// Runs a script (read from stdin) on TARGET_HOST over SSH with a working `sudo` even though the session
// has no TTY: the SSH password is sent as the first stdin line and fed to `sudo -S` by a shell function.
// Usage inside an sh step: rsh [script args...] <<'EOF' ... EOF
def RSH = '''
SUDO_PRELUDE='if [ "$(id -u)" = 0 ]; then sudo() { "$@"; }; else sudo() { printf "%s\\n" "$SUDO_PASS" | command sudo -S -p "" "$@"; }; fi'
REMOTE_WRAPPER="sh -c 'IFS= read -r SUDO_PASS; eval \\"\\$(cat)\\"'"
rsh() {
    RSH_ARGS=""
    for a in "$@"; do RSH_ARGS="${RSH_ARGS} '${a}'"; done
    {
        printf '%s\\n' "${SSH_PASS}"
        printf '%s\\n' "${SUDO_PRELUDE}"
        printf '%s\\n' "set -- ${RSH_ARGS}"
        cat
    } | sshpass -e ssh ${SSH_OPTS} "${SSH_USER}@${REMOTE_HOST}" "${REMOTE_WRAPPER}"
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
        // Kept for parameter compatibility with v1; the Docker variant does not compress or chunk the backup
        string(
            name: 'CHUNK_SIZE',
            defaultValue: '2G',
            trim: true,
            description: 'Unused in the Docker variant (volumes are copied as-is). Kept for compatibility with v1'
        )
    }

    environment {
        REMOTE_HOST  = "${params.TARGET_HOST}"
        NAS_HOST     = "${params.NAS_HOST}"
        NAS_PATH     = "${params.BACKUP_PATH}"
        DAYS_TO_KEEP = "${params.DAYS_TO_KEEP}"
        SSH_OPTS     = '-o StrictHostKeyChecking=no -o ServerAliveInterval=60 -o ServerAliveCountMax=10'
    }

    stages {

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
                        rsh > "${BACKUP_NAME}/docker-compose.yaml" <<'EOF'
set -eu
NAMES=$(sudo docker ps -a --format '{{.Names}}')
if [ -z "$NAMES" ]; then
    echo "No containers found on the target host" >&2
    exit 1
fi
# the Docker Hub image is stale and crashes on current Docker engines (KeyError: 'MacAddress'); GHCR is maintained
sudo docker run --rm -v /var/run/docker.sock:/var/run/docker.sock ghcr.io/red5d/docker-autocompose:latest $NAMES
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

        // For each mount: record ownership, stage a copy owned by the SSH user (sudo), SCP it uncompressed, delete the stage
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
                        SEP=$(printf '\\001')
                        TARGET="${SSH_USER}@${REMOTE_HOST}"

                        cleanup() {
                            echo 'sudo rm -rf "$1"' | rsh "$1" || true
                        }

                        : > "${BACKUP_NAME}/ownership.tsv"

                        while IFS="${TAB}" read -r type name src kind dir; do
                            echo "Copying ${src} -> ${dir}"
                            # /var/tmp instead of /tmp: /tmp is often a RAM-backed tmpfs
                            STAGE=$(echo 'mktemp -d /var/tmp/backup-stage.XXXXXX' | rsh)

                            # stdout is the ownership manifest of the source; the script also fills ${STAGE}/data
                            rsh "${src}" "${kind}" "${STAGE}" "${SSH_USER}" > ownership.part <<'EOF' || { cleanup "${STAGE}"; exit 1; }
set -eu
src=$1; kind=$2; stage=$3; owner=$4
SEP=$(printf '\\001')
sudo find "$src" -printf "%U${SEP}%G${SEP}%m${SEP}%y${SEP}%l${SEP}%P\\n"
if [ "$kind" = d ]; then
    sudo mkdir "$stage/data"
    sudo cp -a "$src/." "$stage/data/"
else
    sudo cp -a "$src" "$stage/data"
fi
# SCP follows symlinks and blocks on fifos/sockets: keep only files and dirs (symlinks live in the manifest)
sudo find "$stage/data" ! -type f ! -type d -delete
sudo chown -R "$owner" "$stage/data"
sudo chmod -R u+rwX "$stage/data"
EOF

                            # a parent mount copied earlier may already contain this path
                            rm -rf "${BACKUP_NAME}/${dir}"
                            mkdir -p "$(dirname "${BACKUP_NAME}/${dir}")"
                            sshpass -e scp ${SSH_OPTS} -r "${TARGET}:${STAGE}/data" "${BACKUP_NAME}/${dir}" < /dev/null \
                                || { cleanup "${STAGE}"; exit 1; }

                            awk -v d="${dir}" -v s="${SEP}" '{ print d s $0 }' ownership.part >> "${BACKUP_NAME}/ownership.tsv"
                            cleanup "${STAGE}"
                        done < "${BACKUP_NAME}/mounts.tsv"
                    ''')
                }
            }
        }

        // Checks every mount folder exists and compares entry counts against the ownership manifest
        stage('Verify Backup Integrity') {
            steps {
                sh '''
                    echo "Verifying backup integrity..."
                    TAB=$(printf '\\t')
                    SEP=$(printf '\\001')
                    test -s "${BACKUP_NAME}/docker-compose.yaml"
                    grep -q '^services:' "${BACKUP_NAME}/docker-compose.yaml"

                    while IFS="${TAB}" read -r type name src kind dir; do
                        if [ ! -e "${BACKUP_NAME}/${dir}" ]; then
                            echo "ERROR: ${dir} is missing from the backup"
                            exit 1
                        fi
                        expected=$(awk -F"${SEP}" -v d="${dir}" '$1==d && ($5=="f" || $5=="d")' "${BACKUP_NAME}/ownership.tsv" | wc -l)
                        actual=$(find "${BACKUP_NAME}/${dir}" \\( -type f -o -type d \\) | wc -l)
                        # live data can change while being copied, so a mismatch is reported but not fatal
                        if [ "${expected}" -ne "${actual}" ]; then
                            echo "WARNING: ${dir}: expected ${expected} entries, found ${actual}"
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
                        sshpass -p "${NAS_PASS}" scp -o StrictHostKeyChecking=no \
                            -o ServerAliveInterval=60 -o ServerAliveCountMax=10 -r \
                            "${BACKUP_NAME}" ${NAS_USER}@${NAS_HOST}:${NAS_PATH}/
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
                        HOST_NAME=$(echo "${REMOTE_HOST}" | tr '.' '_')

                        # || true prevents pipeline failure when no backups exist yet for this host
                        DIRS=$(sshpass -p "${NAS_PASS}" ssh -o StrictHostKeyChecking=no \
                            ${NAS_USER}@${NAS_HOST} \
                            "ls -1d ${NAS_PATH}/*-${HOST_NAME} 2>/dev/null | sort" || true)

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
                            sshpass -p "${NAS_PASS}" ssh -o StrictHostKeyChecking=no \
                                ${NAS_USER}@${NAS_HOST} \
                                "rm -rf $(echo $TO_DELETE | tr '\n' ' ')"
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
