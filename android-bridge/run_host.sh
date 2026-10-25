#!/bin/sh
set -eu
BRIDGE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CRATE_ROOT=$(dirname "$BRIDGE_ROOT")
# Must use the matching compiler/runtime and existing C/Rust toolchains.
TEST_BUILD=$(mktemp -d /tmp/band7-jni-host-XXXXXX)
trap 'rm -rf "$TEST_BUILD"' EXIT HUP INT TERM
cargo build --offline --manifest-path "$CRATE_ROOT/Cargo.toml"
java -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all -Werror -d "$TEST_BUILD/classes" \
    "$BRIDGE_ROOT"/src/org/workspace/band7/*.java "$BRIDGE_ROOT"/tests/org/workspace/band7/*.java
cc -std=c11 -Wall -Wextra -Werror -fPIC -shared -DB7_HOST_JNI_SUBSET \
    "$BRIDGE_ROOT/band7_jni.c" -L "$CRATE_ROOT/target/debug" -lband7_terminal \
    -Wl,-rpath,"$CRATE_ROOT/target/debug" -o "$TEST_BUILD/libband7_jni.so"
java -Xcheck:jni -Djava.library.path="$TEST_BUILD" -cp "$TEST_BUILD/classes" org.workspace.band7.BridgeSmoke
java -Xcheck:jni -Djava.library.path="$TEST_BUILD" -cp "$TEST_BUILD/classes" org.workspace.band7.GattBridgeSmoke
java -Xcheck:jni -Djava.library.path="$TEST_BUILD" -cp "$TEST_BUILD/classes" org.workspace.band7.BasicReadSessionSmoke
java -cp "$TEST_BUILD/classes" org.workspace.band7.DiscoveryFlowTest
java -cp "$TEST_BUILD/classes" org.workspace.band7.BondedFlowTest

java -Xcheck:jni -Djava.library.path="$TEST_BUILD" -cp "$TEST_BUILD/classes" org.workspace.band7.BasicSnapshotSmoke "$CRATE_ROOT/target/debug/band7-terminal"

java -cp "$TEST_BUILD/classes" org.workspace.band7.RadarSessionTest
