> Historical ART blocker below. Resolved scope and remaining NOT_RUN gates: [ART runtime result](art-runtime-0.6.md).

# Encrypted2021 framing: local, not hardware-verified

## Source and exact wire construction

Protocol source remains Gadgetbridge revision
`a0948ee1cbc2a870f91d313f8e37df5f524465f7`, especially
[Huami2021ChunkedEncoder](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/Huami2021ChunkedEncoder.java),
[Huami2021ChunkedDecoder](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/Huami2021ChunkedDecoder.java)
and InitOperation2021. Community notices remain intact in test references.

Encryption is needed for endpoints advertised as encrypted by GET_LIST; this does
not establish which endpoints the user's current unit advertises. AUTH can receive
encrypted frames after the challenge establishes the session key. No protected
operation is enabled or sent by this increment.

For extended encrypted TX, the per-message AES key is the16-byte shared session
key XORed bytewise with the one-byte incremented write handle. Plaintext is
payload followed by uint32 little-endian counter, CRC32(payload+counter)LE32,
then zero fill to a16-byte boundary. AES/ECB/NoPadding encrypts that buffer.
The header length is the ORIGINAL payload length, not the padded ciphertext
length. Extended headers are11 then5 bytes; MTU minus3 bounds each fragment;
flag8 means encrypted, first1, final6. ACK construction remains unchanged.
The counter starts from shared secret bytes0..3LE; the existing provider derives
and owns it without exporting the session key to JNI.

CRC32 is a checksum, NOT a MAC. ECB+CRC32 is NOT AEAD; headers/endpoints are not
CRC-covered. Do not infer authenticity from successful decryption or crcChecked.
The pinned RX decoder decrypts and truncates to the original length; it does NOT
check CRC, counter or padding. We do not invent an RX security guarantee that
upstream lacks, nor claim that the Band implements TX trailing fields in RX.

## Implemented boundaries and local policy

`HuamiEncryptedFrames` produces encrypted frames and bounds/reassembles/decrypts
incoming frames. Source-compatible `UPSTREAM_PAYLOAD_ONLY` explicitly returns
crcChecked=false/sequence=null and makes no replay assertion. Experimental
`REQUIRE_TX_LAYOUT` checks the encoder's counter/CRC/zero-fill layout and increasing
counter; this is useful for synthetic TX fixtures, **not a validated Band RX format**.
It must not silently become a hardware compatibility requirement.

Local restrictions are stricter than upstream: max4096 plaintext bytes, max512 per
fragment, one bounded partial message, contiguous fragment sequence and matching
handle,5s partial timeout, monotonic clock, no empty payload, and no uint32 counter
or byte-handle wrap. A uint32 counter may be used once at its maximum; the next TX
fails. Handle exhaustion forces a new session before repeating the XOR key.
No retry/reordering/replay semantics beyond those explicitly tested are invented.

Only one codec may claim a crypto context. A failed transport must close the codec
and owner; do not rewind counters for retry. Buffers are cleared best effort and
the session owner remains responsible for key cleanup. JVM copies may survive.

The owner claims a receiver after the challenge/proof is computed; AUTH's TX handles
are1(public) and2(proof), so the retained encrypted TX namespace starts after2.
That assumption is bound to the existing two-message AUTH flow and must change
with any future logical TX dispatcher. The serial pump now routes encrypted RX
through the Java session context, then existing Rust endpoint/AUTH validation;
ACKs still share the one-in-flight write queue. Before key establishment, encrypted
RX is unsupported. Cancel/background/disconnect close the codec with its owner.
A synthetic encrypted peer-success reply is tested through decrypt -> owner ->
JNI -> Rust. No public encrypted-operation dispatch API has been added to the owner
or Android adapter. GET_LIST remains a definition/parser only.

## Evidence and reproduction

`run_crypto_host.sh` covers fixture roundtrips, malformed length/sequence/handle,
corruption, timeout, backwards clock, counter/handle exhaustion, one-owner cleanup,
replay rejection under TX-layout policy and deliberate absence of replay validation
under source-compatible policy. A header mutation test demonstrates that even a
valid CRC does not authenticate the endpoint.

`run_encrypted_reference_host.sh` compares byte-for-byte with the unmodified pinned
upstream encoder at MTUs23/247/515 for eight payload sizes, across advancing
handles/counters, then feeds our frames to the original decoder. The three upstream
classes and bounded compatibility shims are test-only, not Android dependencies;
see `android-bridge/crypto-reference/README.md`. Public OpenSSL test material is
synthetically composed into the protocol. This is differential software evidence,
not an independent hardware transcript or proof of Band interoperability.

Use the verified official BC1.86 jar as BC_JAR; no implicit downloads. Existing
provider KATs are separate from framing comparisons. Java/Android compile/lint/DEX
checks are not ART execution. No signed APK or signing key was produced.

## ART feasibility and remaining gate

Official stable emulator37.2.12, platform-tools37.0.1 and AOSP API31 x86_64 image
were downloaded from Google's repository. Archive lengths and official SHA1
checksums were verified; SHA256 receipts are retained privately. The license text
is byte-identical to the previously approved SDK agreement (SHA256
1f8729233617b193fd619213792ae16a41b95d2bbbf525dfe66998252ba68b16).
No preview/additional agreement was accepted.

Executor resources: memory limit16GiB,4 CPU quota, about21GiB free before provision.
KVM is unavailable (`-accel-check`: no vmx/svm). A bounded software-emulation attempt
with2 cores/2048MiB reached Android init, mounted com.android.art and started
zygote; that is evidence software emulation can start, not a fixture PASS.

Fixture execution remains NOT_RUN. The host ADB binary aborts trying to create
`/home/agent/.android` on a read-only filesystem even with ANDROID_USER_HOME set.
The emulator's early console has a bootstrap namespace that lacks the mounted ART
path. No HOME override, host permission escalation, SELinux change or namespace
bypass was attempted. The VM was stopped; its automatically created temporary
emulator ADB identity was removed. No user device or Band credentials were touched.

A no-signature DEX fixture jar was prepared locally. Future ART run, in an approved
writable ADB environment on a dedicated local emulator only: push fixture.jar and
public vector to /data/local/tmp; invoke `dalvikvm -cp /data/local/tmp/fixture.jar
org.workspace.band7.EncryptedFramesTest /data/local/tmp/public-vector.txt`, then
record output/runtime identity. This checks crypto/framing on ART, not JNI/UI/GATT.
Full JNI needs matching emulator-ABI native libraries and separate lifecycle tests.
Do not use an attached user's phone by default. No new APK/pilot before review.
