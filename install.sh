#!/usr/bin/env bash
# Install BEESH Browser on an Android TV / Google TV over the network.
#
#   ./install.sh 192.168.1.20            install the APK in dist/, make it the default browser and
#                                        allow the microphone and installing its own updates
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

# Connects to the TV and waits (up to a minute) for it to allow this computer, saying what to do.
connect_tv() {
  local out
  out="$(adb connect "$TV" 2>&1 || true)"
  case "$out" in
    *"connected to"*) ;;
    *) echo "Couldn't reach the TV at $TV ($out)."
       echo "Check the IP address, that the TV and this computer are on the same network, and that"
       echo "USB debugging (or Network debugging) is on in the TV's Developer options."
       exit 1 ;;
  esac
  local i
  for i in $(seq 1 30); do
    case "$(adb get-state 2>&1)" in
      device) return 0 ;;
      *unauthorized*) [ "$i" = 1 ] && echo "On the TV, choose Allow on \"Allow USB debugging?\" (tick Always allow)." ;;
    esac
    sleep 2
  done
  echo "The TV didn't allow debugging from this computer. Run this again and choose Allow on the TV."
  exit 1
}

# Installs an APK, sorting out the usual reasons a TV turns one down.
install_apk() {
  local apk="$1" pkg="$2" out
  if out="$(adb install -r "$apk" 2>&1)"; then return 0; fi
  case "$out" in
    *UPDATE_INCOMPATIBLE*|*INCONSISTENT_CERTIFICATES*|*signatures*|*VERSION_DOWNGRADE*)
      # Another build (signed with another key, or newer) is on the TV: replace it.
      echo "Replacing the copy of $pkg already on the TV (it was signed differently or is newer)."
      adb uninstall "$pkg" >/dev/null 2>&1 || true
      if out="$(adb install "$apk" 2>&1)"; then return 0; fi ;;
  esac
  case "$out" in
    *OLDER_SDK*) echo "This TV runs Android $(adb shell getprop ro.build.version.release | tr -d '\r'); BEESH needs Android 9 or newer." ;;
    *VERIFICATION_FAILURE*|*INSTALL_FAILED_ABORTED*|*USER_RESTRICTED*)
      echo "The TV blocked the install. On the TV, turn off \"Verify apps over USB\" in Developer options"
      echo "(or allow it in Google Play's Play Protect), then run this again." ;;
    *INSUFFICIENT_STORAGE*) echo "The TV is out of storage. Free some space and run this again." ;;
  esac
  echo "$out" | tail -3
  return 1
}

connect_tv

if [ "${2:-}" = "--undo" ]; then
  adb uninstall $PKG >/dev/null 2>&1 || true
  echo "Removed."
  exit 0
fi

APK="$(ls -t dist/BeeshTV-Browser-*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { echo "No APK in dist/. Run browser/build.sh first."; exit 1; }
echo "Installing $APK on $TV"
install_apk "$APK" $PKG || exit 1
# Lets the browser install its own updates from GitHub (the TV still asks to confirm each one).
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
# Voice typing through the remote's microphone.
adb shell pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
# The default browser: web links from other apps open here.
adb shell cmd role add-role-holder --user 0 android.app.role.BROWSER $PKG >/dev/null 2>&1 || true
adb shell am start -n $PKG/.BrowserActivity >/dev/null 2>&1 || true
echo "Done. BEESH Browser is open on the TV."
