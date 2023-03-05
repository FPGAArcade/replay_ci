#!/bin/bash
RELEASE_TRAIN=$1
PROMOTION_BUILD_ID=$2

if [ $# != 2 ]; then
    echo "Usage:"
    echo "$0 [train name] [build Id]"
    echo ""
    exit 1
fi

if [ -z ${RELEASE_API_URL+x} ] || [ -z ${releaseapikey} ]; then
  echo "Required environment variables are not set:-"
  echo "  RELEASE_API_URL"
  echo "  releaseapikey"
  echo ""
  echo "Aborting"  
  exit 1
fi

status=`curl --silent --output /dev/stderr -w "%{http_code}" --request PUT \
        --header "x-api-key: ${releaseapikey}" \
        --header "Content-Type: application/json" \
        --data "{
            \"trains\": [
                \"${RELEASE_TRAIN}\"
            ]
        }" \
        "${RELEASE_API_URL}builds/${PROMOTION_BUILD_ID}/releaseTrains"`

if [ "${status}" -lt 200 ] || [ "${status}" -ge 300 ]; then
    echo >&2 "API upload failed. Aborting."
    exit 1
fi

# TODO: Remove and perform notification as part of backend api for stable releases.
#       Promotion will be removed entirely from Jenkins once auth'd web frontend available
# Notify discord
if [ "${RELEASE_TRAIN}" = "stable" ]; then

    status=`curl --silent -o uploaded-build-info.txt -w "%{http_code}" \
            --header "accept: application/json" \
            "${RELEASE_API_URL}builds/${PROMOTION_BUILD_ID}"`

    if [ "${status}" -lt 200 ] || [ "${status}" -ge 300 ]; then
        echo >&2 "API query failed. Aborting notification."
        exit 1
    fi

    infoBuildID=`cat uploaded-build-info.txt  | jq -r '.id'`
    infoDownloadURL=`cat uploaded-build-info.txt  | jq -r '.downloadURL'`
    infoZipName=`basename ${infoDownloadURL}`

    # DEPRECATED: Provides access to cores via raw directory access via web server. Left as a fallback
    #             for manual core downloads in case of future api issues.
    RELEASE_DIR="/home/jenkins/www/releases/builds/${RELEASE_TRAIN}/cores/${core_target}/${core_name}"
    mkdir -p "${RELEASE_DIR}"
    cp "/home/jenkins/www/releases/builds/devel/cores/${core_target}/${core_name}/${infoZipName}" "${RELEASE_DIR}/"
    ln -sf "${RELEASE_DIR}/${infoZipName}" "${RELEASE_DIR}/latest"

    read -d '' DISCORD_MESSAGE <<EOF
{
  "content": "A new core ${RELEASE_TRAIN} release is available.",
  "embeds": [
    {
      "title": "${core_name} (${core_target})",
      "url": "${infoDownloadURL}",
      "color": null,
      "fields": [
        {
            "name": "Build ID",
            "value": "${infoBuildID}"
        },
        {
          "name": "Download",
          "value": "[${infoZipName}](${infoDownloadURL})"
        },
        {
          "name": "Stable Releases",
          "value": "${JENKINS_URL}releases/builds/${RELEASE_TRAIN}/cores/${core_target}/${core_name}/"
        }
      ]
    }
  ]
}
EOF

    curl -X POST --header "Content-Type: application/json" --data "${DISCORD_MESSAGE}" ${discordreleasewebhook}

fi