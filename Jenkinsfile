pipeline {
    agent none
    environment {
        PLUGIN_NAME = "com.cdsoftware.paperless"
        IDEMPIERE_VERSION = "12.0.0"
    }
    stages {
        stage('Compile') {
            agent {
                 docker {
                    image 'carl0jgr/idempiere-source-builder:12'
                    args '--entrypoint=\'\' -u root:root -v /var/jenkins_home/.m2:/root/.m2'             
                  }
            }
            steps {
                dir('target-platform') {
                    git branch: '12.0', url: 'https://github.com/ingeint/idempiere-target-platform-plugin.git'
                    sh './plugin-builder build ../${PLUGIN_NAME}'
                    archiveArtifacts artifacts: "target/${PLUGIN_NAME}-${IDEMPIERE_VERSION}.${BUILD_NUMBER}.jar", fingerprint: true
                    sh 'rm -rf target ../${PLUGIN_NAME}/target'
                }
            }
        }
    }
}

