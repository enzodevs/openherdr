#!/usr/bin/env sh
# Downloads the sherpa-onnx Android AAR (speech recognition runtime) into app/libs and checks its SHA-256.
# The digest is GitHub's published asset digest for the v1.13.8 release.
set -eu
VERSION=1.13.8
SHA256=633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
DEST="$(dirname "$0")/../app/libs/sherpa-onnx-$VERSION.aar"
mkdir -p "$(dirname "$DEST")"
curl -fsSL -o "$DEST.part" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"
echo "$SHA256  $DEST.part" | sha256sum -c -
mv "$DEST.part" "$DEST"
