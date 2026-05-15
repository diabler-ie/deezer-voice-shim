#!/bin/bash
set -euo pipefail

export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes build/gen build/dex

echo "==> aapt2 compile resources"
aapt2 compile --dir res -o build/compiled_res.zip

echo "==> aapt2 link"
aapt2 link \
    -o build/linked.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    build/compiled_res.zip

echo "==> javac"
javac -source 1.8 -target 1.8 \
    -classpath "$ANDROID_JAR" \
    -d build/classes \
    src/com/spotify/music/*.java \
    build/gen/com/spotify/music/R.java

echo "==> d8 (dex)"
d8 --min-api 26 \
    --output build/dex \
    build/classes/com/spotify/music/*.class

echo "==> repackage APK with classes.dex"
cp build/linked.apk build/shim-unsigned.apk
( cd build/dex && zip -j ../shim-unsigned.apk classes.dex )

echo "==> done: build/shim-unsigned.apk"
ls -lh build/shim-unsigned.apk
