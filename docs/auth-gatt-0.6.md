> Historical plaintext-stage receipt. Current encrypted-RX implementation and ART findings: [encrypted transport](encrypted-transport-0.6.md). Earlier NOT_RUN/PASS claims below describe that stage.

# AUTH GATT pilot: implementation boundary

This is local preparation after0.5, not a shipped APK or a successful Band session.
No real credentials, Bluetooth IO, signing key, health data or hardware were used.

## Fixed primary sources

Protocol reference: Gadgetbridge revision
`a0948ee1cbc2a870f91d313f8e37df5f524465f7`:

- [Huami2021ChunkedEncoder](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/Huami2021ChunkedEncoder.java): extended plaintext headers, MTU minus3, first header11/continuation5, final flags6, handle/sequence.
- [HuamiSupport](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiSupport.java): ACK `04 00 handle 01 count` on RX, incoming ACK is transport metadata.
- [HuamiService](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiService.java): characteristic16 TX/17 RX. FEE0 and these characteristics were also present in sanitized user discovery; that does not validate AUTH.
- [ZeppOsServicesService](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/services/ZeppOsServicesService.java): endpoint0000, plaintext GET_LIST03, response04/countLE16/endpointLE16/encryptedFlag. Service constructor requests no encryption.

Encoder/decoder provenance remains AGPL-3.0-or-later, community author José Rebelo;
this implementation follows the protocol and adds stricter bounded validation.
Synthetic framing fixtures are not device captures or interoperability vectors.
Published OpenSSL crypto vectors and BC provenance remain in the prior reports.

## Implemented path

`HuamiGattAdapter` is an explicit-start, main-thread, one-shot Android adapter.
It requires a selected, already bonded device and BLUETOOTH_CONNECT. It does not
scan, pair or request permissions. It discovers FEE0, checks characteristic
properties and CCCD, enables RX notifications, then starts the existing owner.
It is not exposed from the normal UI and never receives a credential from chat.
A caller must provide an owned crypto context through a future explicit local flow.

Rust produces AUTH plaintext frames at conservative MTU23; Java serializes one
physical write at a time and queues at most8 ACKs. Incoming fragments use the
existing Rust receiver; incoming peer ACKs never confirm AUTH or complete a local
write. Logical messages/handles remain generation-bound. Encrypted frames stop
with CIPHER_UNSUPPORTED: cipher framing is not silently approximated.

Timer250ms drives checks; setup has15s, physical writes5s, partial reassembly5s,
and the pilot AUTH session15s deadlines. These are lab policy, not hardware facts.
Timeout/cancel/onStop/onDestroy disconnect and close without automatic restart;
late callbacks from previous GATT objects/generations are ignored. Notifications
are limited to512 bytes and16 queued callbacks. Sequence/length conflicts fail.
Android write callbacks do not carry request IDs: tickets guard callbacks already
received/queued; a duplicated callback delivered after a new write on the same
characteristic cannot be identified independently of the platform contract.
After timeout the adapter closes the GATT object, rather than reusing its queue.

The internal JNI result is536 bytes (24 bytes metadata, up to512 data). Operations6
and7 handle receive/frame respectively; this is process ABI, not a new wire/MCP
format. Static/private keys do not cross JNI. Mutable-array clearing is best effort,
not a guarantee that Java/BC/JCA/GC copies are erased.

## Nonmutating next query

`ServiceListProbe` defines/parses GET_LIST only. It rejects malformed lengths,
more than128 entries and duplicate endpoints; unknown encryption flags remain
UNKNOWN. This request asks for service capabilities, without clock/profile/config
writes. The upstream reply handler's `initializeServices()` is deliberately absent:
following it could perform unrelated initialization. Battery endpoint0029 is a
later candidate, only after its cipher requirement is known.

**The query is not dispatched by this AUTH-only adapter.** PeerAccepted currently
confers no arbitrary-operation capability. A separately reviewed explicit query
transition and encrypted transport (where required) remain gates before execution.
No phase2/phase3, time/profile initialization or historical collection is included.

## Bluetooth-free ART fixture harness

Debug-only `AuthFixtureActivity` uses the real BC/JNI/Rust owner and plaintext
pump, a synthetic port and the published public fixture. It does not instantiate
`HuamiGattAdapter`, request Bluetooth or generate keys. A debug-only home action
opens it; its activity is not exported. Start/cancel/repeat/background/rotation
close contexts, with no automatic resume. PEER_ACCEPTED is visibly labelled fake.
The fixture asset and supporting Java test sources are excluded from release.

Host test: `BC_JAR=/verified/bcprov-jdk18on-1.86.jar sh android-bridge/run_crypto_host.sh`.
Android preparation: existing NDK `sh android-app/build-native.sh`, then strict
Gradle tasks `:app:compileDebugJavaWithJavac :app:lintDebug :app:dexBuilderDebug
:app:mergeExtDexDebug` using pinned tools in BUILDING.md. No signing/packaging task
is needed for this preparation. A debug artifact installation would be a separate
step; none was produced here. DEX output is not an ART execution result.

ART is NOT_RUN: this executor has no dalvikvm, emulator/system image or KVM.
Before physical AUTH: execute the debug fixtures on Android/ART, verify cancel,
repeat, onStop/rotation and no Bluetooth requests; then review local secret-entry
ownership and obtain explicit approval for real authentication writes. Hardware
test must stop after handshake and the separately gated GET_LIST, without any
upstream initialization sequence. Do not reset/unpair or extract credentials as a
workaround. Actual ATT callback behavior and protocol interoperability remain
unverified, as do authenticated battery/history and all other protected features.
