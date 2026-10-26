#!/bin/sh
# No downloads, Bluetooth, credentials or generated keys. Test-only reference.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${BC_JAR:?Supply verified official BC1.86 jar}"
test "$(sha256sum "$BC_JAR" | cut -d' ' -f1)" = 2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f
BUILD=$(mktemp -d /tmp/casca-encrypted-reference-XXXXXX)
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
find "$ROOT/crypto-reference" "$ROOT/crypto-src" "$ROOT/crypto-tests" -name '*.java' > "$BUILD/sources"
printf '%s\n' "$ROOT/src/org/workspace/band7/NativeBridge.java" >> "$BUILD/sources"
printf '%s\n' "$ROOT/src/org/workspace/band7/LocalCredential.java" >> "$BUILD/sources"
java -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all -Werror -cp "$BC_JAR" -d "$BUILD/classes" @"$BUILD/sources"
java -cp "$BUILD/classes:$BC_JAR" org.workspace.band7.EncryptedReferenceTest "$ROOT/crypto-tests/openssl-b163-public-test-vector.txt"
