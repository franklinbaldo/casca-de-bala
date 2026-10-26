package org.workspace.band7.app;

import android.content.Context;

/** Entry to the Rust BLE lab (crates/casca-ble). One call, from a Java thread,
 * so btleplug sees the app's class loader; the worker then runs in Rust. */
final class BleNative {
    static { System.loadLibrary("band7_terminal"); }
    private BleNative() {}
    /** "ok", "já iniciado" or "ERRO: …". */
    static native String start(Context context);
}
