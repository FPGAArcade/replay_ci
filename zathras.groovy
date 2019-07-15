// Zathras is responsible for maintaining the great machine.
// Manages creation and upkeep of seed jobs for each monitored repo.
//
// Setup as a free style job with webhook trigger on replay_ci
// Note: CI only supports repos on github currently.
//       Assumptions are made that credentials exist with ${owner}_${repo} format
// Note2: Credentials and git url case must match the github case as the webhook
//        is case sensitive.

// TODO: read from a workspace repos.json to avoid script reauth on changes.
//       Include credentials ID here when required
def repos = [
  [owner: 'takasa', name: 'replay_common', url: 'git@github.com:Takasa/replay_common.git'],
  [owner: 'takasa', name: 'replay_console', url: 'git@github.com:Takasa/replay_console.git'],

  [owner: 'Sector14', name: 'replay_common', url: 'git@github.com:Sector14/replay_common.git'],
]

folder('seed_jobs')

String seed_script = readFileFromWorkspace('seed_job.groovy')

repos.each { repo ->

  String job_name = "seed_jobs/${repo.owner}-${repo.name}-seeder"

  job(job_name) {
    description("Seed job for ${repo.owner}/${repo.name}.")
    parameters {
      // TODO: Don't really want these as ui editable params, just using this
      //       to pass owner and repo through to the child seed job. Better option?
      stringParam('repo_owner', repo.owner, 'Do NOT modify')
      stringParam('repo_name', repo.name, 'Do NOT modify')
    }
    // TODO: Switch to multiscm with replay_ci as extra repo
    scm {
      git {
        remote {
          url("git@github.com:${repo.owner}/${repo.name}.git")
          credentials("${repo.owner}_${repo.name}")
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
        scriptText(seed_script)
        removedJobAction('DELETE')
        removedViewAction('DELETE')
        removedConfigFilesAction('DELETE')
        failOnSeedCollision(true)
      }
    }
    logRotator {
      numToKeep(20)
    }
  }

  // If new job created rather than updated/removed, trigger build
  if (!jenkins.model.Jenkins.instance.getItemByFullName(job_name)) {
    queue(job_name)
  }
}
