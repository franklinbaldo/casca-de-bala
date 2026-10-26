#!/bin/sh
# Compile a DEX jar only. No signing, installation, adb startup or Bluetooth.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${BC_JAR:?Supply verified BC1.86 jar}"
: "${ANDROID_HOME:?Supply existing Android SDK}"
test "$(sha256sum "$BC_JAR" | cut -d' ' -f1)" = 2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f
OUT=${1:?Usage: build_art_jni_fixture.sh OUTPUT_DIRECTORY}
mkdir -p "$OUT"
BUILD=$(mktemp -d /tmp/casca-art-fixture-XXXXXX)
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
find "$ROOT/crypto-src" "$ROOT/crypto-tests" "$ROOT/src" "$ROOT/android-fixtures" -name '*.java' > "$BUILD/sources"
printf '%s\n' "$ROOT/tests/org/workspace/band7/BridgeSmoke.java" "$ROOT/tests/org/workspace/band7/GattBridgeSmoke.java" >> "$BUILD/sources"
java -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all -Werror -cp "$BC_JAR:$ANDROID_HOME/platforms/android-35/android.jar" -d "$BUILD/classes" @"$BUILD/sources"
java -m jdk.jartool/sun.tools.jar.Main --create --file "$BUILD/test-classes.jar" -C "$BUILD/classes" .
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 31 --lib "$ANDROID_HOME/platforms/android-35/android.jar" --output "$BUILD/jni-fixture.jar" "$BUILD/test-classes.jar" "$BC_JAR"
cp "$BUILD/jni-fixture.jar" "$OUT/jni-fixture.jar"
cp "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt" "$OUT/public-vector.txt"
printf '%s\n' 'DEX only; NOT an APK, ART run, JNI or hardware result. No keys generated.'
sha256sum "$OUT/jni-fixture.jar" "$OUT/public-vector.txt"
