package com.midoali.atari.core;

import java.io.IOException;
import java.nio.ByteBuffer;

/** JNI bridge to the pinned, unmodified Genesis Plus GX libretro core. */
public final class SegaCore {
    static { System.loadLibrary("sega_jni"); }
    private SegaCore() {}

    public static native boolean nativeLoad(String path, boolean sixButton) throws IOException;
    public static native void nativeSetSixButton(boolean enabled);
    public static native void nativeSetPerformanceOptions(boolean showBorders, boolean autoFrameSkip);
    /** Cheat ids come from {@link com.midoali.atari.core.Cheats}; applied live, every frame. */
    public static native void nativeSetCheat(int id, boolean enabled);
    public static native int nativeCheatMask();
    public static native void nativeSetAudioStatus(int occupancy, boolean active, boolean underrun);
    /** Returns stereo frame count; metadata: width, height, serial, maxWidth, maxHeight, aspectBits, coreMicros. */
    public static native int nativeRun(short[] audio, ByteBuffer video, int[] metadata);
    public static native void nativeSetInput(int mask);
    public static native void nativeClose();
    public static native double nativeFps();
    public static native int nativeSampleRate();
    public static native int nativeSaveSize();
    public static native boolean nativePersistSave(String path, boolean restore);
    public static native void nativeReset();
    // Test-only APIs are compiled only when SEGA_TEST_API is enabled.
    public static native int nativeTestInput(int id);
    public static native byte[] nativeTestRam(int offset, int length);
    public static native boolean nativeTestInMatch();
}
