# FPGA Arcade Jenkins CI.

Jenkins JobDSL build server scripts. Assumes a single manual job has been
configured on Jenkins for the master branch of this repository along with a
webhook and has "zathras.groovy" as the job DSL.

Zathras will generate a seed job for each non-disabled/non-testing repository
listed in repos.json. Generated seed jobs will generate a build job for
each supported build target of each core listed in the _cores.txt of the
relevant repository.

## Repo Format

json list of repositories to monitor for cores.

  - "owner" Github repository account/organisation owner
  - "name" Github repository name
  - "url": https or ssh github url
  - "branch": (default master) Github branch to generate seed job/core builds from
  - "credentialId" Jenkins credential ID. Only required for private repositories.
  - "testingOnly": (default false) Set true for repositories that should be created
                   on the development jenkins server
  - "disabled": true/false (default false) Skip creation of seed jobs for this repo.
                Disabling an existing repo will currently cause all related build
                jobs to be removed.

## Required Plugins

  - Authorize Project
  - Copy Artifacts
  - Credentials Binding
  - disk-usage
  - GitHub
  - Green Balls
  - Job DSL
  - Multiple SCMs
  - Pipeline: Nodes and Processes
  - Promoted Builds
  - pyenv
  - Simple Theme
  - Slack Notification

## Configuration

### Environment

  - PRODUCTION_SERVER: true/false.
  - RELEASE_DIR: Path to copy promoted build zips (deprecated)
  - RELEASE_API_URL: Base URL for api service (incl. trailing slash).

### Credentials

  - Suitable credentials for each private repo listed in repos.json
  - "slackwebhookurl": URL curl posts stable release notifications to for slack
  - "slack-jenkins-ci-token": Token required by slack plugin to post build
    success notifications.
  - "release-api-key": Authorisation key for uploading to release api


## Testing

Please treat the testing branch as though it does not exist. It's really a local
working branch that is only pushed to GitHub because the Test Jenkins server needs
to monitor a branch of replay_ci for deployment changes.

The testing branch _will_ be rebased to clean up commits before merging into
master and due to being pushed to GitHub it _will_ be force pushed.

Copyright 2019 FPGA Arcade, All Rights Reserved.