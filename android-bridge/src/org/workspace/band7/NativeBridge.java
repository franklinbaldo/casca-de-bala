package org.workspace.band7;

final class NativeBridge {
    static { System.loadLibrary("band7_jni"); }
    private NativeBridge() {}
    // Package-private: UI/MCP should use the lifecycle-owning session API.
    static native long[] invoke(byte[] request);
    static native byte[] gatt(byte[] request);
}
