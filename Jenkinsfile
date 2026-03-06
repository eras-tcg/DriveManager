pipeline {
    agent any

    environment {
        IMAGE_NAME = 'drive-manager'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Image') {
            steps {
                sh "docker build -t ${IMAGE_NAME}:${GIT_COMMIT} ./drive_manager"
            }
        }

        stage('Deploy to Staging') {
            when {
                expression { env.GIT_BRANCH == 'origin/develop' }
            }
            steps {
                sh """
                    docker stop ${IMAGE_NAME}-staging || true
                    docker rm ${IMAGE_NAME}-staging || true
                    docker run -d \\
                        --name ${IMAGE_NAME}-staging \\
                        --restart unless-stopped \\
                        --env-file /etc/drive-manager/.env \\
                        -p 8091:8081 \\
                        ${IMAGE_NAME}:${GIT_COMMIT}
                """
            }
        }

        stage('Approve Production Deploy') {
            when {
                expression { env.GIT_BRANCH == 'origin/main' }
            }
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: 'Deploy to production?', ok: 'Deploy'
                }
            }
        }

        stage('Deploy to Production') {
            when {
                expression { env.GIT_BRANCH == 'origin/main' }
            }
            steps {
                sh """
                    docker stop ${IMAGE_NAME}-prod || true
                    docker rm ${IMAGE_NAME}-prod || true
                    docker run -d \\
                        --name ${IMAGE_NAME}-prod \\
                        --restart unless-stopped \\
                        --env-file /etc/drive-manager/.env \\
                        -p 8081:8081 \\
                        ${IMAGE_NAME}:${GIT_COMMIT}
                """
            }
        }
    }

    post {
        failure {
            echo 'Pipeline failed.'
        }
        success {
            echo 'Pipeline completed successfully.'
        }
    }
}
