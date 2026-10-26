#!/bin/sh
# Existing approved NDK/target only. No downloads, APK copying or signing.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CRATE=$(dirname "$ROOT")
: "${ANDROID_NDK_HOME:?Approved NDK required}"
test "$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties")" = 27.0.12077973
TOOLS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
TARGET_LIB=$(rustc --print target-libdir --target x86_64-linux-android)
test -d "$TARGET_LIB"
DEST=${1:?Supply dedicated fixture output directory}
mkdir -p "$DEST"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLS/x86_64-linux-android31-clang"
export CC_x86_64_linux_android="$TOOLS/x86_64-linux-android31-clang"
export AR_x86_64_linux_android="$TOOLS/llvm-ar"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
export CARGO_TARGET_DIR="$CRATE/target/android-x86_64-fixture"
cargo build --locked --offline --release --target x86_64-linux-android --manifest-path "$CRATE/Cargo.toml"
cp "$CARGO_TARGET_DIR/x86_64-linux-android/release/libband7_terminal.so" "$DEST/"
"$TOOLS/x86_64-linux-android31-clang" -std=c11 -Wall -Wextra -Werror -fPIC -shared "$ROOT/band7_jni.c" -L "$DEST" -lband7_terminal -Wl,-z,max-page-size=16384 -o "$DEST/libband7_jni.so"
"$TOOLS/llvm-readelf" -h -d "$DEST/libband7_terminal.so" "$DEST/libband7_jni.so"
sha256sum "$DEST/libband7_terminal.so" "$DEST/libband7_jni.so"
