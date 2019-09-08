// Automated Core/Target job creation/removal
//
// Note: Assumes private repo usage currently and that credentials for access
// have been configured using "<param_repo_owner>_<param_repo_name>" as the ID format
// All seeded repos are assumed to require a dependancy on replay_common and
// only replay_common.
//
// Required Params:
//   String param_repo_owner - github repo owner e.g takasa
//   String param_repo_name  - github repo name e.g replay_common
//   String param_repo_url   - https or ssh url of git repo
//   CredentialID param_repo_credential_id - Jenkins credential for repo access (may be empty)

// -----------------------------------------------------------------------------
// Classes
// -----------------------------------------------------------------------------

class Repo {
  String owner
  String name
  String credentialId
  String url
  String branch
}

class Core {
  String name
  String path
  String[] targets
}

// -----------------------------------------------------------------------------
// Main
// -----------------------------------------------------------------------------

// Wrap environment variables
def configuration = new HashMap()
def binding = getBinding()
configuration.putAll(binding.getVariables())

Boolean isProduction = configuration['PRODUCTION_SERVER'] ? configuration['PRODUCTION_SERVER'].toBoolean() : false

out.println("Running on " + (isProduction ? "PRODUCTION" : "TEST") + " server.")

// Wrap Params
Repo repo_info = new Repo(owner: param_repo_owner, name: param_repo_name,
                          credentialId: param_repo_credential_id, url: param_repo_url,
                          branch: param_repo_branch)

// Process repo cores
parseCoresFile('_cores.txt').each { core ->
  createCoreJobs(repo_info, core, true, isProduction)
}

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

// Return Core[] extracted from specified cores file
def parseCoresFile(coresFile) {

  def cores_file = readFileFromWorkspace(coresFile)
  def cores = []
  def unique_names = []

  // Extract core and supported targets
  cores_file.eachLine {
    def matcher = it =~ /(?<targets>(?:\[\w+\])+)\s+(?<path>\S*)/

    if (! matcher.matches()) {
      out.println("Match failure for _core.txt line: ${it}")
      return
    }

    def path = matcher.group('path')
    def name = coreNameFromPath(path)
    if (! name) {
      error "Unable to determine core name for path: ${path}"
      return
    }

    def targets = []
    matcher.group('targets').findAll(/\[(\w+?)\]/) {
      target -> targets << target[1]
    }

    // Fail job if duplicate core names detected
    if (unique_names.contains(name)) {
      error "Duplicate core name '${name}'. Aborting."
    }
    unique_names.add(name)

    cores << new Core(name: name, path: path, targets: targets)
  }

  return cores
}

