// Automated Core/Target job creation/removal
//
// Note: Assumes private repo usage currently and that credentials for access
// have been configured using "<owner>_<repo_name>" as the ID format
//
// https://github.com/jenkinsci/job-dsl-plugin/wiki/Job-DSL-Commands#logging
//
// -----------------------------------------------------------------------------
// Global Consts/Classes
// -----------------------------------------------------------------------------
def owner = 'takasa'
def repo = 'replay_common'

class Core {
  String name
  String path
  String[] targets
}

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

def createJob(owner, repo, name, path, target) {
  folder("${owner}/${name}-${target}")

  // Path must end with unchanged repo name, otherwise hardcoded usage
  // of ../../../replay_common by the build system will fail.
  String jobName = "${owner}/${name}-${target}/${repo}"

  job(jobName) {
    description("Autocreated build job for ${jobName}")
    properties {
      githubProjectUrl("https://github.com/${owner}/${repo}")
    }
    scm {
      git {
        remote {
          // TODO: name() does not appear to work?
          url("git@github.com:${owner}/${repo}.git")
          credentials("${owner}_${repo}")
        }
        branch('master')
      }
    }
    triggers {
      gitHubPushTrigger()
    }
    steps {
      // TODO: Handle multi-scm for replay_common local settings
      shell("""\
            python --version

            # Create local settings if this is a replay_common repo
            [ -d "scripts/" ] && cat << EOF > scripts/local_settings.py
            # Local paths auto generated via jenkins project build script
            ISE_PATH = '/opt/Xilinx/14.7/ISE_DS/ISE/'
            ISE_BIN_PATH = '/opt/Xilinx/14.7/ISE_DS/ISE/bin/lin64/'

            MODELSIM_PATH = ''
            QUARTUS_PATH = '/opt/intelFPGA_lite/18.1/quartus/bin/'

            # if UNISIM_PATH is empty, a local (tb/sim) library will be created
            UNISIM_PATH = None

            EOF

            cd ${path}
            python rmake.py infer --target ${target}
            exit \$?
            """.stripIndent())
    }
    publishers {
      archiveArtifacts {
        pattern("${path}/sdcard/**")
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

// TODO: Have a seed job repo that checks out and sets up jobs for
//       several listed repos rather than requiring this script be run against each repo?
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
folder(owner)

cores.each { core ->
  core.targets.each { target ->
    createJob(owner, repo, core.name, core.path, target)
  }
}

