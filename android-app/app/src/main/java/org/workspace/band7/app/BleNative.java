package org.workspace.band7.app;

import android.content.Context;

/** Entry to the Rust BLE lab (crates/casca-ble). One call, from a Java thread,
 * so btleplug sees the app's class loader; the worker then runs in Rust. */
final class BleNative {
    static { System.loadLibrary("band7_terminal"); }
    private BleNative() {}
    /** "ok", "já iniciado" or "ERRO: …". */
    static native String start(Context context);
    /** Zepp's own web login (any method it offers, Xiaomi included). */
    static native String huamiLoginPage();
    /** URL whose cookies hold the Zepp session once signed in. */
    static native String huamiCookieUrl();
    /** Whether those cookies already hold a signed-in session. */
    static native boolean huamiSignedIn(String cookies);
    /** Blocking HTTPS: session cookies -> band keys stored in filesDir. Message for the owner; "ERRO: …" on failure. */
    static native String huamiExchange(String filesDir,String cookies);
}
