# Public test vectors only

openssl-b163-public-test-vector.txt contains Alice scalar/X/Y, Bob scalar/X/Y,
and expected sharedX from the sect163r2 block in OpenSSL3.5.7:
https://github.com/openssl/openssl/blob/openssl-3.5.7/test/recipes/30-test_evp_data/evppkey_ecdh.txt
Original file SHA256:d3938fe1b88e9a8ebb73aa434bd4f938e3cdc21a19858d919df99db88823f74e.
These are already published TEST values, never account/device credentials.
Copyright2001–2024 The OpenSSL Project Authors; Apache-2.0, license retained in
third_party/licenses/openssl-3.5.7/LICENSE.txt. Refer to the exact upstream file
for its copyright header; this subset does not relicense the source.

HuamiCryptoTest combines that primitive vector and the public AES example into
a synthetic protocol-derivation fixture. It is NOT a known-good Band transcript.
CryptoBridgeSmoke passes only peer nonce/public point to JNI, not an auth key.
The tests inspect owned-array cleanup on close, not guaranteed erasure of JVM,
BigInteger, BC/JCA internal copies, crash dumps or caller-owned input buffers.

Set BC_JAR to verified official1.86 and run sh android-bridge/run_crypto_host.sh.
No network download, secret input, BLE or persistent key is performed by it.
