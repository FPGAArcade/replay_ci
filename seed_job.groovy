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
}

class Core {
  String name
  String path
  String[] targets
}

// -----------------------------------------------------------------------------
// Globals
// -----------------------------------------------------------------------------

Repo repo_info = new Repo(owner: param_repo_owner, name: param_repo_name,
                          credentialId: param_repo_credential_id, url: param_repo_url)

def configuration = new HashMap()

// -----------------------------------------------------------------------------
// Main
// -----------------------------------------------------------------------------

// Env variables
def binding = getBinding()
configuration.putAll(binding.getVariables())

Boolean isProduction = configuration['PRODUCTION_SERVER'] ? configuration['PRODUCTION_SERVER'].toBoolean() : false

out.println("Running on " + isProduction ? "PRODUCTION" : "TEST" + " server.")

def cores = []

def cores_file = readFileFromWorkspace('_cores.txt')
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
    error "Duplicate core name '${name}' in repo ${repo_info.url}. Aborting."
  }
  unique_names.add(name)

  cores << new Core(name: name, path: path, targets: targets)
}

cores.each { core ->
  createCoreJobs(repo_info, core, true, isProduction)
}

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

// Use last directory of given path as core name
def coreNameFromPath(path) {
  def matcher = path =~ /.*?\/*(\w+)\s*$/

  return matcher.matches() ? matcher.group(1) : null
}

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
    if (repo.name == "replay_common") {
      replay_common_includes = replay_common_includes.concat("${core.path}/.*")
    }

    job(job_name) {
      description("Autocreated build job for ${job_name}")
      properties {
        githubProjectUrl("https://github.com/${repo.owner}/${repo.name}")
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
                includedRegions("${core.path}/.*")
                excludedRegions('')
              }
            }
            branch('master')
          }
        }
      }
      triggers {
        gitHubPushTrigger()
      }
      steps {
        shell("""\
              python --version

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

              cd ${repo.name}/${core.path}
              python rmake.py infer --target ${core_target}
              exit \$?
              """.stripIndent())
      }
      publishers {
        archiveArtifacts {
          pattern("${repo.name}/${core.path}/sdcard/**")
          onlyIfSuccessful()
        }
        slackNotifier {
          startNotification(true)
          notifyAborted(true)
          notifyBackToNormal(true)
          notifyEveryFailure(true)
          notifyFailure(true)
          notifyNotBuilt(true)
          notifyRegression(true)
          notifyRepeatedFailure(true)
          notifySuccess(true)
          notifyUnstable(true)
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
