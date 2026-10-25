#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD=$(mktemp -d /tmp/band7-worker-host-XXXXXX)
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
# Java source and state-machine regression; no Rust, JNI, Android SDK or BLE.
java -m jdk.compiler/com.sun.tools.javac.Main \
    -Xlint:all,-options -Werror -d "$BUILD/classes" \
    "$ROOT"/src/org/workspace/band7/*.java \
    "$ROOT"/tests/org/workspace/band7/ActivityWorkerTest.java
java -cp "$BUILD/classes" org.workspace.band7.ActivityWorkerTest
