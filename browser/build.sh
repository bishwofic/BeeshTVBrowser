#!/usr/bin/env bash
# Build the browser APK with the plain SDK tools (no Gradle) into dist/.
#   browser/build.sh                 build
#   browser/build.sh 192.168.1.20    build, then install on that TV with install.sh
#   browser/build.sh --play          build the Google Play bundle into dist/play/ (see README)
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
B=build
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)"
PLAY=""
[ "${1:-}" = "--play" ] && { PLAY=1; shift; }

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex" ../dist
MANIFEST=AndroidManifest.xml
LINK_FORMAT=""
if [ -n "$PLAY" ]; then
  command -v bundletool >/dev/null || { echo "Needs bundletool: brew install bundletool"; exit 1; }
  # Apps from Google Play may only be updated by Play: no receiver for the installer's answer. The
  # permission to install packages stays, for opening downloaded APKs (declared in the Play Console).
  MANIFEST="$B/AndroidManifest.xml"
  perl -0pe 's/\n[ \t]*<!-- The system installer.s answer[^\n]*-->\n[ \t]*<receiver\s+android:name="\.Updater\$InstallStatus"[^>]*\/>//s' \
    AndroidManifest.xml > "$MANIFEST"
  if grep -q InstallStatus "$MANIFEST"; then
    echo "Couldn't take the updater out of the Play manifest; check AndroidManifest.xml"; exit 1
  fi
  LINK_FORMAT="--proto-format"
fi

"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link $LINK_FORMAT -o "$B/base.apk" -I "$JAR" --manifest "$MANIFEST" --java "$B/gen" \
  -A assets -0 jpg -0 ttf --min-sdk-version 28 --target-sdk-version 34 "$B/res.zip"
SOURCES="$(find src "$B/gen" -name '*.java')"
if [ -n "$PLAY" ]; then
  # src-play/ replaces the files of the same name in src/ (Updater without updating).
  SOURCES="$(grep -v -x -F -f <(cd src-play && find . -name '*.java' | sed 's|^\./|src/|') <<<"$SOURCES")
$(find src-play -name '*.java')"
fi
# androidx.webkit (WebView features newer than the platform API) ships in the APK; the annotation
# jars are only needed to compile against it.
LIBS="libs/androidx-webkit-1.12.1.jar"
COMPILE_ONLY="libs/androidx-annotation-1.8.1.jar:libs/androidx-annotation-experimental-1.4.1.jar"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR:$LIBS:$COMPILE_ONLY" -d "$B/classes" $SOURCES
"$BT/d8" --min-api 28 --lib "$JAR" --classpath libs/androidx-annotation-1.8.1.jar \
  --classpath libs/androidx-annotation-experimental-1.4.1.jar --output "$B/dex" \
  $(find "$B/classes" -name '*.class') $LIBS

if [ -n "$PLAY" ]; then
  # The bundle's one module: the manifest, resources and assets aapt2 wrote, plus the code.
  M="$B/module"
  mkdir -p "$M/manifest" "$M/dex"
  (cd "$M" && unzip -q ../base.apk)
  mv "$M/AndroidManifest.xml" "$M/manifest/"
  cp "$B"/dex/*.dex "$M/dex/"
  (cd "$M" && zip -q -r ../base.zip .)
  mkdir -p ../dist/play
  OUT="../dist/play/BeeshTV-Browser-$VERSION.aab"
  rm -f "$OUT"
  bundletool build-bundle --modules="$B/base.zip" --output="$OUT"
  # Signed with the upload key, which only Google Play sees; Play signs the APKs it hands out.
  # Back it up: losing it means asking Play support to reset it. BEESH_UPLOAD_PASS skips the prompt.
  KS="${BEESH_UPLOAD_KEYSTORE:-upload.keystore}"
  PASS=()
  [ -n "${BEESH_UPLOAD_PASS:-}" ] && PASS=(-storepass:env BEESH_UPLOAD_PASS)
  if [ ! -f "$KS" ]; then
    echo "Creating the Google Play upload key ($KS): choose a password and keep it safe."
    keytool -genkeypair -keystore "$KS" -alias upload -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=BeeshTV Upload" ${PASS[@]+"${PASS[@]}"}
  fi
  jarsigner -keystore "$KS" ${PASS[@]+"${PASS[@]}"} "$OUT" upload >/dev/null
  echo "Built dist/play/$(basename "$OUT") ($(du -h "$OUT" | cut -f1 | xargs)), for the Play Console"
  exit 0
fi

cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
# Keep this keystore: updates must be signed with the same key to install over an older version.
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias beeshtvbrowser -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BeeshTV Browser" >/dev/null 2>&1
OUT="../dist/BeeshTV-Browser-$VERSION.apk"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$OUT" "$B/aligned.apk"
rm -f "$OUT.idsig"
echo "Built dist/$(basename "$OUT") ($(du -h "$OUT" | cut -f1 | xargs))"

[ -n "${1:-}" ] && ../install.sh "$1"
exit 0
