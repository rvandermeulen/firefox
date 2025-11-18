#!/bin/bash
set -x -e -v

# This script is for building UPX.
PROJECT="upx"

if test -d "$MOZ_FETCHES_DIR/cmake"; then
    export PATH="$(cd $MOZ_FETCHES_DIR/cmake && pwd)/bin:${PATH}"
fi

if [[ $(uname -o) == "Msys" ]]; then
  MAKE="$MOZ_FETCHES_DIR/mozmake/mozmake"
  SUFFIX=".exe"
  . "$GECKO_PATH/taskcluster/scripts/misc/vs-setup.sh"
else
  MAKE=make
  SUFFIX=""
fi

pushd "${MOZ_FETCHES_DIR}/${PROJECT}"
$MAKE
popd

mkdir -p "${PROJECT}/bin"
mv "${MOZ_FETCHES_DIR}/${PROJECT}/build/release/upx${SUFFIX}" "${PROJECT}/bin/"
tar -acf "${PROJECT}.tar.zst" "${PROJECT}"

mkdir -p "$UPLOAD_DIR"
mv "${PROJECT}.tar.zst" "$UPLOAD_DIR"