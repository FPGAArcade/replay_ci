#!/bin/bash
ARG_TARGET=$1

# Build Prerequisites
hash zip 2>/dev/null || { echo >&2 "zip required but not found.  Aborting."; exit 1; }
hash git 2>/dev/null || { echo >&2 "git required but not found.  Aborting."; exit 1; }
hash python 2>/dev/null || { echo >&2 "python required but not found.  Aborting."; exit 1; }

python_major_v=$(python -c"import sys; print(sys.version_info.major)")
python_minor_v=$(python -c"import sys; print(sys.version_info.minor)")

if [[ "${python_major_v}" -lt "3" || ("${python_major_v}" -eq "3" && "${python_minor_v}" -lt "6") ]]; then
    echo "Build system requires python 3.6 or greater (${python_major_v}.${python_minor_v} installed)"
    exit 1
fi

# Build
python rmake.py infer --target "${ARG_TARGET}" || exit $?