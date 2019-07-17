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
// TODO: Remove owner/name and add projectUrl and base folder name?
//       Once loading from json, sanity check that no repos use same base name.
// TODO: seed jobs assume "master" branch is used for development which is true of 
//       replay_ repos but will want to be configurable to account for other 
//       repos using develop
def repos = [

  // Takasa
  [
    owner: 'Takasa',
    name: 'replay_common',
    url: 'git@github.com:Takasa/replay_common.git',
    credentialId: 'takasa_replay_common'
  ],
  [
    owner: 'Takasa',
    name: 'replay_console',
    url: 'git@github.com:Takasa/replay_console.git',
    credentialId: 'takasa_replay_console'
  ],
  [
    owner: 'Takasa',
    name: 'replay_arcade',
    url: 'git@github.com:Takasa/replay_arcade.git',
    credentialId: 'takasa_replay_arcade'
  ],
  [
    owner: 'Takasa',
    name: 'replay_computer',
    url: 'git@github.com:Takasa/replay_computer.git',
    credentialId: 'takasa_replay_computer'
  ],

  // Sector14
  [
    owner: 'Sector14',
    name: 'replay_common',
    url: 'git@github.com:Sector14/replay_common.git',
    credentialId: 'Sector14_replay_common'
  ],

  [
    owner: 'Sector14',
    name: 'replay_arcade',
    url: 'git@github.com:Sector14/replay_arcade.git',
    credentialId: 'Sector14_replay_arcade'
  ],

  // TODO: Add support for single core repo or rearrange repo to support sub dir
  // [
  //   owner: 'Sector14',
  //   name: 'acorn_electron',
  //   url: 'https://github.com/Sector14/acorn-electron-core.git'
  // ],
]

folder('seed_jobs')

String seed_script = readFileFromWorkspace('seed_job.groovy')

repos.each { repo ->
  String job_name = "seed_jobs/${repo.owner}-${repo.name}-seeder"

  job(job_name) {
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
        scriptText(seed_script)
        removedJobAction('DELETE')
        removedViewAction('DELETE')
        removedConfigFilesAction('DELETE')
      }
    }
    logRotator {
      numToKeep(20)
    }
  }

  queue(job_name)
}
