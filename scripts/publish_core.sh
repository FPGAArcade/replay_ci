#!/bin/bash

RELEASE_TRAIN=${1:-"devel"}

hash curl 2>/dev/null || { echo >&2 "curl (curl) required but not found.  Aborting."; exit 1; }
hash xmllint 2>/dev/null || { echo >&2 "xmllint (libxml2-utils) required but not found.  Aborting."; exit 1; }

RELEASE_ZIP=`ls "${core_name}_${core_target}_"*.zip`
RELEASE_ZIP_NAME=`basename ${RELEASE_ZIP}`

# DEPRECATED: Will be removed once Jenkins migrated to docker and new api
#             upload has user friendly front end to access builds.
# Update "latest" sym link
RELEASE_DIR="/home/jenkins/www/releases/cores/${core_target}/${core_name}"
cp "${RELEASE_ZIP}" "${RELEASE_DIR}/"
ln -sf "${RELEASE_DIR}/${RELEASE_ZIP_NAME}" "${RELEASE_DIR}/latest"

echo "Promoting build ${BUILD_NUMBER} to stable release: ${RELEASE_ZIP}"

# Upload to release api
status=`curl --silent --output /dev/stderr -w "%{http_code}" --request POST \
            --header "Authorization: APIKey ${releaseapikey}" \
            --form "buildinfo={
                        \"platformId\": \"${core_target}\",
                        \"coreId\": \"${core_name}\",
                        \"buildType\": \"${RELEASE_TRAIN}\",
                        \"buildDate\": \"${BUILD_TIMESTAMP}\"
                      };type=application/json" \
            --form "zipfile=@\"${RELEASE_ZIP}\";type=application/zip" \
            ${RELEASE_API_URL}builds/`

if [ "${status}" -lt 200 ] || [ "${status}" -ge 300 ]; then
  echo >&2 "API upload failed. Aborting."
  exit 1
fi

# TODO: Remove and perform notification as part of backend api for stable releases.
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
fi

curl -X POST --header "Content-Type: application/json" --data "${DISCORD_MESSAGE}" ${discordreleasewebhook}