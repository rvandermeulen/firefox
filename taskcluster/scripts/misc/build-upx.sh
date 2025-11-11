#!/bin/bash
set -x -e -v

# This script is for building UPX.
PROJECT="upx"

pushd "${MOZ_FETCHES_DIR}/${PROJECT}"
make
popd

mv "${MOZ_FETCHES_DIR}/${PROJECT}/build/release/upx" "${PROJECT}"
tar -acf "${PROJECT}.tar.zst" "${PROJECT}"

mkdir -p "$UPLOAD_DIR"
mv "${PROJECT}.tar.zst" "$UPLOAD_DIR"