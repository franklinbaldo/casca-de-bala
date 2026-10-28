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
# Java-only checks may reuse an already built JNI library.
if [ "${GB_SKIP_RUST:-0}" != 1 ]; then
    : "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 27.0.12077973}"
fi

# 1–2. Materialize pinned Gadgetbridge and Casca overlays only when inputs change.
# A clean source snapshot is essential for reproducible release builds; the
# stamp is intentionally invalidated by a different upstream HEAD or source.
prep_hash() {
    (cd "$ROOT" && find gb/UPSTREAM gb/patches gb/overlay NOTICE PROVENANCE.md \
        docs/licenses third_party -type f -print0 | LC_ALL=C sort -z | \
        xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
}
PREP_HASH=$(prep_hash)
STAMP="$WORK/.casca-prepared-sha256"
if [ ! -d "$WORK/.git" ]; then
    git init -q "$WORK"
    git -C "$WORK" remote add origin https://codeberg.org/Freeyourgadget/Gadgetbridge.git
fi
CURRENT=$(git -C "$WORK" rev-parse -q --verify HEAD 2>/dev/null || true)
if [ "${GB_FORCE_PREPARE:-0}" = 1 ] || [ "$CURRENT" != "$UPSTREAM" ] || \
    [ "$(cat "$STAMP" 2>/dev/null || true)" != "$PREP_HASH" ]; then
    if [ "$CURRENT" != "$UPSTREAM" ]; then
        git -C "$WORK" fetch -q --depth 1 origin "$UPSTREAM"
        git -C "$WORK" checkout -q --force "$UPSTREAM"
    fi
    git -C "$WORK" reset -q --hard "$UPSTREAM"
    git -C "$WORK" clean -qfd -e build -e .gradle -e local.properties
    for patch in "$GB"/patches/*.patch; do git -C "$WORK" apply --whitespace=nowarn "$patch"; done
    cp -R "$GB/overlay/." "$WORK/"
    LEGAL="$WORK/app/src/casca/assets"
    mkdir -p "$LEGAL"
    cp "$ROOT/NOTICE" "$LEGAL/NOTICE.txt"
    cp "$ROOT/PROVENANCE.md" "$LEGAL/PROVENANCE.txt"
    cp -R "$ROOT/docs/licenses/." "$LEGAL/"
    cp -R "$ROOT/third_party/." "$LEGAL/"
    printf '%s\n' "$PREP_HASH" > "$STAMP"
else
    echo 'Casca inputs unchanged: reusing prepared Gadgetbridge worktree and Gradle cache.'
fi

# 3. The MCP hub, without the BLE lab: casca-mcp through its JNI entry.
# Source fingerprint prevents Java-only builds from silently using stale Rust JNI.
rust_hash() {
    # Hash only inputs that can change libcasca_mcp.so. A change to Rust test
    # fixtures or unrelated crates must not invalidate the Java-only cache.
    # The script walks local path dependencies and fails closed if incomplete.
    python3 "$ROOT/scripts/jni_fingerprint.py"
}
RUST_HASH=$(rust_hash)
export CARGO_TARGET_DIR="$ROOT/target/android"
CACHED_JNI="$CARGO_TARGET_DIR/aarch64-linux-android/release/libcasca_mcp.so"
RUST_STAMP="$CARGO_TARGET_DIR/.casca-jni-rust-sha256"
JNI="$WORK/app/src/casca/jniLibs/arm64-v8a"
mkdir -p "$JNI"
if [ "${GB_SKIP_RUST:-0}" = 1 ]; then
    if [ ! -s "$CACHED_JNI" ] || [ "$(cat "$RUST_STAMP" 2>/dev/null || true)" != "$RUST_HASH" ]; then
        echo 'GB_SKIP_RUST=1 requires a previously built JNI matching the current Rust sources.' >&2
        exit 1
    fi
    echo 'Reusing matching JNI library (Java-only check; not release validation).'
else
    TOOLS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
    test -x "$TOOLS/aarch64-linux-android31-clang"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLS/aarch64-linux-android31-clang"
    export CC_aarch64_linux_android="$TOOLS/aarch64-linux-android31-clang"
    export AR_aarch64_linux_android="$TOOLS/llvm-ar"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
    cargo build --locked --release --target aarch64-linux-android --manifest-path "$ROOT/Cargo.toml" -p casca-mcp-jni
    "$TOOLS/llvm-nm" -D --defined-only "$CACHED_JNI" | grep -q Java_org_workspace_casca_McpNative_control
    printf '%s\n' "$RUST_HASH" > "$RUST_STAMP"
fi
cp "$CACHED_JNI" "$JNI/"

# 4. Gadgetbridge's own build.
cd "$WORK"
# shellcheck disable=SC2086 # GB_GRADLE_ARGS is a word list on purpose
if [ -n "${GB_TEST_FILTER:-}" ]; then
    case "${GB_TASK:-assembleCascaDebug}" in
        *UnitTest) ;;
        *) echo 'GB_TEST_FILTER requires an Android UnitTest task.' >&2; exit 2 ;;
    esac
    ./gradlew --no-daemon --max-workers="${GB_WORKERS:-2}" ${GB_GRADLE_ARGS:-} "${GB_TASK}" --tests "${GB_TEST_FILTER}"
else
    ./gradlew --no-daemon --max-workers="${GB_WORKERS:-2}" ${GB_GRADLE_ARGS:-} "${GB_TASK:-assembleCascaDebug}"
fi
case "${GB_TASK:-assembleCascaDebug}" in
    *assemble*) ls -la app/build/outputs/apk/casca/*/ ;;
esac
