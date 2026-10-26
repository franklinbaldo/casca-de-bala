package org.workspace.band7.spike;

/** The whole test runs in Rust (libcasca_spike.so). */
final class Native {
    static { System.loadLibrary("casca_spike"); }
    private Native() {}
    static native String run(android.content.Context context);
}
