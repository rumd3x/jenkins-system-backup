// Full-system restore pipeline: downloads a selected tar backup from NAS storage,
// verifies its integrity, streams it to the target host, and reboots the machine.
pipeline {
    agent { label 'docker' }

    parameters {
        string(
            name: 'TARGET_HOST',
            defaultValue: '',
            trim: true,
            description: 'IP or hostname of the machine that will receive the restored filesystem'
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
            description: 'Full path to the NAS directory that contains the .tar backup files'
        )
    }

    environment {
        REMOTE_HOST = "${params.TARGET_HOST}"
        NAS_HOST    = "${params.NAS_HOST}"
        NAS_PATH    = "${params.BACKUP_PATH}"
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

        stage('Verify Backup Integrity') {
            steps {
                sh '''
                    echo "Verifying backup integrity..."
                    cat "${SELECTED_BACKUP}"/part.* | zstd -d | tar -tf - > /dev/null
                    echo "Backup integrity OK"
                '''
            }
        }

        // Streams all chunks decompressed to the target in one pipe to avoid double disk usage
        stage('Stream and Restore Backup on Target Host') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.SSH_CREDENTIAL,
                    usernameVariable: 'SSH_USER',
                    passwordVariable: 'SSH_PASS'
                )]) {
                    sh '''
                        # --numeric-owner preserves original uid/gid numbers regardless of account names on target
                        cat "${SELECTED_BACKUP}"/part.* | zstd -d | \
                            sshpass -p "${SSH_PASS}" ssh -o StrictHostKeyChecking=no \
                            -o ServerAliveInterval=60 -o ServerAliveCountMax=10 \
                            ${SSH_USER}@${REMOTE_HOST} \
                            "sudo tar -xpf - -C / --numeric-owner && sudo reboot"
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

