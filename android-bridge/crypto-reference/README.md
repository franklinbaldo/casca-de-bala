# Test-only protocol reference

Fixed Gadgetbridge revision: a0948ee1cbc2a870f91d313f8e37df5f524465f7.
Origin: https://github.com/Freeyourgadget/Gadgetbridge

Huami2021ChunkedEncoder.java, Huami2021ChunkedDecoder.java and
Huami2021Handler.java are byte-identical copies of upstream, copyright2022–2024
José Rebelo, AGPL-3.0-or-later. Original notices retained.

CryptoUtils AES methods and CheckSums CRC32 method are small upstream subsets,
with notices retained and Android imports/annotation removed. Other packages
are explicitly test-only compatibility shims: transaction captures byte arrays,
logger discards messages, ArrayUtils copies arrays and GB suppresses hexdumps.
They do not implement Bluetooth, Android permissions or reference cryptography.

The differential test uses public OpenSSL scalar/point fixtures to derive the
same session key locally. It compares our output with the unmodified primary
encoder, and feeds it to the unmodified decoder. This is stronger than an own
encoder/decoder roundtrip, but neither uses a Band packet capture or proves
interoperability with a physical device. Both AES paths use JCA; primitive KATs
are covered separately. No real credentials or generated keys.

Run: BC_JAR=/verified/bcprov-jdk18on-1.86.jar sh android-bridge/run_encrypted_reference_host.sh
Reference classes are not included in Android main/debug source sets.
