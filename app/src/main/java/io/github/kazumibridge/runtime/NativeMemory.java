package io.github.kazumibridge.runtime;

/** Small native allocations keep media caches out of the managed heap. */
public final class NativeMemory {
    static { System.loadLibrary("kazumibridge"); }
    private NativeMemory() {}
    public static native long allocate(int capacity);
    public static native void read(long handle, int offset, byte[] data, int dataOffset, int count);
    public static native void write(long handle, int offset, byte[] data, int dataOffset, int count);
    public static native void release(long handle);
}
