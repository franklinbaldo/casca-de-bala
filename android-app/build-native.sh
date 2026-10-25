#!/bin/sh
# Uses ONLY an already provisioned NDK and Rust Android target. No downloads.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CRATE=$(dirname "$ROOT")
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to approved NDK 27.0.12077973}"
test -f "$ANDROID_NDK_HOME/source.properties"
NDK_VERSION=$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties")
test "$NDK_VERSION" = '27.0.12077973' || { echo 'NDK version mismatch' >&2; exit 2; }
TOOLS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$TOOLS/aarch64-linux-android31-clang"
command -v cargo >/dev/null
command -v rustc >/dev/null
TARGET_LIB=$(rustc --print target-libdir --target aarch64-linux-android)
test -d "$TARGET_LIB" || { echo 'Missing Rust Android std; no target installed automatically' >&2; exit 2; }
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLS/aarch64-linux-android31-clang"
export CC_aarch64_linux_android="$TOOLS/aarch64-linux-android31-clang"
export AR_aarch64_linux_android="$TOOLS/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
# Explicit output location avoids accidentally copying a host .so or another target.
export CARGO_TARGET_DIR="$CRATE/target/android"
cargo build --locked --offline --release --target aarch64-linux-android --manifest-path "$CRATE/Cargo.toml"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libband7_terminal.so" "$DEST/"
"$TOOLS/aarch64-linux-android31-clang" -std=c11 -Wall -Wextra -Werror -fPIC -shared \
    "$CRATE/android-bridge/band7_jni.c" -L "$DEST" -lband7_terminal -Wl,-z,max-page-size=16384 \
    -o "$DEST/libband7_jni.so"
"$TOOLS/llvm-readelf" -h "$DEST/libband7_terminal.so" "$DEST/libband7_jni.so"
echo 'Native arm64 candidates only; APK/ART/device remain unvalidated.'
