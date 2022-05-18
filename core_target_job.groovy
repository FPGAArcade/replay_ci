folder("${job_folder}")

folder("${job_folder}/${core_name}")

job("${job_folder}/${core_name}/${core_target}") {
  description("Autocreated build job for ${core_name}")
  steps {
    shell('echo Hello World!')
  }
}