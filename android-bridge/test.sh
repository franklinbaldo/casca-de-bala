#!/bin/sh
# Every tests/**/*.java class runs here; add a file and it is picked up.
# Host JVM + real Rust cdylib + C JNI shim; no Android SDK, BLE or device.
set -eu
BRIDGE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CRATE_ROOT=$(dirname "$BRIDGE_ROOT")
BUILD=$(mktemp -d "${TMPDIR:-/tmp}/casca-bridge-XXXXXX")
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
cargo build --locked --offline --manifest-path "$CRATE_ROOT/Cargo.toml"
TESTS=$(find "$BRIDGE_ROOT/tests" -name '*.java' | sort)
# shellcheck disable=SC2086
java -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all,-options -Werror -d "$BUILD/classes" \
    $(find "$BRIDGE_ROOT/src" -name '*.java' | sort) $TESTS
cc -std=c11 -Wall -Wextra -Werror -fPIC -shared -DB7_HOST_JNI_SUBSET \
    "$BRIDGE_ROOT/band7_jni.c" -L "$CRATE_ROOT/target/debug" -lband7_terminal \
    -Wl,-rpath,"$CRATE_ROOT/target/debug" -o "$BUILD/libband7_jni.so"
COUNT=0
for file in $TESTS; do
    class=$(echo "${file#"$BRIDGE_ROOT/tests/"}" | sed 's,\.java$,,; s,/,.,g')
    # Harnesses that need the CLI (BasicSnapshotSmoke) read it from args[0].
    java -Xcheck:jni -Djava.library.path="$BUILD" -cp "$BUILD/classes" "$class" \
        "$CRATE_ROOT/target/debug/band7-terminal"
    COUNT=$((COUNT + 1))
done
echo "PASS $COUNT bridge harnesses"
