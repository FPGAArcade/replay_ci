// Automated Core/Target job creation/removal
//
// Note: Assumes private repo usage currently and that credentials for access
// have been configured using "<param_repo_owner>_<param_repo_name>" as the ID format
// All seeded repos are assumed to require a dependancy on replay_common and
// only replay_common. Exception made for replay_console which assumes a psx
// as a temporary hack.
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
  String includedRegions
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


// ** Git path restrictions **
// Seed job for a repo needs to trigger if _cores.txt changes in order to
// create/remove jobs to handle new core and/or platform targets. In addition
// dependancy changes (_deps.txt and srcs.txt) must trigger a re-gen as a core
// may gain/lose a dependancy on another core. In that case, the core should
// rebuild anytime the core it's dependant on changes.

// TODO: Remove the param_ prefix now jenkins includes via params. object
Repo repo_seed = new Repo(owner: params.param_repo_owner,
                          name: params.param_repo_name,
                          credentialId: params.param_repo_credential_id,
                          url: params.param_repo_url,
                          branch: params.param_repo_branch,
                          includedRegions: """\
                              _cores.txt
                              .*/_deps.txt
                              .*/_srcs.txt
                            """.stripIndent())

Repo repo_replay_ci = new Repo(owner: null,
                               name: 'replay_ci',
                               credentialId: '',
                               url: config.isProduction ? 'https://github.com/FPGAArcade/replay_ci.git' : 'https://github.com/Sector14/replay_ci.git',
                               branch: config.isProduction ? 'master' : 'testing',
                               includedRegions: '')

Repo repo_common = new Repo(owner: null,
                            name: 'replay_common',
                            credentialId: config.isProduction ? 'takasa_replay_common' : 'sector14_replay_common',
                            url: config.isProduction ? 'git@github.com:Takasa/replay_common.git' : 'git@github.com:Sector14/replay_common.git',
                            branch: 'master',
                            includedRegions:
                                (repo_seed.name == 'replay_common' ? '_cores.txt\n' : '') +
                                """\
                                  .*/_deps.txt
                                  .*/_srcs.txt
                                """.stripIndent())

// TODO: Remove hardcoded 3rd repo for psx. Needs proper support for extra repos.
Repo repo_psfpga = new Repo(owner: null,
                            name: 'ps-fpga',
                            credentialId: config.isProduction ? 'takasa_ps-fpga' : 'sector14_ps-fpga',
                            url: config.isProduction ? 'git@github.com:Takasa/ps-fpga' : 'git@github.com:Sector14/ps-fpga',
                            branch: 'main',
                            includedRegions: """\
                                  .*/_deps.txt
                                  .*/_srcs.txt
                                """.stripIndent())

// -----------------------------------------------------------------------------
// Methods
// -----------------------------------------------------------------------------

def createCoreJobs(config, repo) {

  println(WORKSPACE)
  println(env.WORKSPACE)
  def cores_path = repo.name+'/_cores.txt'
  println("Reading " + cores_path + " from workspace")
  def core_lines = readFile(cores_path)
  println(core_lines)

  // Process repo cores
  parseCoresLines(core_lines).each { core ->

    // Create separate build job for all supported targets of the core
    core.targets.each { core_target ->
      println("Configuring build job for:-")
      println("  Repo   : ${repo.name}")
      println("  Core   : ${core.name}")
      println("  Target : ${core_target}")
      println("  CWD    : ${WORKSPACE}")

      String build_path = "${repo.name}/${core.path}/build_${core_target}"

      generateBuildMeta(repo, core, core_target, config)
      ArrayList source_files = parseBuildMetaPaths("${build_path}/build.srcs.meta", config)
      ArrayList dep_paths = parseBuildMetaPaths("${build_path}/build.deps.meta", config)

      // Split source files by repo
      Map source_includes = [:]
      source_files.each { item ->
        String[] paths = item.split('/', 2)
        String repo_path = paths[0]
        String source_path = paths[1]

        if (source_includes.get(repo_path) == null)
          source_includes.put(repo_path, [])

        source_includes[repo_path].add(source_path)
      }

      // Also trigger rebuild on _deps.txt/_srcs.txt files in any dep directories
      dep_paths.each { item ->
        String[] paths = item.split('/', 2)
        String repo_path = paths[0]
        String dep_path = paths[1]

        if (source_includes.get(repo_path) == null)
          source_includes.put(repo_path, [])

        source_includes[repo_path].add(dep_path+"/_deps.txt")
        source_includes[repo_path].add(dep_path+"/_srcs.txt")
        source_includes[repo_path].add(dep_path+"/.*/_deps.txt")
        source_includes[repo_path].add(dep_path+"/.*/_srcs.txt")
      }

      String job_name = createCoreTargetJob(repo, core, core_target,
                                            source_includes, config)

      // // If new job created rather than updated/removed, trigger build
      // // NOTE: In the case of job update, it shouldn't matter if existing
      // //       build job triggers before or after seed job when srcs/deps change.
      // if (!jenkins.model.Jenkins.instance.getItemByFullName(job_name)) {
      //   queue(job_name)
      // }
    }

  }
}

