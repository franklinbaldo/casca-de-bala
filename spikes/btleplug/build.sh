#!/bin/sh
# Builds the spike APK (debug key). Needs ANDROID_HOME and ANDROID_NDK_HOME.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(dirname "$(dirname "$HERE")")
: "${ANDROID_HOME:?}" "${ANDROID_NDK_HOME:?}"
TOOLS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLS/aarch64-linux-android31-clang"
export CC_aarch64_linux_android="$TOOLS/aarch64-linux-android31-clang"
export AR_aarch64_linux_android="$TOOLS/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
cargo build --release --target aarch64-linux-android --manifest-path "$HERE/rust/Cargo.toml"
mkdir -p "$HERE/android/app/src/main/jniLibs/arm64-v8a"
cp "$HERE/rust/target/aarch64-linux-android/release/libcasca_spike.so" "$HERE/android/app/src/main/jniLibs/arm64-v8a/"
# btleplug's Java sources live inside the crate; point Gradle at them.
JAVA=$(cargo metadata --format-version 1 --manifest-path "$HERE/rust/Cargo.toml" | python3 -c '
import json,sys,os
m=json.load(sys.stdin)
p=next(p for p in m["packages"] if p["name"]=="btleplug")
print(os.path.join(os.path.dirname(p["manifest_path"]),"src/droidplug/java/src/main/java"))')
"$ROOT/android-app/gradlew" --project-dir "$HERE/android" --no-daemon -PbtleplugJava="$JAVA" :app:assembleDebug
ls -l "$HERE/android/app/build/outputs/apk/debug/app-debug.apk"
