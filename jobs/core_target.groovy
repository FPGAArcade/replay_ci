pipeline {
    agent any

    stages {
        stage('Test') {
            steps {
                echo("running core_target test step.")
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