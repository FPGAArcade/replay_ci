// Automated Core/Target job creation/removal
//
// Note: Assumes private repo usage currently and that credentials for access
// have been configured using "<repo_owner>_<repo_name>" as the ID format
// All seeded repos are assumed to require a dependancy on replay_common and
// only replay_common.
//
// Params:
//   String repo_owner - github repo owner e.g takasa
//   String repo_name  - github repo name e.g replay_common

// -----------------------------------------------------------------------------
// Classes
// -----------------------------------------------------------------------------

class Core {
  String name
  String path
  String[] targets
}

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

def createJob(repo_owner, repo_name, name, path, target) {
  folder("${repo_owner}-${repo_name}/${name}")

  String jobName = "${repo_owner}-${repo_name}/${name}/${target}"

  job(jobName) {
    description("Autocreated build job for ${jobName}")
    properties {
      githubProjectUrl("https://github.com/${repo_owner}/${repo_name}")
    }
    multiscm {
      // Jenkins is not able to determine other repo build deps currently.
      // We assume only replay_common exists as a dep and that every job
      // except for replay_common itself, depends on it.
      if (repo_name != "replay_common") {
        git {
          remote {
            url("git@github.com:${repo_owner}/replay_common.git")
            credentials("${repo_owner}_replay_common")
          }
          extensions {
            relativeTargetDirectory('replay_common')
            // TODO: Trigger build only if framework/lib changes?
          }
          branch('master')
        }
      }
      git {
        remote {
          url("git@github.com:${repo_owner}/${repo_name}.git")
          credentials("${repo_owner}_${repo_name}")
        }
        extensions {
          relativeTargetDirectory(repo_name)
          pathRestriction {
            includedRegions("${path}/.*")
            excludedRegions('')
          }
        }
        branch('master')
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

            cd ${repo_name}/${path}
            python rmake.py infer --target ${target}
            exit \$?
            """.stripIndent())
    }
    publishers {
      archiveArtifacts {
        pattern("${repo_name}/${path}/sdcard/**")
        onlyIfSuccessful()
      }
      // slackNotifier {
      //   startNotification(true)
      //   notifyAborted(true)
      //   notifyBackToNormal(true)
      //   notifyEveryFailure(true)
      //   notifyFailure(true)
      //   notifyNotBuilt(true)
      //   notifyRegression(true)
      //   notifyRepeatedFailure(true)
      //   notifySuccess(true)
      //   notifyUnstable(true)
      // }
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
}

// -----------------------------------------------------------------------------
// Main
// -----------------------------------------------------------------------------
def cores = []

def coresFile = readFileFromWorkspace('_cores.txt')

// Extract core and supported targets
coresFile.eachLine {
  def matcher = it =~ /(?<targets>(?:\[\w+\])+)\s+(?<path>\S*)/

  if (matcher.matches()) {
    def path = matcher.group('path')
    def name = path.replaceAll('/','_')
    def targets = []
    matcher.group('targets').findAll(/\[(\w+?)\]/) {
      target -> targets << target[1]
    }
    cores << new Core(name: name, path: path, targets: targets)
  }
  else
    out.println("Match failure for _core.txt line: ${it}")
}

// Base folder should always exist
folder("${repo_owner}-${repo_name}")

cores.each { core ->
  core.targets.each { target ->
    createJob(repo_owner, repo_name, core.name, core.path, target)
  }
}

