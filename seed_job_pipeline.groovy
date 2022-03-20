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

class Config {
  Boolean isProduction
  String releasePath
  String releaseAPIURL
}

class Repo {
  String owner
  String name
  String credentialId
  String url
  String branch
}

class Core {
  String name
  String path
  String[] targets
}

// -----------------------------------------------------------------------------
// Main
// -----------------------------------------------------------------------------

// Wrap environment variables
def config = new Config(isProduction: env.PRODUCTION_SERVER ? env.PRODUCTION_SERVER.toBoolean() : false,
                        releasePath: env.RELEASE_PATH,
                        releaseAPIURL: env.RELEASE_API_URL)

println("Running on " + (config.isProduction ? "PRODUCTION" : "TEST") + " server.")
println("Config: ")
println(config.dump())
if (!config.releasePath)
  throw new Exception("Required ENV variable RELEASE_PATH not found.")
if (!config.releaseAPIURL)
  throw new Exception("Required ENV variable RELEASE_API_URL not found.")

// TODO: Move all but pipeline into shared library

// -----------------------------------------------------------------------------
// Pipeline
// -----------------------------------------------------------------------------

pipeline {
    agent any

    stages {
        stage('Debug Info Step') {
            steps {
                echo "Dumping Params"
                echo "param_repo_owner: ${params.param_repo_owner}"
                echo "param_repo_name: ${params.param_repo_name}"
                echo "param_repo_credential_id: ${params.param_repo_credential_id}"
                echo "param_repo_url: ${params.param_repo_url}"
                echo "param_repo_branch: ${params.param_repo_branch}"
                echo "Production: ${PRODUCTION_SERVER}"
            }
        }
    }
}