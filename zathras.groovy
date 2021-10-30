// Zathras is responsible for maintaining the great machine.
// Manages creation and upkeep of seed jobs for each monitored repo.
//
// Setup as a free style job with webhook trigger on replay_ci
// Note: CI only supports repos on github currently.
// Note2: Credentials and git url case must match the github case as the webhook
//        is case sensitive.
import groovy.json.JsonSlurper

// TODO: Remove owner/name and add projectUrl and base folder name?
//       Once loading from json, sanity check that no repos use same base name.
// TODO: Support none monolythic repos without requiring subdir + _cores.txt

// -----------------------------------------------------------------------------
// Globals
// -----------------------------------------------------------------------------

def configuration = new HashMap()
def binding = getBinding()
configuration.putAll(binding.getVariables())

Boolean isProduction = configuration['PRODUCTION_SERVER'] ? configuration['PRODUCTION_SERVER'].toBoolean() : false

def repoDefaults = ['disabled': false, 'branch': 'master']

// -----------------------------------------------------------------------------
// Main
// -----------------------------------------------------------------------------
out.println("Running on " + (isProduction ? "PRODUCTION" : "TEST") + " server.")

def jsonSlurper = new JsonSlurper()
def repoList = jsonSlurper.parseText(readFileFromWorkspace('repos.json'))

generateSeedJobs(repoList, repoDefaults, isProduction)

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

def generateSeedJobs(repos, repoDefaults, isProduction) {
  folder('seed_jobs')

  String seed_script = readFileFromWorkspace('seed_job.groovy')

  repos.each { repo_overrides ->
    def repo = repoDefaults + repo_overrides

    if (repo.disabled)
      out.println("Skipping disabled repo: ${repo.name}")

    if ( (repo.testingOnly && isProduction) || repo.disabled)
      return

    String job_name = "seed_jobs/${repo.owner}-${repo.name}-seeder"

    createSeedJob(job_name, repo, seed_script, isProduction)

    queue(job_name)
  }
}

def createSeedJob(jobName, repo, seedScript, isProduction) {

  // Seed job for a repo needs to trigger if _cores.txt changes in order to
  // create/remove jobs to handle new core and/or platform targets. In addition
  // dependancy changes (_deps.txt and srcs.txt) must trigger a re-gen as a core
  // may gain/lose a dependancy on another core. In that case, the core should
  // rebuild anytime the core it's dependant on changes.
  String seed_repo_includes = """\
                                _cores.txt
                                .*/_deps.txt
                                .*/_srcs.txt
                              """.stripIndent()
  String replay_common_includes = """\
                                    .*/_deps.txt
                                    .*/_srcs.txt
                                  """.stripIndent()

  job(jobName) {
    description("Seed job for ${repo.url}.")
    parameters {
      // REVIEW: Don't really want these as ui editable params, better option?
      stringParam('param_repo_owner', repo.owner, 'Do NOT modify')
      stringParam('param_repo_name', repo.name, 'Do NOT modify')
      credentialsParam('param_repo_credential_id') {
        description('Do NOT modify')
        defaultValue(repo.credentialId)
      }
      stringParam('param_repo_url', repo.url, 'Do Not modify')
      stringParam('param_repo_branch', repo.branch, 'Do Not modify')
    }
    multiscm {
      // replay_common is required by all cores for build dependancy generation
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

      // HACK: The "psx" core in replay_console requires a 3rd repo.
      // This is not yet supported thus this hack to hard code an extra
      // repo until the seed job is upgraded to read a jenkins configuration file
      // from the core dir and allow arbitrary extra repos.
      if (repo.name == "replay_console") {
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
          branch('master')
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
              includedRegions(seed_repo_includes)
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
      jobDsl {
        scriptText(seedScript)
        removedJobAction('DELETE')
        removedViewAction('DELETE')
        removedConfigFilesAction('DELETE')
      }
    }
    logRotator {
      numToKeep(20)
    }
  }

}