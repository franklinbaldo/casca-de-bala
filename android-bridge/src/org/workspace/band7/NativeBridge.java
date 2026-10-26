package org.workspace.band7;

final class NativeBridge {
    static { System.loadLibrary("band7_jni"); }
    private NativeBridge() {}
    // Package-private: UI/MCP should use the lifecycle-owning session API.
    static native long[] invoke(byte[] request);
    static native byte[] gatt(byte[] request);
    static native byte[] authSession(int op,long handle,long generation,long now,int endpoint,byte[] publicOrEncryptedPayload);
    static native byte[] zepp(int operation,int endpoint,int chunkHandle,byte[] payload);
    static native int authReplyKind(int endpoint, byte[] publicPeerPayload);
}
