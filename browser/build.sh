#!/usr/bin/env bash
# Build the browser APK with the plain SDK tools (no Gradle) into dist/.
#   browser/build.sh                 build
#   browser/build.sh 192.168.1.20    build, then install on that TV with install.sh
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
B=build
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)"

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex" ../dist
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets -0 jpg -0 ttf --min-sdk-version 28 --target-sdk-version 34 "$B/res.zip"
# androidx.webkit (WebView features newer than the platform API) ships in the APK; the annotation
# jars are only needed to compile against it.
LIBS="libs/androidx-webkit-1.12.1.jar"
COMPILE_ONLY="libs/androidx-annotation-1.8.1.jar:libs/androidx-annotation-experimental-1.4.1.jar"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR:$LIBS:$COMPILE_ONLY" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 28 --lib "$JAR" --classpath libs/androidx-annotation-1.8.1.jar \
  --classpath libs/androidx-annotation-experimental-1.4.1.jar --output "$B/dex" \
  $(find "$B/classes" -name '*.class') $LIBS
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
