// Build pipeline for a specific core target
// Additional Parameters:-
//   param_job_folder: Name of root folder to create job in e.g "FPGAArcade-replay_common"
//   param_core_name: Name of core to build e.g "PacMan"
//   param_target_name: Target platform to build for e.g "R1"
import groovy.json.JsonOutput

String job_folder = "${param_repo.owner}-${param_repo.name}"
folder("${job_folder}")

folder("${job_folder}/${param_core.name}")

pipelineJob("${job_folder}/${param_core.name}/${param_core_target}") {
  description("Auto-created build job for ${param_core.name} [${param_core_target}]")

  environmentVariables {
    // REVIEW: is this already available?
    env('job_folder', job_folder)

    env('core_name', param_core.name)
    env('core_path', param_core.path)
    env('core_target', param_core_target)

    env('repo_owner', param_repo.owner)
    env('repo_name', param_repo.name)
    env('repo_credential_id', param_repo.credentialId)
    env('repo_url', param_repo.url)
    env('repo_branch', param_repo.branch)
    env('repo_source_includes', param_source_includes[param_repo.name] ? param_source_includes[param_repo.name].join('\n') : '')

    env('repo_replay_ci_name', param_repo_replay_ci.name)
    env('repo_replay_ci_url', param_repo_replay_ci.url)
    env('repo_replay_ci_branch', param_repo_replay_ci.branch)

    env('repo_replay_common_owner', param_repo_replay_common.owner)
    env('repo_replay_common_name', param_repo_replay_common.name)
    env('repo_replay_common_credential_id', param_repo_replay_common.credentialId)
    env('repo_replay_common_url', param_repo_replay_common.url)
    env('repo_replay_common_branch', param_repo_replay_common.branch)
    env('repo_replay_common_source_includes', param_source_includes[param_repo_replay_common.name] ? param_source_includes[param_repo_replay_common.name].join('\n') : '')

    env('repo_psfpga_owner', param_repo_psfpga.owner)
    env('repo_psfpga_name', param_repo_psfpga.name)
    env('repo_psfpga_credential_id', param_repo_psfpga.credentialId)
    env('repo_psfpga_url', param_repo_psfpga.url)
    env('repo_psfpga_branch', param_repo_psfpga.branch)
    env('repo_psfpga_source_includes', param_source_includes[param_repo_psfpga.name] ? param_source_includes[param_repo_psfpga.name].join('\n') : '')
  }

  definition {
    cps {
      script(readFileFromWorkspace('replay_ci/jobs/core_target.groovy'))
      sandbox()
    }
  }

  properties {
    githubProjectUrl("https://github.com/${param_repo.owner}/${param_repo.name}/")

    pipelineTriggers {
      triggers {
        if (param_config.isProduction)
          githubPush()
        else {
          pollSCM {
            scmpoll_spec('*/2 * * * *')
          }
        }
      }
    }
  }

  logRotator {
    numToKeep(20)
  }
}

    //   promotions {
    //     promotion {
    //       name("Stable Release")
    //       icon("star-gold")
    //       conditions {
    //         manual("")
    //       }
    //       wrappers {
    //         credentialsBinding {
    //           string('releaseapikey', 'release-api-key')
    //           string('discordreleasewebhook', 'discord-release-notification-webhook')
    //         }
    //       }
    //       actions {
    //         // DEPRECATED: Will be removed once upload API considered stable
    //         copyArtifacts("\${PROMOTED_JOB_NAME}") {
    //           buildSelector {
    //               buildNumber("\${PROMOTED_NUMBER}")
    //             }
    //           includePatterns("*.zip")
    //           targetDirectory("/home/jenkins/www/releases/cores/${core_target}/${core.name}/")
    //         }
    //         // TODO: Move to separate script with args or env vars
    //         // TODO: Move release notification handling into release API as event based on new build post.
    //         shell("""\
    //               #!/bin/bash
    //               hash curl 2>/dev/null || { echo >&2 "curl (curl) required but not found.  Aborting."; exit 1; }
    //               hash xmllint 2>/dev/null || { echo >&2 "xmllint (libxml2-utils) required but not found.  Aborting."; exit 1; }

    //               BUILD_DIR="\${JENKINS_HOME}/jobs/${job_folder}/jobs/${core.name}/jobs/${core_target}/builds/\${PROMOTED_NUMBER}"
    //               BUILD_DATE=`xmllint --nowarning --xpath "/build/timestamp/text()" \${BUILD_DIR}/build.xml`
    //               RELEASE_ZIP=`ls "\${BUILD_DIR}/archive/${core.name}_${core_target}_"*.zip`
    //               RELEASE_ZIP_NAME=`basename \${RELEASE_ZIP}`

    //               # DEPRECATED: Will be removed once Jenkins migrated to docker and new api upload considered stable.
    //               # Update "latest" sym link
    //               RELEASE_DIR="/home/jenkins/www/releases/cores/${core_target}/${core.name}"
    //               ln -sf "\${RELEASE_DIR}/\${RELEASE_ZIP_NAME}" "\${RELEASE_DIR}/latest"

    //               echo "Promoting build \${PROMOTED_NUMBER} to stable release: \${RELEASE_ZIP}"
    //               echo \${BUILD_DATE}

    //               # Upload to release api
    //               status=`curl --silent --output /dev/stderr -w "%{http_code}" --request POST \
    //                           --header "Authorization: APIKey \${releaseapikey}" \
    //                           --form "buildinfo={
    //                                       \\"platformId\\": \\"${core_target}\\",
    //                                       \\"coreId\\": \\"${core.name}\\",
    //                                       \\"buildType\\": \\"stable\\",
    //                                       \\"buildDate\\": \${BUILD_DATE}
    //                                     };type=application/json" \
    //                           --form "zipfile=@\\"\${RELEASE_ZIP}\\";type=application/zip" \
    //                           \${RELEASE_API_URL}builds/`
    //               if [ "\${status}" -lt 200 ] || [ "\${status}" -ge 300 ]; then
    //                 echo >&2 "API upload failed. Aborting."
    //                 exit 1
    //               fi

    //               # Notify discord
    //               read -d '' DISCORD_MESSAGE <<EOF
    //               {
    //                 "content": "A new core stable release is available.",
    //                 "embeds": [
    //                   {
    //                     "title": "${core.name} (${core_target})",
    //                     "url": "https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/\${RELEASE_ZIP_NAME}|\${RELEASE_ZIP_NAME}",
    //                     "color": null,
    //                     "fields": [
    //                       {
    //                         "name": "Download",
    //                         "value": "[\${RELEASE_ZIP_NAME}](https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/\${RELEASE_ZIP_NAME})"
    //                       },
    //                       {
    //                         "name": "Previous Releases",
    //                         "value": "https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/"
    //                       }
    //                     ]
    //                   }
    //                 ]
    //               }
    //               EOF

    //               curl -X POST --header "Content-Type: application/json" --data "\${DISCORD_MESSAGE}" \${discordreleasewebhook}

    //               exit \$?
    //               """.stripIndent())
    //       }
    //     }
    //   }
    // }

    // wrappers {
    //   credentialsBinding {
    //     string('discordbuildwebhookurl', 'discord-build-notification-webhookurl')
    //   }
    // }
    // configure { project ->
    //   project / publishers << 'nz.co.jammehcow.jenkinsdiscord.WebhookPublisher' {
    //     webhookURL '${discordbuildwebhookurl}'
    //     //branchName '${GIT_BRANCH}'
    //     statusTitle "${job_name} #\${BUILD_NUMBER}"
    //     //notes 'some notes here'
    //     //thumbnailURL 'https://example.com/thumbnail.jpg'
    //     sendOnStateChange false
    //     enableUrlLinking true
    //     enableArtifactList true
    //     enableFooterInfo false
    //     showChangeset false
    //     sendLogFile false
    //     sendStartNotification false
    //   }
    // }
  // }