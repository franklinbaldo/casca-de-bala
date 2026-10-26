> Installed Activity follow-up (partial/failing gate): [Activity report](activity-gate-0.6.md). Earlier Activity NOT_RUN below describes the JNI-only stage.

# Real JNI/Rust on Android ART (x86_64 fixture)

This increment validates existing functionality, not a new capability release.
No APK/signing key, Bluetooth call, hardware, real health data or Band credential.

## Build and artifact boundaries

Existing official NDK27.0.12077973 and Rust1.99.1 were used. Rust's official
`x86_64-linux-android` standard target was installed through rustup; application
crates stayed locked/offline. `android-bridge/build_android_x86_64_fixture.sh`
produces fixture-only ELF64 x86_64 JNI/core libraries at API31 and16KiB page
alignment, using official NDK jni.h (not the host JNI subset). They are deliberately
not copied over the app's ARM64 jniLibs.

`android-bridge/build_art_jni_fixture.sh` produces an unsigned DEX jar with the
existing bridge/auth/framing tests plus the Android-only scheduling fixture.
BC1.86 remains pinned/hash-checked. No reference compatibility shims or APK signing
are involved. Exact source/dependency licenses accompany the private artifacts.

Reproduce builds with existing approved tools, BC_JAR, ANDROID_HOME and
ANDROID_NDK_HOME set, then supply separate fixture output directories to the two
scripts. These scripts never install SDK components, start ADB or create identities.

## Android runtime result

Official emulator37.2.12, platform-tools37.0.1, AOSP Android12/API31 x86_64,
software emulation without KVM,2 cores/2048MiB. The previously approved temporary
emulator-only ADB identity was confined to a fresh child-process home. Agent HOME,
host permissions and authentication stayed unchanged. Dedicated localhost server,
explicit emulator target and nonmatching USB filter prevented user-device access.
The dedicated processes stopped and temporary identity directory was deleted.

Five suites passed under `dalvikvm -Xcheck:jni` with the compiled native libraries:

- CryptoBridgeSmoke: JNI reply shape, bounds/endpoint/null/truncation and crypto.
- AuthLifecycleTest: native handles, order, early/stale callbacks, cancellation,
  background, failure, limits and owner cleanup, with synthetic clock/transport.
- FramingTransportTest: actual JNI/Rust framing, ACK pump and encrypted AUTH peer
  fixture through the real decoder; port/peer remain simulated.
- BridgeSmoke: JNI -> C ABI -> Rust -> real SQLite, UTF-8, reopen/deduplication,
  concurrent replay, conflict error handling, cursor/coverage, worker close/reopen/errors.
  Records are strictly synthetic; this is not a health collection test.
- GattBridgeSmoke: synthetic GATT envelopes through JNI/Rust; no GATT IO.

Each command used the dedicated emulator, native-library path and fixture jar,
with `java.io.tmpdir=/data/local/tmp` for synthetic SQLite files. Native/core/DEX
SHA256 hashes were checked on the emulator before execution. Every suite exited0.
Old test stdout retains host/JVM/NoART wording; commands and runtime identity,
not those fixed labels, establish this ART execution scope.

## Android scheduling/lifecycle policy

`AndroidOwnerDispatchFixture` passed under `app_process -Xcheck:jni`. This provides
actual Android framework JNI registration for HandlerThread, Looper and SystemClock,
while loading our actual JNI/Rust owner. It verifies posted stale-generation
callbacks, timer removal, repeated cleanup/cancel and background policy calls.
The timeout uses an explicitly injected clock offset; it does not measure15s of
wall time or Android scheduling accuracy.

Background/cancel are explicit fixture calls. **No Activity is installed**, so
onStop/onDestroy, rotation, permissions/settings, main UI Looper and actual GATT
callbacks are NOT_RUN. HandlerThread is real Android scheduling, not a fake Activity
lifecycle test. Framework boot-complete was still empty and full UI boot is not
claimed. ART/native fixtures can run before full framework/UI completion.

## Gates before a pilot APK

1. Review this result, including unchanged RX CRC/replay limits.
2. Separately authorize a test signature if an installed APK is needed; then test
   real Activity onStop/rotation/re-entry, permission states and GATT cancellation
   in the emulator. No signing identity was created in this round.
3. Validate the intended ARM64 build/runtime separately; x86_64 results do not
   prove ARM64 ABI/runtime behavior.
4. Local Band secret entry/storage, real authentication and the nonmutating service
   query remain separately reviewed/authorized physical gates. No clock/profile,
   firmware, health collection or protected-operation dispatch is implied here.

Previous host69-test receipts are historical and were not relabelled as ART tests.
The published0.5 release and separate Pillow patch remain untouched.
