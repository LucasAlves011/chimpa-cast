pipeline {
    agent any

    tools {
        maven 'maven3'
        jdk 'jdk25'
    }

    environment {
        SPRING_PROFILES_ACTIVE = 'prd'
        APP_ADMIN_KEY = credentials('CHIMPACAST_ADMIN_KEY')
        APP_SESSION_SECRET = credentials('CHIMPACAST_SESSION_SECRET')
        CLOUDFLARE_CALLS_APP_ID = credentials('CHIMPACAST_CF_APP_ID')
        CLOUDFLARE_CALLS_APP_SECRET = credentials('CHIMPACAST_CF_APP_SECRET')
        CLOUDFLARE_CALLS_ACCOUNT_ID = credentials('CHIMPACAST_CF_ACCOUNT_ID')
        CLOUDFLARE_CALLS_ANALYTICS_TOKEN = credentials('CHIMPACAST_CF_ANALYTICS_TOKEN')
        DISCORD_CLIENT_ID = "${params.CHIMPACAST_DISCORD_CLIENT_ID}"
        DISCORD_CLIENT_SECRET = credentials('CHIMPACAST_DISCORD_CLIENT_SECRET')
        DISCORD_BOT_TOKEN = credentials('CHIMPACAST_DISCORD_BOT_TOKEN')
        DISCORD_GUILD_ID = "${params.CHIMPACAST_DISCORD_GUILD_ID}"
        DISCORD_CHANNEL_ID = "${params.CHIMPACAST_DISCORD_CHANNEL_ID}"
        DISCORD_REDIRECT_URI = "${params.CHIMPACAST_DISCORD_REDIRECT_URI}"
        DISCORD_PUBLIC_URL = "${params.CHIMPACAST_DISCORD_PUBLIC_URL}"
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
                        docker network create prd-chimpacast-network || true

                        echo "--- Parando containers antigos do ChimpaCast PRD ---"
                        ./docker-compose -p prd-chimpacast stop prd-app-chimpacast || true
                        ./docker-compose -p prd-chimpacast rm -f prd-app-chimpacast || true

                        echo "--- Subindo novos containers (Build + Deploy) ---"
                        ./docker-compose -p prd-chimpacast up -d --build
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
