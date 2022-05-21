// Core target build job
pipeline {
    agent any

    stages {
        stage('Checkout: common') {
          steps {
            dir(env.REPO_REPLAY_CI_NAME) {
              git branch: env.REPO_REPLAY_CI_BRANCH, url: env.REPO_REPLAY_CI_URL
            }
            dir(env.REPO_REPLAY_COMMON_NAME) {
              checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${env.REPO_REPLAY_COMMON_BRANCH}"]],
                userRemoteConfigs: [[
                   credentialsId: env.REPO_REPLAY_COMMON_CREDENTIAL_ID,
                   url          : env.REPO_REPLAY_COMMON_URL
                ]],
                extensions: [
                  [$class: 'PathRestriction', excludedRegions: '', includedRegions: env.REPO_REPLAY_COMMON_SOURCE_INCLUDES]
                ]
              ])
            }
            sh "cp \"${env.REPO_REPLAY_CI_NAME}/scripts/local_settings.py\" \"${env.REPO_REPLAY_COMMON_NAME}/scripts/local_settings.py\""
          }
        }
        stage("Checkout: core") {
          when {
            not { equals expected: env.REPO_REPLAY_COMMON_NAME, actual: env.REPO_NAME }
          }
          steps {
            dir(env.REPO_NAME) {
              checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${env.REPO_BRANCH}"]],
                userRemoteConfigs: [[
                  credentialsId: env.REPO_CREDENTIAL_ID,
                  url          : env.REPO_URL
                ]],
                extensions: [
                  [$class: 'PathRestriction', excludedRegions: '', includedRegions: env.REPO_SOURCE_INCLUDES]
                ],
              ])
            }
          }
        }
        stage('Checkout: ps-fpga') {
          when {
            equals expected: "replay_console", actual: env.REPO_NAME
            // equals expected: "psx", actual: core.name
          }
          steps {
            dir(env.REPO_PSFPGA_NAME) {
              checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${env.REPO_PSFPGA_BRANCH}"]],
                userRemoteConfigs: [[credentialsId: env.REPO_PSFPGA_CREDENTIAL_ID, url: env.REPO_PSFPGA_URL]],
                extensions: [
                  [$class: 'PathRestriction', excludedRegions: '', includedRegions: env.REPO_PSFPGA_SOURCE_INCLUDES]
                ]
              ])
            }
          }
        }
        // stage('Building') {
        //   steps {
        //     echo("TODO")
        //   }
        // }
        // stage('Testing') {
        //   steps {
        //     echo("TODO")
        //   }
        // }
        // stage('Packaging') {
        //   steps {
        //     echo("TODO")
        //   }
        // }
    }

    post {
      failure {
        echo "Run when build was a failure"
      }
      success {
        echo "Run when build was a success"
      }
    }
}