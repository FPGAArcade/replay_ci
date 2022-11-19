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

def repoDefaults = ['disabled': false, 'branch': 'master', 'testing': false, 'production': false]

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
  folder('seed_jobs_pipeline')

  String seed_script = readFileFromWorkspace('jobs/seed_core_targets.groovy')

  repos.each { repo_overrides ->
    def repo = repoDefaults + repo_overrides

    if (repo.disabled)
      out.println("Skipping disabled repo: ${repo.name} (${repo.url}")

    if ( (isProduction && !repo.production) || (!isProduction && !repo.testing) || repo.disabled)
      return

    String job_name = "seed_jobs_pipeline/${repo.owner}-${repo.name}-seeder"

    createSeedJob(job_name, repo, seed_script, isProduction)

    queue(job_name)
  }
}

def createSeedJob(jobName, repo, seedScript, isProduction) {
  pipelineJob(jobName) {
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

    definition {
      cps {
        script(seedScript)
        sandbox()
      }
    }

    properties {
      pipelineTriggers {
        triggers {
          if (isProduction)
            githubPush()
          else {
            pollSCM {
              scmpoll_spec('*/2 * * * *')
            }
          }
        }
      }
    }

    // orphanedItemStrategy {
    //   // Trims dead items by the number of days or the number of items.
    //   discardOldItems {}
    //   defaultOrphanedItemStrategy {}
    // }

    logRotator {
      numToKeep(20)
    }
  }

}
