#!/bin/sh
# No signing, key generation, checksum enrollment or credential access.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
node scripts/check-source.mjs
mkdir -p artifacts/ci
python3 ci/version.py > artifacts/ci/version.json
CODE=$(python3 -c 'import json;print(json.load(open("artifacts/ci/version.json"))["versionCode"])')
NAME=$(python3 -c 'import json;print(json.load(open("artifacts/ci/version.json"))["versionName"])')
# Network preparation is explicit; all later compilation is locked/offline.
if test "${CI_PREPARE_NETWORK:-0}" = 1; then
    cargo fetch --locked
fi
# Fast, pure-JVM JUnit contract checks: independent of Gradle/SDK and APK.
# Network prep is explicit and JAR bytes are SHA-256 pinned.
if test "${CI_PREPARE_NETWORK:-0}" = 1; then
    python3 scripts/test_java_host.py --prepare-network
else
    python3 scripts/test_java_host.py
fi
python3 scripts/rust-licenses.py --check
cargo test --locked --offline
cargo fmt --all -- --check
cargo clippy --locked --offline --all-targets -- -D warnings
sh android-bridge/test.sh
python3 -m unittest discover -s miniapp/packaging
node --test miniapp/tests/page.test.mjs miniapp/tests/vm.test.mjs
# The app is the Casca flavor of Gadgetbridge (gb/): upstream at gb/UPSTREAM,
# gb/patches, gb/overlay and libcasca_mcp.so. Same package and signing key as
# the earlier Casca app, so it installs as an update.
GB_APK=gb/work/app/build/outputs/apk/casca/release/app-casca-release-unsigned.apk
GB_TASK=assembleCascaRelease GB_GRADLE_ARGS="-PcascaVersionCode=$CODE -PcascaVersionName=$NAME" \
    sh gb/build.sh
cp "$GB_APK" artifacts/ci/casca-de-bala-arm64-unsigned.apk
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
echo 'PASS host/build/static unsigned APK (Gadgetbridge flavor). NOT_RUN repeated-build check, ART/BLE/hardware/signing.'
