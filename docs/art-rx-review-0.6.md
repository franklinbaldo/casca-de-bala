> Historical ART blocker below. Resolved scope and remaining NOT_RUN gates: [ART runtime result](art-runtime-0.6.md).

# ART configuration and RX review (no runtime restart)

## ART: blocker remains, with narrower evidence

The installed client is official platform-tools37.0.1. The prior run already set
ANDROID_USER_HOME to a writable directory, but the client aborted at startup:
`Cannot mkdir '/home/agent/.android': Read-only file system`. That is an ordinary
filesystem failure, not an automatic-approval denial or a failed fixture assertion.

[Official Android variable documentation](https://developer.android.com/tools/variables)
describes ANDROID_USER_HOME for SDK preferences and ANDROID_SDK_HOME as a legacy
fallback. It does not establish that this particular ADB client honors either.
A static string inspection of the installed binary found HOME/.android and
ADB_VENDOR_KEYS, but not ANDROID_USER_HOME, ANDROID_SDK_HOME or ANDROID_PREFS_ROOT.
That absence alone is not a definitive API contract. No alternate setting was
executed: if one made the directory writable, normal startup could generate a key.

ADB_VENDOR_KEYS selects credential inputs; it is not documented here as a no-keygen
or preferences-directory switch. In the accessible **historical** AOSP Android9
[auth source](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-9.0.0_r1/adb/adb_auth_host.cpp),
adb_auth_init first loads/generates the default user key, then reads vendor keys.
This explains why that variable is not a safe general no-generation guarantee,
but is not asserted to be exact37.0.1 source. Current Gitiles source could not be
retrieved in this environment; that source-version limitation remains explicit.

The temporary emulator key from the previous attempt was already deleted and
remains absent. No ADB/emulator is running, hence no existing authorized channel
can be reused. Restarting the previous emulator flow also automatically created
a local test identity during the earlier attempt. This round neither restarted
it nor generated, copied, loaded or exposed credentials. HOME, permissions,
authentication, namespace and SELinux settings remain unchanged.

Conclusion: ART fixtures remain NOT_RUN. A normal directory correction alone has
not been shown to satisfy the explicit no-new-ADB-key restriction. Before another
runtime attempt, provide a supported writable runner with an already authorized
channel, or separately decide on a temporary test identity in an appropriate
runner. Neither provisioning decision is executed here. The existing unsigned DEX
fixture and build script remain prepared; no new APK/build/signature is needed
for this review. Software-emulator kernel/ART-start evidence from the prior stage
is retained, not promoted to a completed test.

## RX: what can be strengthened without inventing guarantees

The current source-compatible policy preserves the pinned upstream decoder's
behavior. Frame bounds, fragment order/handle/length consistency, owner generation,
timeouts, expected AUTH endpoint and state transitions can be enforced from the
known protocol/state contract and are already local checks. They do not establish
cryptographic authenticity or message replay protection.

New differential tests modify only the encrypted trailer of a PUBLIC synthetic
fixture, re-encrypting with the public fixture key so the payload is unchanged:
invalid CRC, changed counter with stale CRC, and nonzero zero-fill. The unmodified
pinned Gadgetbridge decoder and UPSTREAM_PAYLOAD_ONLY accept all three; experimental
REQUIRE_TX_LAYOUT rejects them. This demonstrates a policy difference, not a
hardware exploit or proof of a general Gadgetbridge vulnerability.

Enforcing CRC/counter/padding on real RX cannot yet be called compatibility-safe:
only the encoder defines those trailing fields in our verified source. No physical
RX fixture proves that every Band response follows that layout or a specific
counter direction/base/wrap/reconnect rule. TX counter derivation must not be
silently reused as RX replay state. Duplicate ATT fragments and application-level
retransmissions also require distinct treatment before an anti-replay policy.

A future non-enforcing diagnostic can compare the candidate trailer layout on
approved synthetic/physical fixtures and report only match/mismatch/unknown, with
no raw secret/payload logs and no automatic downgrade from a required policy.
Promoting a strict policy needs model/firmware-specific evidence and explicit
counter/reconnection tests. Even then CRC32 is not a MAC; the source's ECB+CRC32
scheme and unbound endpoint/header do not become AEAD. No production policy was
changed by this review, no protected operation was enabled, and no physical data
was accessed.
