#!/bin/bash
ARG_CORE_NAME=$1
ARG_CORE_TARGET=$2

pushd "sdcard" || exit $?

# TODO: Determine API version
VERSION=`git describe --tags --always --long`
DATE=`date -u '+%Y%m%d_%H%M'`
RELEASE_ZIP="${ARG_CORE_NAME}_${ARG_CORE_TARGET}_${DATE}_${VERSION}.zip"

zip -r "${RELEASE_ZIP}" *

mv "${RELEASE_ZIP}" "${WORKSPACE}"

popd

exit $?