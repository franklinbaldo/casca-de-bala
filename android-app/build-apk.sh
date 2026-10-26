#!/bin/sh
# Laboratory APK only. Temporary standard debug key; never a production identity.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_BIN=${GRADLE_BIN:-"$ROOT/gradlew"}
: "${ANDROID_HOME:?Set ANDROID_HOME to approved SDK}"
test -s "$ROOT/gradle/verification-metadata.xml" || { echo 'Missing pinned verification metadata; refusing build' >&2; exit 2; }
cd "$ROOT"
# Verify the pinned plugin inputs BEFORE creating a laboratory signing identity.
"$GRADLE_BIN" --offline --no-daemon --dependency-verification strict help
KEY_DIR=$(mktemp -d /tmp/band7-debug-signing-XXXXXX)
trap 'rm -rf "$KEY_DIR"' EXIT HUP INT TERM
: "${JAVA_HOME:?Set JAVA_HOME to the validated Java toolchain}"
"$JAVA_HOME/bin/keytool" -genkeypair -noprompt -storetype JKS \
    -keystore "$KEY_DIR/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 \
    -dname 'CN=Android Debug,O=Android,C=US'
cd "$ROOT"
"$GRADLE_BIN" --offline --no-daemon --dependency-verification strict \
    -Pband7DebugKeystore="$KEY_DIR/debug.keystore" \
    :app:clean :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
mkdir -p "$ROOT/app/build/lab"
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$ROOT/app/build/lab/band7-basic-reader-debug.apk"
# Repeat with the same temporary signing identity and verified cached inputs.
"$GRADLE_BIN" --offline --no-daemon --dependency-verification strict --rerun-tasks \
    -Pband7DebugKeystore="$KEY_DIR/debug.keystore" :app:assembleDebug
cmp "$ROOT/app/build/lab/band7-basic-reader-debug.apk" "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
sha256sum "$ROOT/app/build/lab/band7-basic-reader-debug.apk"
echo 'PASS repeated APK bytes with same temporary key; key removed on exit. ART/BLE NOT_RUN.'
