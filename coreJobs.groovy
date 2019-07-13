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
def owner = 'sector14'
def repo = 'replay_console'

class Core {
  String name
  String[] targets
}

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

def createJob(owner, repo, name, target) {
  folder("${owner}-${repo}/${name}")

  def jobName = "${owner}-${repo}/${name}/${target}"

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
      shell("""\
            python --version

            python ${name}/rmake.py flow infer --target ${target}
            exit \$?
            """.stripIndent())
    }
    publishers {
      archiveArtifacts {
        pattern("${name}/sdcard/**")
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
  def matcher = it =~ /(?<targets>(?:\[\w+\])+)\s*(?<name>\w+)/

  if (matcher.matches()) {
    def name = matcher.group('name')
    def targets = []
    matcher.group('targets').findAll(/\[(\w+?)\]/) {
      target -> targets << target[1]
    }
    cores << new Core(name: name, targets: targets)
  }
  else
    out.println("Match failure for _core.txt line: ${it}")
}

// Base repo folder should always exist
folder("${owner}-${repo}")

cores.each { core ->
  core.targets.each { target ->
    createJob(owner, repo, core.name, target)
  }
}

