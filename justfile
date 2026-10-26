# Casca de Bala: one entry point for local work and CI. `just` lists recipes.
set shell := ["sh", "-eu", "-c"]
gradle := "android-app/gradlew --project-dir android-app"

default:
    @just --list

# Rust, JNI bridge, miniapp and CI-contract tests (no Android SDK needed)
test:
    cargo test --locked --offline
    sh android-bridge/test.sh
    node --test miniapp/tests/page.test.mjs miniapp/tests/vm.test.mjs
    python3 -m unittest discover -s miniapp/packaging
    python3 -m unittest discover -s ci -p 'test_*.py'

# Formatting, clippy and tracked-file guard
lint:
    cargo fmt --all -- --check
    cargo clippy --locked --offline --all-targets -- -D warnings
    node scripts/check-source.mjs

fmt:
    cargo fmt --all

# Rust + JNI shim for arm64 Android (needs ANDROID_NDK_HOME)
native:
    sh android-app/build-native.sh

# Unsigned release APK + lint + JVM unit tests (needs ANDROID_HOME)
apk: native
    {{gradle}} --dependency-verification strict :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest

# Debug APK signed with a throwaway key, installed on the connected phone
install: native
    {{gradle}} --dependency-verification strict :app:installDebug

# Phone MCP server on this machine (synthetic band snapshot): `y <id>` approves
mcp-dev port="8765":
    cargo run --offline -q -p casca-mcp --example serve -- {{port}}

# Exactly what CI runs: tests, repeated unsigned APK, source tarball, SHA256SUMS
ci:
    sh ci/build.sh
