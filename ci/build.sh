#!/bin/sh
# No signing, key generation, checksum enrollment or credential access.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
GRADLE_BIN=${GRADLE_BIN:-"$ROOT/android-app/gradlew"}
: "${ANDROID_HOME:?Set Android SDK}"
: "${ANDROID_NDK_HOME:?Set NDK 27.0.12077973}"
test "$(rustc --version | cut -d' ' -f2)" = "$(sed -n 's/^channel = "\(.*\)"$/\1/p' rust-toolchain.toml)"
"$GRADLE_BIN" --version | sed -n '/^Gradle /p' | grep -qx 'Gradle 8.11.1'
test -s android-app/gradle/verification-metadata.xml
node scripts/check-source.mjs
mkdir -p artifacts/ci
python3 ci/version.py > artifacts/ci/version.json
CODE=$(python3 -c 'import json;print(json.load(open("artifacts/ci/version.json"))["versionCode"])')
NAME=$(python3 -c 'import json;print(json.load(open("artifacts/ci/version.json"))["versionName"])')
# Network preparation is explicit; all later compilation is locked/offline.
if test "${CI_PREPARE_NETWORK:-0}" = 1; then
    cargo fetch --locked
fi
python3 scripts/rust-licenses.py --check
cargo test --locked --offline
cargo fmt --all -- --check
cargo clippy --locked --offline --all-targets -- -D warnings
sh android-bridge/test.sh
python3 -m unittest discover -s miniapp/packaging
node --test miniapp/tests/page.test.mjs miniapp/tests/vm.test.mjs
sh android-app/build-native.sh
if test "${CI_PREPARE_NETWORK:-0}" = 1; then
    "$GRADLE_BIN" --project-dir android-app --no-daemon --dependency-verification strict \
        -PcascaVersionCode="$CODE" -PcascaVersionName="$NAME" \
        :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
fi
"$GRADLE_BIN" --project-dir android-app --offline --no-daemon --dependency-verification strict \
    -PcascaVersionCode="$CODE" -PcascaVersionName="$NAME" \
    :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
APK=android-app/app/build/outputs/apk/release/app-release-unsigned.apk
cp "$APK" artifacts/ci/casca-de-bala-arm64-unsigned.apk
"$GRADLE_BIN" --project-dir android-app --offline --no-daemon --dependency-verification strict \
    --rerun-tasks -PcascaVersionCode="$CODE" -PcascaVersionName="$NAME" :app:assembleRelease
cmp "$APK" artifacts/ci/casca-de-bala-arm64-unsigned.apk
python3 ci/verify.py artifacts/ci/casca-de-bala-arm64-unsigned.apk
# Tracked source only, with exact version inputs; never caches or signing files.
git archive --format=tar --prefix=casca-de-bala/ HEAD > artifacts/ci/source.tar
EPOCH=$(git show -s --format=%ct HEAD)
tar --append --file=artifacts/ci/source.tar --mtime="@$EPOCH" --owner=0 --group=0 \
    --numeric-owner --transform='s,^,casca-de-bala/,' artifacts/ci/version.json
gzip -n < artifacts/ci/source.tar > artifacts/ci/casca-de-bala-source.tar.gz
rm artifacts/ci/source.tar
(cd artifacts/ci && sha256sum casca-de-bala-arm64-unsigned.apk \
    casca-de-bala-source.tar.gz version.json > SHA256SUMS)
echo 'PASS host/build/static/repeated unsigned APK. NOT_RUN ART/BLE/hardware/signing.'
