// Core target build job
pipeline {
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
        // stage('Prerequisites') {
        //   steps {
            // # Crude packaging script for releases
            // hash zip 2>/dev/null || { echo >&2 "zip required but not found.  Aborting."; exit 1; }
            // hash git 2>/dev/null || { echo >&2 "git required but not found.  Aborting."; exit 1; }
            // hash python 2>/dev/null || { echo >&2 "python required but not found.  Aborting."; exit 1; }

            // python_major_v=\$(python -c"import sys; print(sys.version_info.major)")
            // python_minor_v=\$(python -c"import sys; print(sys.version_info.minor)")

            // if [[ "\${python_major_v}" -lt "3" || ("\${python_major_v}" -eq "3" && "\${python_minor_v}" -lt "6") ]]; then
            //     echo "Build system requires python 3.6 or greater (\${python_major_v}.\${python_minor_v} installed)"
            //     exit 1
            // fi
        //   }
        // }
        stage('Building') {
          steps {
            sh script:"""\
              #!/bin/bash
              pushd "${env.REPO_NAME}/${env.CORE_PATH}" || exit \$?
              python rmake.py infer --target "${env.CORE_TARGET}" || exit \$?
              popd
              """.stripIndent()
          }
        }
        stage('Packaging') {
          steps {
            sh script:"""\
              #!/bin/bash
              # Clean up prior build zip artifacts
              rm *.zip
              pushd "${env.REPO_NAME}/${env.CORE_PATH}/sdcard" || exit \$?

              # TODO: Determine API version
              VERSION=`git describe --tags --always --long`
              DATE=`date -u '+%Y%m%d_%H%M'`
              RELEASE_ZIP="${env.CORE_NAME}_${env.CORE_TARGET}_\${DATE}_\${VERSION}.zip"

              echo "RELEASE_ZIP_NAME: \${RELEASE_ZIP}"

              zip -r "\${RELEASE_ZIP}" *
              popd
              mv "${env.REPO_NAME}/${env.CORE_PATH}/sdcard/\${RELEASE_ZIP}" .

              exit \$?
              """.stripIndent()

            archiveArtifacts artifacts: '*.zip', followSymlinks: false
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