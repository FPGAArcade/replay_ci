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
// TODO: seed jobs assume "master" branch is used for development which is true of
//       replay_ repos but will want to be configurable to account for other
//       repos using develop
// TODO: Support none monolythic repos without requiring subdir + _cores.txt

// -----------------------------------------------------------------------------
// Globals
// -----------------------------------------------------------------------------

def configuration = new HashMap()
def binding = getBinding()
configuration.putAll(binding.getVariables())

Boolean isProduction = configuration['PRODUCTION_SERVER'] ? configuration['PRODUCTION_SERVER'].toBoolean() : false

// -----------------------------------------------------------------------------
// Main
// -----------------------------------------------------------------------------
out.println("Running on " + (isProduction ? "PRODUCTION" : "TEST") + " server.")

def jsonSlurper = new JsonSlurper()
def repo_list = jsonSlurper.parseText(readFileFromWorkspace('repos.json'))

generateSeedJobs(repo_list, isProduction)

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

def generateSeedJobs(repos, isProduction) {
  folder('seed_jobs')

  String seed_script = readFileFromWorkspace('seed_job.groovy')

  repos.each { repo ->
    if (repo.disabled)
      out.println("Repo is disabled ${repo.name}")

    if ( (repo.testingOnly && isProduction) || repo.disabled)
      return

    String job_name = "seed_jobs/${repo.owner}-${repo.name}-seeder"

    createSeedJob(job_name, repo, seed_script)

    queue(job_name)
  }
}

def createSeedJob(jobName, repo, seedScript) {

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
    }
    // TODO: Switch to multiscm with replay_ci as extra repo
    scm {
      git {
        remote {
          url(repo.url)
          credentials(repo.credentialId)
        }
        branch('master')
        extensions {
          pathRestriction {
            includedRegions('_cores.txt')
            excludedRegions('')
          }
        }
      }
    }
    triggers {
      gitHubPushTrigger()
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