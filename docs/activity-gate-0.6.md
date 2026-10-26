# Installed Activity gate: PARTIAL, not ready for physical AUTH

This round uses a temporary signed **testOnly** x86_64 APK exclusively in the local
Android12/API31 emulator. It is not a release, phone installer or Band test.
Latest exact artifact/result are recorded in the private preparation receipt.

## What was built and isolated

A conditional `band7FixtureAbi=x86_64` profile supplies the previously validated
x86_64 native libraries, separate application ID
`org.workspace.band7.basicreader.activitytest`, and self-instrumentation inside the
same APK. It refuses release tasks or an absent explicit fixture keystore.
`src/activityFixture` contains its manifest/test class; normal debug/release do not
include that instrumentation manifest. Normal debug compile/lint and a merged-
manifest isolation check passed. Normal ARM64 inputs and published0.5 are untouched.

The fixture profile builds offline with strict dependency verification. A private
RSA2048 signer with2-day certificate was generated for this test only. Password
entered build tools via a private child environment, never logs or source. APK v2
signature and16KiB zip alignment passed. No release signing identity was created,
replaced or reused. Signer keystore/password and the temporary ADB home were removed.
No private key/password is included in the preparation artifact.

AuthFixtureActivity uses public OpenSSL test material, a synthetic port/peer and
actual BC/JNI/Rust state/framing. It never constructs the GATT adapter. Added debug-
only view IDs/state and a labelled no-response checkbox permit repeatable timeout
checks. PEER_ACCEPTED remains explicitly simulated, not Band authentication.

## Results must be distinguished by build

The latest APK SHA256 is
`7be7ddcb973b06dc7e0767ffaa63b7d21c321717acc2817c10c06a9e5fc701d8`
(9359383 bytes). On this exact build:

- PASS: entry from actual home UI to fixture, explicit start, real framework rotation
  with Activity recreation/onStop/onDestroy, old owner closure and no automatic
  session start in the replacement Activity; HOME and foreground return without
  automatic session restart.
- FAIL: latest Activity suite asserted CANCELLED during repeated start/cancel and
  got a different status. The assertion did not record before/after status, so
  app defect versus a previously terminal/expired session is not isolated.
- PASS: Android OS permission dialog denial, return to app with CONNECT denied.
- PASS: CONNECT granted/SCAN denied on the bonded route, app window focused with
  no additional permission dialog. Only the emulator's virtual adapter was used;
  no selection, connection, scan or physical Bluetooth access.
- NOT_REACHED on this final Activity run: subsequent timeout/retry section.

The entire Activity suite (including repeated cancel/late callbacks and real5s
write-callback timeout/retry) passed on earlier test APKs, including SHA256
`a36c77a9b3cd8eeee4e9fe38a620765e146fcf06cc0a00ed35358177186b00ee`
and `672a915a023e1ff9a10f7902d7cc2a119a20efcd980fdb67f1d1e03af4a78547`.
Those historical passes are not attributed to the latest failed suite. Test clicks
use real View listeners/visibility and framework lifecycle; they are instrumented
programmatic clicks, not manual touch/TalkBack/visual acceptance. HOME is a system
key event. Transport remains simulated throughout.

## Corrected harness defects and environmental failures

The harness initially clicked before scroll/layout settled, treated CompoundButton
performClick's boolean as whether a checkbox toggled, and queried changing
accessibility roots twice while a permission window disappeared. It now waits for
laid-out visible widgets, verifies checkbox state directly, and checks the actual
Activity window focus for permission return/no-dialog assertions. No production
protocol behavior was changed to make tests pass.

The emulator's first20-minute process guard required a restart with the SAME
existing ADB identity, not a new key. ADB became online before PackageManager; an
uninstall attempt failed with `Can't find service: package` before execution.
A bounded service-readiness check was added; actual UI boot took about331s in
software emulation.

A later run timed out at240s while the emulator SystemServer crashed with
`IllegalStateException: Lost network stack` in ConnectivityModuleConnector.
No app process remained; the system recovered to the launcher. This is a logged
system-process failure, not proof of an APK exception or a diagnosed root cause.
No network/authentication/security setting was changed. One re-execution after
service recovery produced the partial final results above. No blind rerun was
used to hide the cancellation failure.

## Cleanup and next diagnostic

Own server/emulator processes are stopped; only defunct OS entries may remain.
Signer and ADB private directories were deleted. Final uninstall failed because
the emulator had already exited by its process guard. The testOnly package remains
in the offline private AVD data; it was not installed on any user phone/Band. A
future local test update may first need to uninstall only this `.activitytest`
package; it must never remove Zepp Life, pairing or the user's normal app.

Before another pilot, record a nonsensitive atomic snapshot on the UI thread just
before/after each cancel: generation, lifecycle/resumed state, owner status/failure,
native status, wire reason and monotonic time. The owner intentionally preserves
an already finalized failure/background status on later cancel; the current test
cannot establish whether that was the case. Do not weaken CANCELLED assertions
without observing that precondition. Re-run this bounded case on a stable emulator.

ARM64 ART, real GATT callbacks, local Band-key entry, actual handshake and the
explicit nonmutating service query remain separate gates. Permanent-denial/settings,
all API versions, manual accessibility/visual QA and real physical timing are not
validated here. No credentials, health, firmware, pairing or protected operations
were accessed. No APK or source was published; this artifact is private test evidence.
# Atualização posterior

Este documento preserva o gate histórico. O diagnóstico focalizado posterior
resolveu a expectativa de cancelamento: ver [cancel-resolution-0.6.md](cancel-resolution-0.6.md).
