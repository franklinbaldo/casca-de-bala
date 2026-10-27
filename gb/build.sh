#!/bin/sh
# Builds the Casca flavor of Gadgetbridge: upstream at gb/UPSTREAM, plus
# gb/patches (minimal upstream edits), plus gb/overlay (the flavor's own
# sources), plus libcasca_mcp.so from crates/casca-mcp-jni.
#   ANDROID_HOME / ANDROID_NDK_HOME: SDK and NDK 27.0.12077973
#   GB_TASK: Gradle task (default assembleCascaDebug)
#   GB_GRADLE_ARGS: extra Gradle arguments (CI passes -PcascaVersionCode/-PcascaVersionName)
set -eu
GB=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(dirname "$GB")
WORK="$GB/work"
UPSTREAM=$(cat "$GB/UPSTREAM")
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 27.0.12077973}"

# 1. Upstream at the pinned commit, reset to pristine on every run (build caches kept).
if [ ! -d "$WORK/.git" ]; then
    git init -q "$WORK"
    git -C "$WORK" remote add origin https://codeberg.org/Freeyourgadget/Gadgetbridge.git
fi
if [ "$(git -C "$WORK" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$UPSTREAM" ]; then
    git -C "$WORK" fetch -q --depth 1 origin "$UPSTREAM"
    git -C "$WORK" checkout -q --force "$UPSTREAM"
fi
git -C "$WORK" reset -q --hard "$UPSTREAM"
git -C "$WORK" clean -qfd -e build -e .gradle -e local.properties

# 2. Minimal upstream edits, then the flavor's own sources.
for patch in "$GB"/patches/*.patch; do git -C "$WORK" apply --whitespace=nowarn "$patch"; done
cp -R "$GB/overlay/." "$WORK/"
# Legal texts from their single source in this repository, as the Casca app shipped them.
LEGAL="$WORK/app/src/casca/assets"
mkdir -p "$LEGAL"
cp "$ROOT/NOTICE" "$LEGAL/NOTICE.txt"
cp "$ROOT/PROVENANCE.md" "$LEGAL/PROVENANCE.txt"
cp -R "$ROOT/docs/licenses/." "$LEGAL/"
cp -R "$ROOT/third_party/." "$LEGAL/"

# 3. The MCP hub, without the BLE lab: casca-mcp through its JNI entry.
TOOLS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$TOOLS/aarch64-linux-android31-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLS/aarch64-linux-android31-clang"
export CC_aarch64_linux_android="$TOOLS/aarch64-linux-android31-clang"
export AR_aarch64_linux_android="$TOOLS/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
export CARGO_TARGET_DIR="$ROOT/target/android"
cargo build --locked --release --target aarch64-linux-android --manifest-path "$ROOT/Cargo.toml" -p casca-mcp-jni
JNI="$WORK/app/src/casca/jniLibs/arm64-v8a"
mkdir -p "$JNI"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libcasca_mcp.so" "$JNI/"
"$TOOLS/llvm-nm" -D --defined-only "$JNI/libcasca_mcp.so" | grep -q Java_org_workspace_casca_McpNative_control

# 4. Gadgetbridge's own build.
cd "$WORK"
# shellcheck disable=SC2086 # GB_GRADLE_ARGS is a word list on purpose
./gradlew --no-daemon --max-workers="${GB_WORKERS:-2}" ${GB_GRADLE_ARGS:-} "${GB_TASK:-assembleCascaDebug}"
ls -la app/build/outputs/apk/casca/*/
