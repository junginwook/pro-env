
pipeline {
    agent any

    tools {
        gradle "Gradle 7.6"
        jdk "jdk17"
    }
    environment {
        GIT_DISTRIBUTE_URL = "https://github.com/junginwook/multimodule.git"
    }
    stages {
        stage("Preparing Job") {
            steps {
                script {
                    try {
                        GIT_DISTRIBUTE_BRANCH_MAP = ["dev" : "develop", "qa" : "release", "prod" : "main"]

                        env.GIT_DISTRIBUTE_BRANCH = GIT_DISTRIBUTE_BRANCH_MAP[STAGE]

                        print("Deploy stage is ${STAGE}")
                        print("Deploy service is ${SERVICE}")
                        print("Deploy git branch is ${env.GIT_DISTRIBUTE_BRANCH}")
                    }
                    catch (error) {
                        print(error)
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Preparing Job stage failed"
                }
                success {
                    echo "Preparing Job stage success"
                }
            }
        }
        stage("Cloning Git") {
            steps {
                script {
                    try {
                        git url: GIT_DISTRIBUTE_URL, branch: GIT_DISTRIBUTE_BRANCH, credentialsId: "ssh-key"
                    }
                    catch (error) {
                        print(error)
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Git clone stage failed"
                }
                success {
                    echo "Git clone stage success"
                }
            }
        }
        stage("Building Jar") {
            steps {
                script {
                    try {
                        sh("rm -rf deploy")
                        sh("mkdir deploy")

                        sh("gradle :${SERVICE}:clean :${SERVICE}:build -x test")

                        sh("cd deploy")
                        sh("cp /var/jenkins_home/workspace/${env.JOB_NAME}/${SERVICE}/build/libs/*.jar ./deploy/${SERVICE}.jar")
                    }
                    catch (error) {
                        print(error)
                        sh("sudo rm -rf /var/lib/jenkins/workspace/${env.JOB_NAME}/*")
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Build jar stage failed"
                }
                success {
                    echo "Build jar stage success"
                }
            }
        }
        stage("Upload To S3") {
            steps {
                script {
                    try {
                        def script = """
                        #!/bin/bash
                        ORIGIN_JAR_PATH='/var/jenkins_home/batch/deploy/*.jar'
                        ORIGIN_JAR_NAME=\$(basename \${ORIGIN_JAR_PATH})
                        TARGET_PATH='/home/jenkins_home/batch/application.jar'
                        JAR_BOX_PATH='/home/jenkins_home/batch/jar/'
                        
                        echo "  > 배포 JAR: "\${ORIGIN_JAR_NAME}
                        
                        echo "  > chmod 770 \${ORIGIN_JAR_PATH}"
                        sudo chmod 770 \${ORIGIN_JAR_PATH}
                        
                        echo "  > cp \${ORIGIN_JAR_PATH} \${JAR_BOX_PATH}"
                        sudo cp \${ORIGIN_JAR_PATH} \${JAR_BOX_PATH}
                        
                        echo "  > chown -h jenkins:jenkins \${JAR_BOX_PATH}\${ORIGIN_JAR_NAME}"
                        sudo chown -h jenkins:jenkins \${JAR_BOX_PATH}\${ORIGIN_JAR_NAME}
                        
                        echo "  > sudo ln -s -f \${JAR_BOX_PATH}\${ORIGIN_JAR_NAME} \${TARGET_PATH}"
                        sudo ln -s -f \${JAR_BOX_PATH}\${ORIGIN_JAR_NAME} \${TARGET_PATH}
                        """.stripIndent()
                        writeFile(file: 'deploy/deploy.sh', text: script)

                        sh """
                                cd deploy
                                cat>appspec.yml<<-EOF
                                version: 0.0
                                os: linux
                                files:
                                  - source:  /
                                    destination: /home/jenkins_home/batch/deploy
                                
                                permissions:
                                  - object: /
                                    pattern: "**"
                                    owner: root
                                    group: root
                                
                                hooks:
                                  ApplicationStart:
                                    - location: deploy.sh
                                      timeout: 60
                                      runas: root
                                """.stripIndent()

                        sh """
                                cd deploy
                                zip -r deploy *
                                """


                        withAWS(credentials: "aws-key") {
                            s3Upload(
                                    path: "${env.JOB_NAME}/${env.BUILD_NUMBER}/${env.JOB_NAME}.zip",
                                    file: "/var/jenkins_home/workspace/${env.JOB_NAME}/deploy/deploy.zip",
                                    bucket: "batch-repo"
                            )
                        }
                    }
                    catch (error) {
                        print(error)
                        sh("sudo rm -rf /var/lib/jenkins/workspace/${env.JOB_NAME}/*")
                        currentBuild.result = "FAILURE"
                    }
                }
            }
        }
        stage("Deploy") {
            steps {
                script {
                    try {
                        withAWS(credentials: "aws-key") {
                            sh """
                                aws deploy create-deployment \
                                --application-name inwook-deploy \
                                --deployment-group-name inwook-deploy-group \
                                --region ap-northeast-2 \
                                --s3-location bucket=batch-repo,key=${env.JOB_NAME}/${env.BUILD_NUMBER}/${env.JOB_NAME}.zip,bundleType=zip \
                                --file-exists-behavior OVERWRITE \
                                --output json > DEPLOYMENT_ID.json
                                cat DEPLOYMENT_ID.json
                            """
                        }

                        def DEPLOYMENT_ID = readJSON file: './DEPLOYMENT_ID.json'
                        echo"${DEPLOYMENT_ID.deploymentId}"
                        sh("rm -rf ./DEPLOYMENT_ID.json")

                        awaitDeploymentCompletion("${DEPLOYMENT_ID.deploymentId}")
                    }
                    catch (error) {
                        print(error)
                        sh("sudo rm -rf /var/jenkins_home/workspace/${env.JOB_NAME}/*")
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Deploy stage failed"
                }
                success {
                    echo "Deploy stage success"
                }
            }
        }
        stage("Clean Up") {
            steps {
                echo "clean up"
            }
        }
    }
}