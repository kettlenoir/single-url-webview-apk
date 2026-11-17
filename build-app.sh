#!/bin/bash
# Build the APK for the app tree in the current directory.
# Usage: cd <app-tree> && ./build-app.sh [output.apk]   (bare filename only)
# Toolchain location can be overridden with ANDROID_TOOLS=/path/to/tools.
set -e
TOOLS=${ANDROID_TOOLS:-$HOME/workspace/tools/android}
export JAVA_HOME=$TOOLS/jdk17
export PATH=$JAVA_HOME/bin:$PATH
export LANG=C.UTF-8
BT=$TOOLS/sdk/build-tools/34.0.0
JAR=$TOOLS/sdk/platforms/android-34/android.jar
OUT=$(basename "${1:-app.apk}")
KS=$PWD/build/debug.keystore

mkdir -p build/gen build/classes build/dex
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
fi
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/app-unsigned.apk -I $JAR --manifest AndroidManifest.xml \
  --java build/gen --min-sdk-version 26 --target-sdk-version 34 build/res.zip
javac -encoding UTF-8 --release 8 -cp $JAR -d build/classes $(find build/gen src -name "*.java")
$BT/d8 --release --lib $JAR --output build/dex $(find build/classes -name "*.class")
cd build
zip -qj app-unsigned.apk dex/classes.dex
$BT/zipalign -pf 4 app-unsigned.apk app-aligned.apk
$BT/apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT" app-aligned.apk
$BT/apksigner verify "$OUT"
echo "BUILT: build/$OUT"
