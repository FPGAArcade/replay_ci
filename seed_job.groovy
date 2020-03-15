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

// TODO: Store required settings/consts in a global config
// Wrap environment variables
def configuration = new HashMap()
def binding = getBinding()
configuration.putAll(binding.getVariables())

Boolean isProduction = configuration['PRODUCTION_SERVER'] ? configuration['PRODUCTION_SERVER'].toBoolean() : false
String workspace = configuration['WORKSPACE']

out.println("Running on " + (isProduction ? "PRODUCTION" : "TEST") + " server.")

// Wrap Params
Repo repo = new Repo(owner: param_repo_owner, name: param_repo_name,
                     credentialId: param_repo_credential_id, url: param_repo_url,
                     branch: param_repo_branch)

// Process repo cores
parseCoresFile(repo.name+'/_cores.txt').each { core ->

  // Create separate build job for all supported targets of the core
  core.targets.each { core_target ->
    out.println("Querying for core sources")
    out.println("  Repo   : ${repo.name}")
    out.println("  Core   : ${core.name}")
    out.println("  Target : ${core_target}")
    out.println("  CWD    : ${workspace}")

    ArrayList source_files = coreSourceFiles(repo, core, core_target, workspace)

    // Split source files by repo
    Map source_includes = [:]
    source_files.each { item ->
      String[] paths = item.split('/', 2)

      if (source_includes.get(paths[0]) == null)
        source_includes.put(paths[0], [paths[1]])
      else
        source_includes[paths[0]].add(paths[1])
    }

    String job_name = createCoreTargetJob(repo, core, core_target,
                                          source_includes, isProduction)

    // If new job created rather than updated/removed, trigger build
    if (!jenkins.model.Jenkins.instance.getItemByFullName(job_name)) {
      queue(job_name)
    }
  }

}

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

// Return Core[] extracted from specified cores file
def parseCoresFile(coresFile) {

  def cores_file = readFileFromWorkspace(coresFile)
  out.println("Reading " + coresFile + " from workspace")
  out.println(cores_file)

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

// Runs build system and returns list of source files for core/target
// relative to workspace_path.
def coreSourceFiles(repo, core, core_target, workspace_path) {
  def sout = new StringBuilder()
  def serr = new StringBuilder()
  def working_dir = new File("${workspace_path}/${repo.name}/${core.path}")

  def p = "python rmake.py infer --target ${core_target}".execute([], working_dir)
  p.consumeProcessOutput(sout, serr)
  p.waitFor()

  // TODO: Check return code
  // TODO: Add save arg to early out build. Should build.srcs.meta go into a build dir?
  //       maybe make the build dir name be overridable?

  String sources_list = readFileFromWorkspace("${repo.name}/${core.path}/build.srcs.meta")

  // Change paths to relative to workspace root (+1 to remove leading slash)
  ArrayList sources_relative = []
  sources_list.eachLine { line ->
    sources_relative.add(line.substring(workspace_path.length()+1))
  }

  return sources_relative
}

def createCoreTargetJob(repo, core, core_target, source_includes, isProduction) {
  String job_folder = "${repo.owner}-${repo.name}"
  folder(job_folder)

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
              targetDirectory("/home/jenkins/www/releases/cores/${core_target}/${core.name}/")
            }
            // HACK: Using curl based slack messaging as slackNotifier is not available in stepContext.
            // TODO: Using build log to determine the name of the release zip artifact is hacky. See what json api holds.
            // TODO: Remove hard coded target directory for core zips here and above.
            shell("""\
                  #!/bin/bash
                  RELEASE_ZIP=`grep -a "Creating release zip" "\${JENKINS_HOME}/jobs/${job_folder}/jobs/${core.name}/jobs/${core_target}/builds/\${PROMOTED_NUMBER}/log" | cut -d " " -f4 | awk '{\$1=\$1}1'`

                  # Update "latest" sym link
                  RELEASE_DIR="/home/jenkins/www/releases/cores/${core_target}/${core.name}"
                  ln -sf "\${RELEASE_DIR}/\${RELEASE_ZIP}" "\${RELEASE_DIR}/latest"

                  read -d '' SLACK_MESSAGE <<EOF
                  New stable release of ${core.name} for the ${core_target}.
                  Download: <https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/\${RELEASE_ZIP}|\${RELEASE_ZIP}>
                  Previous Builds: <https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/>
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
          relativeTargetDirectory('')
          pathRestriction {
            includedRegions(source_includes['replay_common'].join('\n'))
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
            relativeTargetDirectory('')
            pathRestriction {
              includedRegions(source_includes[repo.name].join('\n'))
              excludedRegions('')
            }
          }
          branch(repo.branch)
        }
      }
    }
    triggers {
      if (isProduction)
        gitHubPushTrigger()
      else {
        pollSCM {
          scmpoll_spec('*/2 * * * *')
        }
      }
    }
    steps {
      shell("""\
            #!/bin/bash
            # Crude packaging script for releases
            hash zip 2>/dev/null || { echo >&2 "zip required but not found.  Aborting."; exit 1; }
            hash git 2>/dev/null || { echo >&2 "git required but not found.  Aborting."; exit 1; }
            hash python 2>/dev/null || { echo >&2 "python required but not found.  Aborting."; exit 1; }

            python_major_v=\$(python -c"import sys; print(sys.version_info.major)")
            python_minor_v=\$(python -c"import sys; print(sys.version_info.minor)")

            if [[ "\${python_major_v}" -lt "3" || ("\${python_major_v}" -eq "3" && "\${python_minor_v}" -lt "6") ]]; then
                echo "Build system requires python 3.6 or greater (\${python_major_v}.\${python_minor_v} installed)"
                exit 1
            fi

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
            python rmake.py infer --target "${core_target}" || exit \$?
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
            # NOTE: This is the old release tag. Kept for now as older build logs contain it but will
            #       be removed in the future.
            echo "Creating release zip \${RELEASE_ZIP}"


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
  }

}
