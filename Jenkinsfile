pipeline {
    agent any

    tools {
        maven 'maven3'
        jdk 'jdk25'
    }

    environment {
        SPRING_PROFILES_ACTIVE = 'prd'
        APP_ACCESS_PASSWORD = credentials('STREAMFLOW_ACCESS_PASSWORD')
        APP_ADMIN_KEY = credentials('STREAMFLOW_ADMIN_KEY')
        CLOUDFLARE_CALLS_APP_ID = credentials('STREAMFLOW_CF_APP_ID')
        CLOUDFLARE_CALLS_APP_SECRET = credentials('STREAMFLOW_CF_APP_SECRET')
        CLOUDFLARE_CALLS_ACCOUNT_ID = credentials('STREAMFLOW_CF_ACCOUNT_ID')
        CLOUDFLARE_CALLS_ANALYTICS_TOKEN = credentials('STREAMFLOW_CF_ANALYTICS_TOKEN')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build JAR') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Build & Deploy Docker') {
            steps {
                script {
                    sh """
                        # 1. Baixa o docker-compose se não existir
                        if [ ! -f "./docker-compose" ]; then
                            echo "--- Baixando Docker Compose... ---"
                            curl -SL https://github.com/docker/compose/releases/download/v2.29.0/docker-compose-linux-x86_64 -o docker-compose
                            chmod +x docker-compose
                        fi

                        echo "--- Garantindo que a rede externa exista ---"
                        docker network create prd-streamflow-network || true

                        echo "--- Parando containers antigos do StreamFlow PRD ---"
                        ./docker-compose -p prd-streamflow stop prd-app-streamflow || true
                        ./docker-compose -p prd-streamflow rm -f prd-app-streamflow || true

                        echo "--- Subindo novos containers (Build + Deploy) ---"
                        ./docker-compose -p prd-streamflow up -d --build
                    """
                }
            }
        }

        stage('Cleanup') {
            steps {
                sh 'docker image prune -f'
            }
        }
    }
}
