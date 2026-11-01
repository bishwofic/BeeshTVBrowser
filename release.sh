#!/usr/bin/env bash
# Build the browser and publish it as a GitHub release, which TVs then offer as an update.
#   ./release.sh            release the version in browser/AndroidManifest.xml
# Bump android:versionCode and android:versionName in the manifest first; the tag is v<versionName>.
# Needs the GitHub CLI (`brew install gh`, then `gh auth login`).
set -euo pipefail
cd "$(dirname "$0")"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' browser/AndroidManifest.xml)"
if gh release view "v$VERSION" >/dev/null 2>&1; then
  echo "v$VERSION is already released. Bump versionName in browser/AndroidManifest.xml first."
  exit 1
fi
browser/build.sh
gh release create "v$VERSION" "dist/BeeshTV-Browser-$VERSION.apk" \
  --title "BEESH Browser $VERSION" --generate-notes
echo "Released v$VERSION. TVs pick it up within six hours, or at once from Settings → Updates."
