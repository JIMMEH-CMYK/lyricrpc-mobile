#!/usr/bin/env bash
# Build LyricRPC-mobile into a signed, installable APK with no Gradle and no Android Studio:
# aapt2 -> javac -> d8 -> jar -> zipalign -> apksigner, all from C:/Users/USER/tools/android-build.
#
# The SDK helpers are .bat wrappers around java, so they are called through java.exe directly: it
# avoids cmd quoting and it means every path handed to them must already be a Windows path.
set -euo pipefail

# The JDK and the SDK live together, uninstalled and deleteable; override ANDROID_BUILD_TOOLS if yours
# sit somewhere else.
TOOLS="${ANDROID_BUILD_TOOLS:-C:/Users/USER/tools/android-build}"
SDK="$TOOLS/sdk"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVA="$TOOLS/jdk17/bin/java.exe"

cd "$(dirname "$0")"
WIN_PROJECT="$(pwd -W)"
BUILD="$WIN_PROJECT/build"

# One place to bump: the manifest falls back to these when it is linked.
VERSION_CODE=5
VERSION_NAME=1.1.3

say() { echo "==> $*"; }

say "clean"
rm -rf "$BUILD"
mkdir -p "$WIN_PROJECT/build/gen" "$WIN_PROJECT/build/classes" "$WIN_PROJECT/build/dex" "$WIN_PROJECT/build/apk"

say "checking for regex the phone cannot compile"
# Pattern.UNICODE_CHARACTER_CLASS is accepted by the desktop JDK and rejected outright by Android's
# regex engine, so CoreTest passes right up until the app is on a phone. This is the only check that
# catches it here. Only the call form matches, so a comment can name the flag in peace.
if grep -rqE ',[[:space:]]*Pattern\.UNICODE_CHARACTER_CLASS' "$WIN_PROJECT/src"; then
  echo "src/ compiles a Pattern with UNICODE_CHARACTER_CLASS; Android cannot. Drop the flag." >&2
  exit 1
fi

say "aapt2 compile res/"
"$BUILD_TOOLS/aapt2.exe" compile --dir "$WIN_PROJECT/res" -o "$BUILD/res.zip"

say "aapt2 link"
"$BUILD_TOOLS/aapt2.exe" link \
  -o "$BUILD/apk/base.apk" \
  --manifest "$WIN_PROJECT/AndroidManifest.xml" \
  -I "$PLATFORM" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$BUILD/res.zip"

say "javac"
# -source/-target 8 rather than --release, because android.jar has to be the boot classpath.
find "$WIN_PROJECT/src" -name '*.java' ! -name 'CoreTest.java' > "$BUILD/sources.txt"
echo "$BUILD/gen/com/lyricrpc/mobile/R.java" >> "$BUILD/sources.txt"
"$TOOLS/jdk17/bin/javac.exe" \
  -encoding UTF-8 \
  -nowarn \
  -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -d "$BUILD/classes" \
  "@$BUILD/sources.txt"

say "d8"
( cd "$BUILD/classes" && find . -name '*.class' | sed 's|^\./||' | tr -d '\r' > "$BUILD/classes.txt" )
CLASSES=()
while IFS= read -r entry; do CLASSES+=("$BUILD/classes/$entry"); done < "$BUILD/classes.txt"
[ ${#CLASSES[@]} -gt 0 ] || { echo "FATAL javac produced no classes"; exit 1; }
"$JAVA" -Xmx1024M -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
  --release \
  --min-api 26 \
  --lib "$PLATFORM" \
  --output "$BUILD/dex" \
  "${CLASSES[@]}"
[ -f "$BUILD/dex/classes.dex" ] || { echo "FATAL d8 produced no classes.dex"; exit 1; }

say "packaging classes.dex into the apk"
"$TOOLS/jdk17/bin/jar.exe" --update --file "$BUILD/apk/base.apk" -C "$BUILD/dex" classes.dex

say "zipalign"
"$BUILD_TOOLS/zipalign.exe" -f 4 "$BUILD/apk/base.apk" "$BUILD/apk/aligned.apk"

say "apksigner"
KEYSTORE="$WIN_PROJECT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  "$TOOLS/jdk17/bin/keytool.exe" -genkeypair -noprompt \
    -keystore "$KEYSTORE" \
    -alias lyricrpc \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=LyricRPC debug key, O=LyricRPC, C=US" \
    -storepass android -keypass android >/dev/null
fi
OUT="$WIN_PROJECT/LyricRPC-$VERSION_NAME.apk"
rm -f "$OUT"
"$JAVA" -cp "$BUILD_TOOLS/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$BUILD/apk/aligned.apk"

say "verify"
"$JAVA" -cp "$BUILD_TOOLS/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify --print-certs "$OUT"
say "APK_READY $OUT ($(stat -c %s "$OUT") bytes)"
