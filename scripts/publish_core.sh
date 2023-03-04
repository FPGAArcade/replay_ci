#!/bin/bash
RELEASE_TRAIN=${1:-"devel"}

hash curl 2>/dev/null || { echo >&2 "curl (curl) required but not found.  Aborting."; exit 1; }
hash xmllint 2>/dev/null || { echo >&2 "xmllint (libxml2-utils) required but not found.  Aborting."; exit 1; }

RELEASE_ZIP=`ls "${core_name}_${core_target}_"*.zip`
RELEASE_ZIP_NAME=`basename ${RELEASE_ZIP}`

# Temp sanity check until all seed jobs updated to use promote script.
if [ "${RELEASE_TRAIN}" != "devel" ]; then
  echo >&2 "Unable to upload non 'devel' train builds."
  exit 1
fi

# DEPRECATED: Provides access to cores via raw directory access via web server. Left as a fallback
#             for manual core downloads in case of future api issues.
RELEASE_DIR="/home/jenkins/www/releases/builds/${RELEASE_TRAIN}/cores/${core_target}/${core_name}"
mkdir -p "${RELEASE_DIR}"
cp "${RELEASE_ZIP}" "${RELEASE_DIR}/"
ln -sf "${RELEASE_DIR}/${RELEASE_ZIP_NAME}" "${RELEASE_DIR}/latest"


echo "Uploading build ${BUILD_NUMBER} to '${RELEASE_TRAIN}' release train: ${RELEASE_ZIP}"

# Upload to release api
status=`curl --silent --output /dev/stderr -w "%{http_code}" --request POST \
            --header "x-api-key: ${releaseapikey}" \
            --form "buildinfo={
                        \"platformId\": \"${core_target}\",
                        \"coreId\": \"${core_name}\",
                        \"buildType\": \"core\",
                        \"releaseTrain\": \"${RELEASE_TRAIN}\",
                        \"buildDate\": \"${BUILD_TIMESTAMP}\"
                      };type=application/json" \
            --form "zipfile=@\"${RELEASE_ZIP}\";type=application/zip" \
            ${RELEASE_API_URL}builds/`

if [ "${status}" -lt 200 ] || [ "${status}" -ge 300 ]; then
  echo >&2 "API upload failed. Aborting."
  exit 1
fi
