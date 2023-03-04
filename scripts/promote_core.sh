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
read -d '' DISCORD_MESSAGE <<EOF
{
  "content": "A new core stable release is available.",
  "embeds": [
    {
      "title": "${core_name} (${core_target})",
      "url": "https://build.fpgaarcade.com/releases/cores/${core_target}/${core_name}/${RELEASE_ZIP_NAME}|${RELEASE_ZIP_NAME}",
      "color": null,
      "fields": [
        {
          "name": "Download",
          "value": "[${RELEASE_ZIP_NAME}](https://build.fpgaarcade.com/releases/cores/${core_target}/${core_name}/${RELEASE_ZIP_NAME})"
        },
        {
          "name": "Previous Releases",
          "value": "https://build.fpgaarcade.com/releases/cores/${core_target}/${core_name}/"
        }
      ]
    }
  ]
}
EOF
curl -X POST --header "Content-Type: application/json" --data "${DISCORD_MESSAGE}" ${discordreleasewebhook}
fi