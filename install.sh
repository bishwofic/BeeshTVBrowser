#!/usr/bin/env bash
# Install BeeshTV Browser on an Android TV / Google TV over the network.
#
#   ./install.sh 192.168.1.20            install the APK in dist/ and let it install its own updates
#   ./install.sh 192.168.1.20 --undo     remove it
#
# Turn on the TV's developer options and USB / network debugging first, and accept the
# "Allow debugging?" prompt on the TV the first time.
set -euo pipefail
cd "$(dirname "$0")"

TV="${1:-${ANDROID_SERIAL:-}}"
[ -n "$TV" ] || { echo "Usage: ./install.sh <tv-ip> [--undo]"; exit 1; }
[[ "$TV" == *:* ]] || TV="$TV:5555"
export ANDROID_SERIAL="$TV"
PKG=com.beeshtv.browser

adb connect "$TV" >/dev/null
adb wait-for-device

if [ "${2:-}" = "--undo" ]; then
  adb uninstall $PKG >/dev/null 2>&1 || true
  echo "Removed."
  exit 0
fi

APK="$(ls -t dist/BeeshTV-Browser-*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { echo "No APK in dist/. Run browser/build.sh first."; exit 1; }
echo "Installing $APK on $TV"
adb install -r --no-incremental "$APK"
# Lets the browser install its own updates from GitHub (the TV still asks to confirm each one).
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
adb shell am start -n $PKG/.BrowserActivity >/dev/null
echo "Done. BeeshTV Browser is open on the TV."
