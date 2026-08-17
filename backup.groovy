// Full-system backup pipeline: streams a tar archive from the target host to the Jenkins agent,
// verifies its integrity, uploads it to NAS storage, and rotates old backups.
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
        // Smaller chunks reduce peak disk usage on the Jenkins agent
        string(
            name: 'CHUNK_SIZE',
            defaultValue: '2G',
            trim: true,
            description: 'Size of each backup chunk (e.g. 2G, 500M)'
        )
    }

    environment {
        REMOTE_HOST  = "${params.TARGET_HOST}"
        NAS_HOST     = "${params.NAS_HOST}"
        NAS_PATH     = "${params.BACKUP_PATH}"
        DAYS_TO_KEEP = "${params.DAYS_TO_KEEP}"
        CHUNK_SIZE   = "${params.CHUNK_SIZE}"
    }

    stages {

        // Remove any stale backup directories left from a previous failed run
        stage('Cleanup Workspace') {
            steps {
                sh 'rm -rf */ || true'
            }
        }

        // Computes the backup name, then streams tar | zstd | split directly into named chunks
        stage('Create and Stream Backup') {
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
                    sh '''
                        mkdir -p "${BACKUP_NAME}"
                        # --warning=no-file-changed suppresses non-fatal "file changed as we read it" warnings
                        # excluded paths are virtual/pseudo filesystems or volatile dirs that must not be restored
                        sshpass -p "${SSH_PASS}" ssh -o StrictHostKeyChecking=no \
                            -o ServerAliveInterval=60 -o ServerAliveCountMax=10 \
                            ${SSH_USER}@${REMOTE_HOST} \
                            "sudo tar -cpf - \
                                --warning=no-file-changed \
                                --exclude=/proc \
                                --exclude=/sys \
                                --exclude=/dev \
                                --exclude=/run \
                                --exclude=/tmp \
                                --exclude=/mnt \
                                --exclude=/media \
                                --exclude=/lost+found \
                                /" | \
                        zstd | \
                        split -b "${CHUNK_SIZE}" -d --suffix-length=3 - "${BACKUP_NAME}/part."
                    '''
                }
            }
        }

        // Reassembles and decompresses chunks in-memory to validate the archive without writing to disk
        stage('Verify Backup Integrity') {
            steps {
                sh '''
                    echo "Verifying backup integrity..."
                    cat "${BACKUP_NAME}"/part.* | zstd -d | tar -tf - > /dev/null
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