// Return last directory of given path as core name
def coreNameFromPath(path) {
  def matcher = path =~ /.*?\/*(\w+)\s*$/

  return matcher.matches() ? matcher.group(1) : null
}

// Create separate build job for all supported targets of the specified core
def createCoreJobs(repo, core, queueNewJobs, isProduction) {

  String job_folder = "${repo.owner}-${repo.name}"
  folder(job_folder)

  core.targets.each { core_target ->

    folder("${job_folder}/${core.name}")

    String job_name = "${job_folder}/${core.name}/${core_target}"

    // Other repos need to rebuild only when replay_common framework changes
    // However, replay_common itself also needs to rebuild if loader core changes.
    String replay_common_includes = """\
                                       lib/.*
                                       replay_lib/.*
                                       replay_targets/.*
                                    """.stripIndent()
    String replay_core_includes = """\
                                    ${core.path}/rtl/.*
                                    ${core.path}/sdcard/.*
                                  """.stripIndent()

    if (repo.name == "replay_common") {
      replay_common_includes = replay_common_includes.concat(replay_core_includes)
    }

    String release_channel = isProduction ? "#build_releases" : "#build_notify_test"

    job(job_name) {
      description("Autocreated build job for ${job_name}")
      properties {
        githubProjectUrl("https://github.com/${repo.owner}/${repo.name}/")
        promotions {
          promotion {
            name("Stable Release")
            icon("star-gold")
            conditions {
              manual("")
            }
            wrappers {
              credentialsBinding {
                string('slackwebhookurl', 'slackwebhookurl')
              }
            }
            actions {
              copyArtifacts("\${PROMOTED_JOB_NAME}") {
                buildSelector {
                    buildNumber("\${PROMOTED_NUMBER}")
                  }
                includePatterns("*.zip")
                // TODO: Carry through build meta to allow computers/console/... group to be used?
                targetDirectory("/home/jenkins/www/releases/cores/${core_target}/${core.name}/")
              }
              // HACK: Using curl based slack messaging as slackNotifier is not available in stepContext.
              // TODO: Using build log to determine the name of the release zip artifact is hacky. See what json api holds.
              shell("""\
                    #!/bin/bash
                    RELEASE_ZIP=`grep -a "RELEASE_ZIP_NAME:" "\${JENKINS_HOME}/jobs/${job_folder}/jobs/${core.name}/jobs/${core_target}/builds/\${PROMOTED_NUMBER}/log" | cut -d " " -f2 | awk '{\$1=\$1}1'`

                    read -d '' SLACK_MESSAGE <<EOF
                    New stable release of ${core.name} for the ${core_target}.
                    <https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/\${RELEASE_ZIP}|Download Zip>
                    EOF

                    curl -X POST --data "payload={\\"text\\": \\"\${SLACK_MESSAGE}\\", \\"channel\\": \\"${release_channel}\\", \\"username\\": \\"jenkins\\", \\"icon_emoji\\": \\":ghost:\\"}" \${slackwebhookurl}

                    exit \$?
                    """.stripIndent())
            }
          }
        }
      }
      multiscm {
        // Jenkins is not able to determine other core build deps currently
        // We assume only replay_common exists as a dep and that every core
        // depends on it.
        git {
          remote {
            if (isProduction) {
              url("git@github.com:Takasa/replay_common.git")
              credentials("takasa_replay_common")
            } else {
              url("git@github.com:Sector14/replay_common.git")
              credentials("sector14_replay_common")
            }
          }
          extensions {
            relativeTargetDirectory('replay_common')
            pathRestriction {
              includedRegions(replay_common_includes)
              excludedRegions('')
            }
          }
          branch('master')
        }

        if (repo.name != "replay_common") {
          git {
            remote {
              url(repo.url)
              credentials(repo.credentialId)
            }
            extensions {
              relativeTargetDirectory(repo.name)
              pathRestriction {
                includedRegions(replay_core_includes)
                excludedRegions('')
              }
            }
            branch(repo.branch)
          }
        }
      }
      triggers {
        gitHubPushTrigger()
      }
      steps {
        shell("""\
              #!/bin/bash
              # Crude packaging script for releases
              hash zip 2>/dev/null || { echo >&2 "zip required but not found.  Aborting."; exit 1; }
              hash git 2>/dev/null || { echo >&2 "git required but not found.  Aborting."; exit 1; }
              hash python 2>/dev/null || { echo >&2 "python required but not found.  Aborting."; exit 1; }

              python --version

              ######################################################################
              # Build Settings
              ######################################################################

              # Create local settings if this is a replay_common repo
              [ -d "replay_common/scripts/" ] && cat << EOF > replay_common/scripts/local_settings.py
              # Local paths auto generated via jenkins project build script
              ISE_PATH = '/opt/Xilinx/14.7/ISE_DS/ISE/'
              ISE_BIN_PATH = '/opt/Xilinx/14.7/ISE_DS/ISE/bin/lin64/'

              MODELSIM_PATH = ''
              QUARTUS_PATH = '/opt/intelFPGA_lite/18.1/quartus/bin/'

              # if UNISIM_PATH is empty, a local (tb/sim) library will be created
              UNISIM_PATH = None

              EOF

              ######################################################################
              # Build
              ######################################################################

              pushd "${repo.name}/${core.path}" || exit \$?
              python rmake.py infer --target ${core_target} || exit \$?
              popd

              ######################################################################
              # Package
              ######################################################################

              # Clean up prior build zip artifacts
              rm *.zip
              pushd "${repo.name}/${core.path}/sdcard" || exit \$?

              # TODO: Determine API version
              VERSION=`git describe --tags --always --long`
              DATE=`date -u '+%Y%m%d_%H%M'`
              RELEASE_ZIP="${core.name}_${core_target}_\${DATE}_\${VERSION}.zip"

              # NOTE: Do not change the RELEASE_ZIP_NAME: tag. It is parsed during promotion process.
              echo "RELEASE_ZIP_NAME: \${RELEASE_ZIP}"

              zip "\${RELEASE_ZIP}" *
              popd
              mv "${repo.name}/${core.path}/sdcard/\${RELEASE_ZIP}" .

              exit \$?
              """.stripIndent())
      }
      publishers {
        archiveArtifacts {
          pattern("*.zip")
          onlyIfSuccessful()
        }
        fingerprint {
          targets("*.zip,${repo.name}/${core.path}/sdcard/**")
        }
        slackNotifier {
          startNotification(false)
          notifyAborted(false)
          notifyBackToNormal(true)
          notifyEveryFailure(true)
          notifyFailure(true)
          notifyNotBuilt(true)
          notifyRegression(true)
          notifyRepeatedFailure(true)
          notifySuccess(true)
          notifyUnstable(true)
          commitInfoChoice('NONE')
          includeCustomMessage(false)
        }
      }
      wrappers {
        configure { node ->
          node / 'buildWrappers' / 'ruby-proxy-object' / 'ruby-object'(['ruby-class': 'Jenkins::Tasks::BuildWrapperProxy', 'pluginid': 'pyenv']) {
            'object'(['ruby-class': 'PyenvWrapper', 'pluginid': 'pyenv']) {
              'pyenv_repository'(['ruby-class': 'String', 'pluginid': 'pyenv'], 'https://github.com/yyuu/pyenv.git')
              'version'(['ruby-class': 'String', 'pluginid': 'pyenv'], '3.6.5')
              'pyenv__revision'(['ruby-class': 'String', 'pluginid': 'pyenv'], 'master')
              'pyenv__root'(['ruby-class': 'String', 'pluginid': 'pyenv'], '$HOME/.pyenv')
              'ignore__local__version'(['ruby-class': 'FalseClass', 'pluginid': 'pyenv'])
              'pip__list'(['ruby-class': 'String', 'pluginid': 'pyenv'], 'tox')
            }
            'pluginid'([pluginid: 'pyenv', 'ruby-class': 'String'], 'pyenv')
          }
        }
      }
    }

    // If new job created rather than updated/removed, trigger build
    if (queueNewJobs && !jenkins.model.Jenkins.instance.getItemByFullName(job_name)) {
      queue(job_name)
    }
  }
}
