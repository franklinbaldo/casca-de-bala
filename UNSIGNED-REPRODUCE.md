# Unsigned ARM64 candidate — not an installer

Same functional source as the v12 emulator preparation. The init recipe overrides
only candidate metadata to versionCode6/versionName0.6-pilot-unsigned and refuses
non-null release signing config or signing/key tasks. No project keystore added.

Approved/reused tools: Java21, Gradle8.11.1, AGP8.9.2, SDK35/build-tools35.0.0,
NDK27.0.12077973, Rust1.99.1 target aarch64-linux-android, verified BC1.86.
Use the pinned dependencies already in the offline cache. From source root:

    sh android-app/build-native.sh
    "$GRADLE_BIN" --offline --no-daemon --dependency-verification=strict \
      -Pband7BcRepository="$BAND7_BC_REPOSITORY" \
      -I unsigned-build.init.gradle -p android-app \
      :app:assembleRelease :app:lintRelease

Set JAVA_HOME, ANDROID_HOME, ANDROID_NDK_HOME, CARGO_HOME/RUSTUP_HOME/PATH,
GRADLE_USER_HOME and the two variables above to the equivalent approved tool/cache
paths. The mirror is the previously verified local BC1.86 Maven artifact. Do not
run build-apk.sh for this gate: that laboratory script generates a temporary key.
Output: android-app/app/build/outputs/apk/release/app-release-unsigned.apk.

Static validation: aapt/apkanalyzer manifest and defined DEX classes, ZIP CRC,
zipalign -c -P16, ELF AArch64 exports/16KiB LOAD alignment, packaged natives equal
NDK llvm-strip --strip-unneeded output. apksigner verify must reject the unsigned
APK (expected Missing META-INF/MANIFEST.MF), not report a signature PASS.

No installation, ART ARM64, hardware, public release or key operations. Product UI
copy still has laboratory-version legacy text and needs final release polish;
version metadata alone is not a declaration that0.6 is ready to distribute.
