package io.github.kazumibridge.hook;

/** Loaded only by NativeBridge's loader, which supplies the module native library path. */
public final class NativeRuntime implements NativeBackend {
    public NativeRuntime() {
        System.loadLibrary("shadowhook");
        System.loadLibrary("kazumibridge");
    }

    @Override public native int install();
    @Override public native void configure(int port, byte[] token, boolean active);
    @Override public native long redirectCount();
    @Override public native String diagnostic();
}
