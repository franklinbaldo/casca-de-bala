package org.workspace.band7;

/** Stateless JSON boundary to Rust basic GATT parsing/planning. Never performs BLE.
 * No Android permissions are requested here. Reader is an assertion by the caller,
 * not proof that an Android callback or physical measurement occurred.
 */
public final class GattBasics {
    private GattBasics() {}
    /** Input/output are UTF-8 JSON envelopes, owned by the caller. The caller must
     * not mutate input during this synchronous call. No references are retained.
     * Semantic failures return a JSON error envelope; native boundary failures throw.
     */
    public static byte[] process(byte[] envelope) {
        if(envelope==null||envelope.length==0||envelope.length>32768)
            throw new IllegalArgumentException("GATT envelope must contain 1..32768 bytes");
        byte[] result=NativeBridge.gatt(envelope);
        if(result==null)throw new IllegalStateException("Native GATT boundary failed");
        return result;
    }
}
