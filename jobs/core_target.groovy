// Core target build job
pipeline {
    agent none

    stages {
      stage('Build and Package') {
        agent any
        stages {
          // Jenkins is not able to determine other core build deps currently
          // We assume only replay_common & replay_ci exist as a dep and that every
          // core build depends on them.
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
          // HACK: The "psx" core in replay_console requires a 3rd repo.
          // This is not yet supported thus this hack to hard code an extra
          // repo until the seed job is upgraded to read a jenkins configuration file
          // from the core dir and allow arbitrary extra repos.
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
          stage('Building') {
            steps {
              dir("${env.REPO_NAME}/${env.CORE_PATH}") {
                sh script:"chmod 700 '${WORKSPACE}/replay_ci/scripts/build_core.sh'"
                sh script:"'${WORKSPACE}/replay_ci/scripts/build_core.sh' '${env.CORE_TARGET}'"
              }
            }
          }
          stage('Packaging') {
            steps {
              sh script:"rm *.zip"

              dir("${env.REPO_NAME}/${env.CORE_PATH}") {
                sh script:"chmod 500 '${WORKSPACE}/replay_ci/scripts/package_core.sh'"
                sh script:"'${WORKSPACE}/replay_ci/scripts/package_core.sh' '${env.CORE_NAME}' '${env.CORE_TARGET}'"
              }
            }
          }
        }
        post {
          success {
            archiveArtifacts artifacts: '*.zip', followSymlinks: false

            stash name: "artifacts", includes: "*.zip"
          }
        }
      }


      stage('Publish') {
        input {
          message "Publish this build as a stable release?"
        }
        agent any
        steps {
          // TODO: Unstash and deploy artifacts
          sh "echo deploying stable release..."
        }

      }
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