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

class Config {
  Boolean isProduction
  String workspacePath
  String releasePath
  String releaseAPIURL
}

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
def envmap = new HashMap()
def binding = getBinding()
envmap.putAll(binding.getVariables())

def config = new Config(isProduction: envmap['PRODUCTION_SERVER'] ? envmap['PRODUCTION_SERVER'].toBoolean() : false,
                        workspacePath: envmap['WORKSPACE'],
                        releasePath: envmap['RELEASE_PATH'] ? envmap['RELEASE_PATH'] : null,
                        releaseAPIURL: envmap['RELEASE_API_URL'] ? envmap['RELEASE_API_URL'] : null)

out.println("Running on " + (config.isProduction ? "PRODUCTION" : "TEST") + " server.")
out.println("Config: ")
out.println(config.dump())
if (!config.releasePath)
  throw new Exception("Required ENV variable RELEASE_PATH not found.")
if (!config.releaseAPIURL)
  throw new Exception("Required ENV variable RELEASE_API_URL not found.")

// Wrap Params
Repo repo = new Repo(owner: param_repo_owner, name: param_repo_name,
                     credentialId: param_repo_credential_id, url: param_repo_url,
                     branch: param_repo_branch)

// Process repo cores
parseCoresFile(repo.name+'/_cores.txt').each { core ->

  // Create separate build job for all supported targets of the core
  core.targets.each { core_target ->
    out.println("Configuring build job for:-")
    out.println("  Repo   : ${repo.name}")
    out.println("  Core   : ${core.name}")
    out.println("  Target : ${core_target}")
    out.println("  CWD    : ${config.workspacePath}")

    String build_path = "${repo.name}/${core.path}/build_${core_target}"

    generateBuildMeta(repo, core, core_target, config)
    ArrayList source_files = parseBuildMetaPaths("${build_path}/build.srcs.meta", config)
    ArrayList dep_paths = parseBuildMetaPaths("${build_path}/build.deps.meta", config)

    // Split source files by repo
    Map source_includes = [:]
    source_files.each { item ->
      String[] paths = item.split('/', 2)
      String repo_path = paths[0]
      String source_path = paths[1]

      if (source_includes.get(repo_path) == null)
        source_includes.put(repo_path, [])

      source_includes[repo_path].add(source_path)
    }

    // Also trigger rebuild on _deps.txt/_srcs.txt files in any dep directories
    dep_paths.each { item ->
      String[] paths = item.split('/', 2)
      String repo_path = paths[0]
      String dep_path = paths[1]

      if (source_includes.get(repo_path) == null)
        source_includes.put(repo_path, [])

      source_includes[repo_path].add(dep_path+"/_deps.txt")
      source_includes[repo_path].add(dep_path+"/_srcs.txt")
      source_includes[repo_path].add(dep_path+"/.*/_deps.txt")
      source_includes[repo_path].add(dep_path+"/.*/_srcs.txt")
    }

    String job_name = createCoreTargetJob(repo, core, core_target,
                                          source_includes, config)

    // If new job created rather than updated/removed, trigger build
    // NOTE: In the case of job update, it shouldn't matter if existing
    //       build job triggers before or after seed job when srcs/deps change.
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
      throw new Exception("Unable to determine core name for path: ${path}")
      return
    }

    def targets = []
    matcher.group('targets').findAll(/\[(\w+?)\]/) {
      target -> targets << target[1]
    }

    // Fail job if duplicate core names detected
    if (unique_names.contains(name)) {
      throw new Exception("Duplicate core name '${name}'. Aborting.")
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

def generateBuildMeta(repo, core, core_target, config) {
  // TODO: Remove duplication of paths and need to hard code in the seed job
  //       both here and in the created job shell script.
  File local_settings = new File("${config.workspacePath}/replay_common/scripts/local_settings.py")

  local_settings.write("""\
  # Local paths auto generated via jenkins project build script
  ISE_PATH = '/opt/Xilinx/14.7/ISE_DS/ISE/'
  ISE_BIN_PATH = '/opt/Xilinx/14.7/ISE_DS/ISE/bin/lin64/'

  MODELSIM_PATH = ''
  QUARTUS_PATH = '/opt/intelFPGA_lite/20.1/quartus/bin/'

  # if UNISIM_PATH is empty, a local (tb/sim) library will be created
  UNISIM_PATH = None
  """.stripIndent())

  def working_dir = new File("${config.workspacePath}/${repo.name}/${core.path}")

  def p = "python rmake.py infer --target ${core_target} --seed".execute([], working_dir)

  // TODO: timeout
  def sbStd = new StringBuffer()
  def sbErr = new StringBuffer()
  p.waitForProcessOutput(sbStd, sbErr)

  if (p.exitValue() != 0) {
    out.println(sbStd)
    out.println(sbErr)
    throw new Exception("Error generating build meta for core '${core.name}' target '${core_target}'.")
  }
}

// return ArrayList of paths relative to work space directory.
def parseBuildMetaPaths(meta_filename, config) {
  String meta = readFileFromWorkspace(meta_filename)

  def trim_count = config.workspacePath.length()+1

  // Change paths to relative to workspace root (+1 to remove leading slash)
  ArrayList meta_relative = []
  meta.eachLine { line ->
    meta_relative.add(line.substring(trim_count))
  }

  return meta_relative
}

def createCoreTargetJob(repo, core, core_target, source_includes, config) {
  String job_folder = "${repo.owner}-${repo.name}"
  folder(job_folder)

  folder("${job_folder}/${core.name}")

  String job_name = "${job_folder}/${core.name}/${core_target}"

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
              string('releaseapikey', 'release-api-key')
              string('discordreleasewebhook', 'discord-release-notification-webhook')
            }
          }
          actions {
            // DEPRECATED: Will be removed once upload API considered stable
            copyArtifacts("\${PROMOTED_JOB_NAME}") {
              buildSelector {
                  buildNumber("\${PROMOTED_NUMBER}")
                }
              includePatterns("*.zip")
              targetDirectory("/home/jenkins/www/releases/cores/${core_target}/${core.name}/")
            }
            // TODO: Move to separate script with args or env vars
            // TODO: Move release notification handling into release API as event based on new build post.
            shell("""\
                  #!/bin/bash
                  hash curl 2>/dev/null || { echo >&2 "curl (curl) required but not found.  Aborting."; exit 1; }
                  hash xmllint 2>/dev/null || { echo >&2 "xmllint (libxml2-utils) required but not found.  Aborting."; exit 1; }

                  BUILD_DIR="\${JENKINS_HOME}/jobs/${job_folder}/jobs/${core.name}/jobs/${core_target}/builds/\${PROMOTED_NUMBER}"
                  BUILD_DATE=`xmllint --nowarning --xpath "/build/timestamp/text()" \${BUILD_DIR}/build.xml`
                  RELEASE_ZIP=`ls "\${BUILD_DIR}/archive/${core.name}_${core_target}_"*.zip`
                  RELEASE_ZIP_NAME=`basename \${RELEASE_ZIP}`

                  # DEPRECATED: Will be removed once Jenkins migrated to docker and new api upload considered stable.
                  # Update "latest" sym link
                  RELEASE_DIR="/home/jenkins/www/releases/cores/${core_target}/${core.name}"
                  ln -sf "\${RELEASE_DIR}/\${RELEASE_ZIP_NAME}" "\${RELEASE_DIR}/latest"

                  echo "Promoting build \${PROMOTED_NUMBER} to stable release: \${RELEASE_ZIP}"
                  echo \${BUILD_DATE}

                  # Upload to release api
                  status=`curl --silent --output /dev/stderr -w "%{http_code}" --request POST \
                              --header "Authorization: APIKey \${releaseapikey}" \
                              --form "buildinfo={
                                          \\"platformId\\": \\"${core_target}\\",
                                          \\"coreId\\": \\"${core.name}\\",
                                          \\"buildType\\": \\"stable\\",
                                          \\"buildDate\\": \${BUILD_DATE}
                                        };type=application/json" \
                              --form "zipfile=@\\"\${RELEASE_ZIP}\\";type=application/zip" \
                              \${RELEASE_API_URL}builds/`
                  if [ "\${status}" -lt 200 ] || [ "\${status}" -ge 300 ]; then
                    echo >&2 "API upload failed. Aborting."
                    exit 1
                  fi

                  # Notify discord
                  read -d '' DISCORD_MESSAGE <<EOF
                  {
                    "content": "A new core stable release is available.",
                    "embeds": [
                      {
                        "title": "${core.name} (${core_target})",
                        "url": "https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/\${RELEASE_ZIP_NAME}|\${RELEASE_ZIP_NAME}",
                        "color": null,
                        "fields": [
                          {
                            "name": "Download",
                            "value": "[\${RELEASE_ZIP_NAME}](https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/\${RELEASE_ZIP_NAME})"
                          },
                          {
                            "name": "Previous Releases",
                            "value": "https://build.fpgaarcade.com/releases/cores/${core_target}/${core.name}/"
                          }
                        ]
                      }
                    ]
                  }
                  EOF

                  curl -X POST --header "Content-Type: application/json" --data "\${DISCORD_MESSAGE}" \${discordreleasewebhook}

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
          if (config.isProduction) {
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
            includedRegions(source_includes['replay_common'].join('\n'))
            excludedRegions('')
          }
        }
        branch('master')
      }

      // HACK: The "psx" core in replay_console requires a 3rd repo.
      // This is not yet supported thus this hack to hard code an extra
      // repo until the seed job is upgraded to read a jenkins configuration file
      // from the core dir and allow arbitrary extra repos.
      if (repo.name == "replay_console" && core.name == "psx") {
        git {
          remote {
            if (config.isProduction) {
              url("git@github.com:Takasa/ps-fpga")
              credentials("takasa_ps-fpga")
            } else {
              url("git@github.com:Sector14/ps-fpga")
              credentials("sector14_ps-fpga")
            }
          }
          extensions {
            relativeTargetDirectory('ps-fpga')
            pathRestriction {
              includedRegions(source_includes['ps-fpga'].join('\n'))
              excludedRegions('')
            }
          }
          branch('main')
        }
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
              includedRegions(source_includes[repo.name].join('\n'))
              excludedRegions('')
            }
          }
          branch(repo.branch)
        }
      }
    }
    triggers {
      if (config.isProduction)
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
            QUARTUS_PATH = '/opt/intelFPGA_lite/20.1/quartus/bin/'

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

            echo "RELEASE_ZIP_NAME: \${RELEASE_ZIP}"

            zip -r "\${RELEASE_ZIP}" *
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
    }
    wrappers {
      credentialsBinding {
        string('discordbuildwebhookurl', 'discord-build-notification-webhookurl')
      }
    }
    configure { project ->
      project / publishers << 'nz.co.jammehcow.jenkinsdiscord.WebhookPublisher' {
        webhookURL '${discordbuildwebhookurl}'
        //branchName '${GIT_BRANCH}'
        statusTitle "${job_name} #\${BUILD_NUMBER}"
        //notes 'some notes here'
        //thumbnailURL 'https://example.com/thumbnail.jpg'
        sendOnStateChange false
        enableUrlLinking true
        enableArtifactList true
        enableFooterInfo false
        showChangeset false
        sendLogFile false
        sendStartNotification false
      }
    }
  }

}