// Return Core[] extracted from specified cores file
def parseCoresLines(cores_lines) {
  def cores = []
  def unique_names = []

  // Extract core and supported targets
  // eachLine not currently supported by CPS
  //cores_lines.eachLine {
  cores_lines.split('\n').each {
    def matcher = it =~ /(?<targets>(?:\[\w+\])+)\s+(?<path>\S*)/

    if (! matcher.matches()) {
      println("Match failure for _core.txt line: ${it}")
      return
    }

    def path = matcher.group('path')
    def name = coreNameFromPath(path)
    if (! name) {
      throw new Exception("Unable to determine core name for path: ${path}")
      return
    }

    def targets = []
    matcher.group('targets').findAll(/\[(\w+?)\]/) {
      target -> targets << target[1]
    }

    // Fail job if duplicate core names detected
    if (unique_names.contains(name)) {
      throw new Exception("Duplicate core name '${name}'. Aborting.")
    }
    unique_names.add(name)

    cores << new Core(name: name, path: path, targets: targets)
  }

  return cores
}

// Return last directory of given path as core name
def coreNameFromPath(path) {
  def matcher = path =~ /.*?\/*(\w+)\s*$/

  return matcher.matches() ? matcher.group(1) : null
}

def generateBuildMeta(repo, core, core_target, config) {
  def working_dir = new File("${WORKSPACE}/${repo.name}/${core.path}")

  def p = "python rmake.py infer --target ${core_target} --seed".execute([], working_dir)

  // TODO: timeout
  def sbStd = new StringBuffer()
  def sbErr = new StringBuffer()
  p.waitForProcessOutput(sbStd, sbErr)

  if (p.exitValue() != 0) {
    println(sbStd)
    println(sbErr)
    throw new Exception("Error generating build meta for core '${core.name}' target '${core_target}'.")
  }
}

// return ArrayList of paths relative to work space directory.
def parseBuildMetaPaths(meta_filename, config) {
  String meta = readFile(meta_filename)

  def trim_count = WORKSPACE.length()+1

  // Change paths to relative to workspace root (+1 to remove leading slash)
  ArrayList meta_relative = []
  meta.split('\n').each { line ->
    meta_relative.add(line.substring(trim_count))
  }

  return meta_relative
}

def createCoreTargetJob(repo, core, core_target, source_includes, config) {
  String job_folder = "${repo.owner}-${repo.name}"

  jobDsl targets: ['replay_ci/jobs/core_target.groovy'].join('\n'),
         additionalParameters: [param_job_folder: job_folder,
                                param_core_name: core.name,
                                param_target_name: core_target],
         failOnSeedCollision: true,
         removedConfigFilesAction: 'DELETE',
         removedJobAction: 'DELETE',
         removedViewAction: 'DELETE'
}

// -----------------------------------------------------------------------------
// Pipeline
// -----------------------------------------------------------------------------

pipeline {
    agent any

    stages {
        stage('Debug Info') {
            //when {
              // environment name: 'PRODUCTION_SERVER', value: 'false'

              // expression {
              //   return ! config.isProduction
              // }
            //}
            steps {
                echo "Dumping Params"
                echo "param_repo_owner: ${params.param_repo_owner}"
                echo "param_repo_name: ${params.param_repo_name}"
                echo "param_repo_credential_id: ${params.param_repo_credential_id}"
                echo "param_repo_url: ${params.param_repo_url}"
                echo "param_repo_branch: ${params.param_repo_branch}"
                echo "Production: ${PRODUCTION_SERVER}"
                echo "Common: ${repo_common.includedRegions}"
            }
        }
        stage('Checkout: common') {
          steps {
            dir(repo_replay_ci.name) {
              git branch: repo_replay_ci.branch, url: repo_replay_ci.url
            }
            dir(repo_common.name) {
              checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${repo_common.branch}"]],
                userRemoteConfigs: [[
                   credentialsId: repo_common.credentialId,
                   url          : repo_common.url
                ]],
                extensions: [
                  [$class: 'PathRestriction', excludedRegions: '', includedRegions: repo_common.includedRegions]
                ]
              ])
            }
            sh "cp \"${repo_replay_ci.name}/scripts/local_settings.py\" \"${repo_common.name}/scripts/local_settings.py\""
          }
        }
        stage("Checkout: core") {
          when {
            not { equals expected: repo_common.name, actual: repo_seed.name }
          }
          steps {
            dir(repo_seed.name) {
              checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${repo_seed.branch}"]],
                userRemoteConfigs: [[
                  credentialsId: repo_seed.credentialId,
                  url          : repo_seed.url
                ]],
                extensions: [
                  [$class: 'PathRestriction', excludedRegions: '', includedRegions: repo_seed.includedRegions]
                ],
              ])
            }
          }
        }
        stage('Checkout: ps-fpga') {
          when {
            equals expected: "replay_console", actual: repo_seed.name
            // equals expected: "psx", actual: core.name
          }
          steps {
            dir(repo_psfpga.name) {
              checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${repo_psfpga.branch}"]],
                userRemoteConfigs: [[credentialsId: repo_psfpga.credentialId, url: repo_psfpga.url]],
                extensions: [
                  [$class: 'PathRestriction', excludedRegions: '', includedRegions: repo_psfpga.includedRegions]
                ]
              ])
            }
          }
        }
        stage('Seeding Target Jobs') {
          steps {
            createCoreJobs(config, repo_seed)
          }
        }
    }

    post {
      failure {
        echo "Run when build was a failure"
      }
      success {
        echo "Run when build was a success"
      }
    }
}
