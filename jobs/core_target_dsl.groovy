// Build pipeline for a specific core target
// Additional Parameters:-
//   param_job_folder: Name of root folder to create job in e.g "FPGAArcade-replay_common"
//   param_core_name: Name of core to build e.g "PacMan"
//   param_target_name: Target platform to build for e.g "R1"
import groovy.json.JsonOutput

String job_folder = "${param_repo.owner}-${param_repo.name}"
folder("${job_folder}")

folder("${job_folder}/${param_core.name}")

pipelineJob("${job_folder}/${param_core.name}/${param_core_target}") {
  description("Auto-created build job for ${param_core.name} [${param_core_target}]")

  environmentVariables {
    // REVIEW: is this already available?
    env('job_folder', job_folder)

    env('core_name', param_core.name)
    env('core_path', param_core.path)
    env('core_target', param_core_target)

    env('repo_owner', param_repo.owner)
    env('repo_name', param_repo.name)
    env('repo_credential_id', param_repo.credentialId)
    env('repo_url', param_repo.url)
    env('repo_branch', param_repo.branch)
    env('repo_source_includes', param_source_includes[param_repo.name] ? param_source_includes[param_repo.name].join(';') : '')

    env('repo_replay_ci_name', param_repo_replay_ci.name)
    env('repo_replay_ci_url', param_repo_replay_ci.url)
    env('repo_replay_ci_branch', param_repo_replay_ci.branch)

    env('repo_replay_common_owner', param_repo_replay_common.owner)
    env('repo_replay_common_name', param_repo_replay_common.name)
    env('repo_replay_common_credential_id', param_repo_replay_common.credentialId)
    env('repo_replay_common_url', param_repo_replay_common.url)
    env('repo_replay_common_branch', param_repo_replay_common.branch)
    env('repo_replay_common_source_includes', param_source_includes[param_repo_replay_common.name] ? param_source_includes[param_repo_replay_common.name].join(';') : '')

    env('repo_psfpga_owner', param_repo_psfpga.owner)
    env('repo_psfpga_name', param_repo_psfpga.name)
    env('repo_psfpga_credential_id', param_repo_psfpga.credentialId)
    env('repo_psfpga_url', param_repo_psfpga.url)
    env('repo_psfpga_branch', param_repo_psfpga.branch)
    env('repo_psfpga_source_includes', param_source_includes[param_repo_psfpga.name] ? param_source_includes[param_repo_psfpga.name].join(';') : '')
  }

  definition {
    cps {
      script(readFileFromWorkspace('replay_ci/jobs/core_target.groovy'))
      sandbox()
    }
  }

  properties {
    githubProjectUrl("https://github.com/${param_repo.owner}/${param_repo.name}/")

    pipelineTriggers {
      triggers {
        if (param_config.isProduction)
          githubPush()
        // else {
          // pollSCM {
          //   scmpoll_spec('*/2 * * * *')
          // }
        // }
      }
    }
  }

  logRotator {
    numToKeep(20)
  }
}