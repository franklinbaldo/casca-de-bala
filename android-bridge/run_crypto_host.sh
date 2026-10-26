#!/bin/sh
# Explicit already provisioned official artifact only. No implicit download.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${BC_JAR:?Set BC_JAR to verified official bcprov-jdk18on1.86 jar}"
test "$(sha256sum "$BC_JAR" | cut -d' ' -f1)" = 2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f
BUILD=$(mktemp -d /tmp/casca-bc186-host-XXXXXX)
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
java -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all -Werror -cp "$BC_JAR" -d "$BUILD/classes" "$ROOT"/src/org/workspace/band7/NativeBridge.java "$ROOT"/src/org/workspace/band7/LocalCredential.java "$ROOT"/crypto-src/org/workspace/band7/*.java "$ROOT"/crypto-tests/org/workspace/band7/*.java
java -cp "$BUILD/classes:$BC_JAR" org.workspace.band7.HuamiCryptoTest "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt"

CRATE=$(dirname "$ROOT")
cargo build --locked --offline --manifest-path "$CRATE/Cargo.toml"
cc -std=c11 -Wall -Wextra -Werror -fPIC -shared -DB7_HOST_JNI_SUBSET "$ROOT/band7_jni.c" -L "$CRATE/target/debug" -lband7_terminal -Wl,-rpath,"$CRATE/target/debug" -o "$BUILD/libband7_jni.so"
java -Xcheck:jni -Djava.library.path="$BUILD" -cp "$BUILD/classes:$BC_JAR" org.workspace.band7.CryptoBridgeSmoke "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt"

java -Xcheck:jni -Djava.library.path="$BUILD" -cp "$BUILD/classes:$BC_JAR" org.workspace.band7.AuthLifecycleTest "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt"
java -Xcheck:jni -Djava.library.path="$BUILD" -cp "$BUILD/classes:$BC_JAR" org.workspace.band7.FramingTransportTest "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt"
java -cp "$BUILD/classes:$BC_JAR" org.workspace.band7.EncryptedFramesTest "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt"
java -Xcheck:jni -Djava.library.path="$BUILD" -cp "$BUILD/classes:$BC_JAR" org.workspace.band7.CancellationContractTest "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt"
java -Xcheck:jni -Djava.library.path="$BUILD" -cp "$BUILD/classes:$BC_JAR" org.workspace.band7.BatteryQueryTest "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt"
