#!/bin/sh
# Read-only prerequisite audit, never installs or accepts terms.
set -u
missing=0
check(){ if test -e "$2"; then echo "FOUND $1"; else echo "MISSING $1"; missing=1; fi; }
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
for tool in java cargo rustc gradle; do
    if command -v "$tool" >/dev/null 2>&1; then echo "FOUND $tool"; else echo "MISSING $tool"; missing=1; fi
done
SDK=${ANDROID_HOME:-/nonexistent-android-sdk}
NDK=${ANDROID_NDK_HOME:-/nonexistent-android-ndk}
check android-35 "$SDK/platforms/android-35/android.jar"
check build-tools-35.0.0 "$SDK/build-tools/35.0.0/apksigner"
check NDK-27.0.12077973 "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang"
if command -v rustc >/dev/null 2>&1; then
    TARGET_LIB=$(rustc --print target-libdir --target aarch64-linux-android)
    check Rust-Android-std "$TARGET_LIB"
fi
check native-Rust "$ROOT/app/src/main/jniLibs/arm64-v8a/libband7_terminal.so"
check native-JNI "$ROOT/app/src/main/jniLibs/arm64-v8a/libband7_jni.so"
echo 'Also required: authorized Gradle 8.11.1 + AGP 8.9.2 caches and an authorized signing key; not downloaded/generated here.'
exit "$missing"
