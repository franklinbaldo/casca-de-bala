> Later x86_64 JNI/ART results: [native integration](art-jni-0.6.md). Prior JNI NOT_RUN below refers to this earlier Java-only stage.

# ART fixture execution: Android12/API31 x86_64

The earlier runner blocker is resolved for this explicitly authorized temporary
emulator-only identity. The exact unsigned DEX retained in the private preparation
package was executed; no APK, production-code change or fixture rebuild was needed.

## Configuration and scope

The installed platform-tools37.0.1 client ignored ANDROID_USER_HOME when its usual
home directory was read-only. A dedicated **child-process user home**, with matching
ANDROID_USER_HOME/.android preferences, resolved that path normally. The agent's
HOME, shell profile and host filesystem permissions were not changed. This uses
HOME for its intended process-home semantics, not as a repurposed workspace variable.

One temporary ADB key was created in that private directory under the user's
specific approval. No other keys were read/copied/replaced. Authentication remained
enabled; no skip-adb-auth, root/namespace/SELinux bypass or physical-device access.
The server listened on localhost:5038, was restricted with a nonmatching USB filter,
and every test command explicitly targeted the dedicated local emulator. The first
numeric-host listener syntax failed; the supported localhost spelling fixed it
without starting another emulator. No public endpoint/persistent access was added.

The emulator used the previously verified official stable37.2.12 binary and AOSP
API31 x86_64 image, software emulation without KVM,2 virtual cores and2048MiB RAM.
No agreement, toolchain installation, APK signer or production credential was added.
After tests the dedicated processes stopped and the entire temporary home/identity
directory was removed. Test-only virtual-machine data remains in the existing
private AVD; no reusable host identity remains.

## Verified result

ADB confirmed Android12, SDK31, x86_64 and the executable link
`/system/bin/dalvikvm -> /apex/com.android.art/bin/dalvikvm`.
The framework boot-complete property was empty; full UI boot is not claimed.
Standalone fixture execution does not require that property to be set.

Host and on-emulator SHA256 matched:

- Unsigned DEX jar,2194723 bytes:
  `1f424773a129dbda4bcb0dbd378e9d4abb8304d4541db5777031ce3e4527a0c1`.
- Published public vector:
  `7e40c9f66155731b2ccb4b2bf6331b1a82fe7bbb0610c2412ebc36acf2c7efa5`.

**PASS on ART:** HuamiCryptoTest and EncryptedFramesTest, invoked as:

```
adb -s DEDICATED_LOCAL_EMULATOR shell dalvikvm \
  -cp /data/local/tmp/casca-fixture.jar \
  org.workspace.band7.HuamiCryptoTest /data/local/tmp/casca-public-vector.txt
adb -s DEDICATED_LOCAL_EMULATOR shell dalvikvm \
  -cp /data/local/tmp/casca-fixture.jar \
  org.workspace.band7.EncryptedFramesTest /data/local/tmp/casca-public-vector.txt
```

These execute public curve/AES fixtures, invalid-point/length/order2 and cleanup
checks, plus encrypted framing roundtrips, source-compatible/experimental RX policy,
sequence/length/corruption/timeouts, counter/handle exhaustion and owner closure.
They use synthetic clock/peer inputs, not Bluetooth or a captured Band session.
Both commands exited0 after assertions and PASS output. The first test's unchanged
stdout includes a historical host/NoART label; it does not detect its runtime.
The commands and Android executable identity establish the ART execution context.

**NOT_RUN:** JNI/Rust on ART, Activity lifecycle/rotation or full app UI, ARM64 ART,
GATT, real authentication/query/health/hardware, new signed APK or publication.
The Java fixture jar has no matching x86_64 native libraries, so native lifecycle
checks remain host-only. Previous69 Rust tests/differential host checks are earlier
receipts, not newly executed or reclassified as ART results here.

Next review gate: matching-ABI JNI/ART plus Activity lifecycle tests. Real Band
credentials and a physical AUTH pilot remain separately authorized steps; no clock,
profile, firmware or protected-operation writes are implied by this result.
