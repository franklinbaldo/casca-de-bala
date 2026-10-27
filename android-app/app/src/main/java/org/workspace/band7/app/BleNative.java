package org.workspace.band7.app;

import android.content.Context;

/** Entry to the Rust BLE lab (crates/casca-ble). One call, from a Java thread,
 * so btleplug sees the app's class loader; the worker then runs in Rust. */
final class BleNative {
    static { System.loadLibrary("band7_terminal"); }
    private BleNative() {}
    /** "ok", "já iniciado" or "ERRO: …". */
    static native String start(Context context);
    /** Listens on 127.0.0.1 for Zepp's return (15 min) and gives the login URL to open in the browser; "ERRO: …" on failure. */
    static native String huamiStart(String filesDir);
}
