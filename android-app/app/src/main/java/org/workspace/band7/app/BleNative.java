package org.workspace.band7.app;

import android.content.Context;

/** Entry to the Rust BLE lab (crates/casca-ble). One call, from a Java thread,
 * so btleplug sees the app's class loader; the worker then runs in Rust. */
final class BleNative {
    static { System.loadLibrary("band7_terminal"); }
    private BleNative() {}
    /** "ok", "já iniciado" or "ERRO: …". */
    static native String start(Context context);
    /** Official Xiaomi OAuth page that yields the Huami code (Zepp Life accounts signed in with Xiaomi). */
    static native String huamiAuthorize();
    /** The OAuth code if url is the final redirect, else null. */
    static native String huamiCode(String url);
    /** Blocking HTTPS: code -> band keys stored in filesDir. Message for the owner; "ERRO: …" on failure. */
    static native String huamiExchange(String filesDir,String code);
}
