
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

        }
        stage("Cloning Git") {

        }
        stage("Building Jar") {

        }
        stage("Upload To S3") {

        }
        stage("Deploy") {

        }
        stage("Clean Up") {

        }
    }
}