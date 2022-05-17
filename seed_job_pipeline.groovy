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

// TODO: Remove the param_ prefix now jenkins includes via params. object
// Wrap Params
Repo repo = new Repo(owner: params.param_repo_owner, name: params.param_repo_name,
                    credentialId: params.param_repo_credential_id, url: params.param_repo_url,
                    branch: params.param_repo_branch)

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
  cores_lines.eachLine {
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
  // TODO: Rewrite to read scripts from existing workspace.
  // TODO: Utilise steps to avoid unsafe groovy script usage.

  // TODO: Remove duplication of paths and need to hard code in the seed job
  //       both here and in the created job shell script.
  File local_settings = new File("${WORKSPACE}/replay_common/scripts/local_settings.py")

  local_settings.write("""\
  # Local paths auto generated via jenkins project build script
  ISE_PATH = '/opt/Xilinx/14.7/ISE_DS/ISE/'
  ISE_BIN_PATH = '/opt/Xilinx/14.7/ISE_DS/ISE/bin/lin64/'

  MODELSIM_PATH = ''
  QUARTUS_PATH = '/opt/intelFPGA_lite/20.1/quartus/bin/'

  # if UNISIM_PATH is empty, a local (tb/sim) library will be created
  UNISIM_PATH = None
  """.stripIndent())

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
  meta.eachLine { line ->
    meta_relative.add(line.substring(trim_count))
  }

  return meta_relative
}

def createCoreTargetJob(repo, core, core_target, source_includes, config) {
  String job_folder = "${repo.owner}-${repo.name}"

  String job_name = "${job_folder}/${core.name}/${core_target}"


  // TODO: Implement me
  // TODO: Move to repo script rather than inline
  jobDsl scriptText: '''
    folder(job_folder)
    folder("${job_folder}/${core_name}")

    job("${job_folder}/${core_name}/${core_target}") {
      description("Autocreated build job for ${core_name}")
      steps {
      shell('echo Hello World!')
      }
    }
    '''.stripIndent(),
    additionalParameters: [job_folder: job_folder,
                           core_name: core.name,
                           core_target: core_target]

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
            }
        }
        stage('Checkout: replay_common') {
          steps {
            dir('replay_common') {
              // TODO: Include/Exclude paths for monitoring/triggering
              // TODO: Based on production or not checkout correct replay_common
              // REVIEW: Would be better to have all the repo differences for testing/production
              //         sorted out higher up as part of config or via env
              checkout([$class: 'GitSCM', branches: [[name: '*/master']], extensions: [], userRemoteConfigs: [[credentialsId: 'sector14_replay_common', url: 'git@github.com:Sector14/replay_common.git']]])
            }
          }
        }
        stage("Checkout: core") {
          when {
            not { equals expected: "replay_common", actual: repo.name }
          }
          steps {
            dir(repo.name) {
              // TODO: Include/Exclude paths for monitoring/triggering
              // TODO: Based on production or not checkout correct replay_common
              // REVIEW: Would be better to have all the repo differences for testing/production
              //         sorted out higher up as part of config or via env
              checkout([$class: 'GitSCM', branches: [[name: "*/${repo.branch}"]], extensions: [], userRemoteConfigs: [[credentialsId: repo.credentialId, url: repo.url]]])
            }
          }
        }
        stage('Checkout: ps-fpga') {
          when {
            equals expected: "replay_console", actual: repo.name
            // equals expected: "psx", actual: core.name
          }
          steps {
            dir("ps-fpga") {
              // TODO: Remove hardcoded hacky 3rd repo for psx. Needs proper support
              //       to pull in additional repos
              // TODO: Production/Testing support
              checkout([$class: 'GitSCM', branches: [[name: "*/main"]], extensions: [], userRemoteConfigs: [[credentialsId: "sector14_ps-fpga", url: "git@github.com:Sector14/ps-fpga"]]])
            }
          }
        }
        stage('Seeding Core Jobs') {
          steps {
            createCoreJobs(config, repo)
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
