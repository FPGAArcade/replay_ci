folder("${param_job_folder}")

folder("${param_job_folder}/${param_core_name}")

job("${param_job_folder}/${param_core_name}/${param_target_name}") {
  description("Auto-created build job for ${param_core_name}")
  steps {
    shell('echo Hello World!')
  }
}