package com.tunegrab.app.audio;

/** Host-side JNI regression tests: no Android SDK or media/network access required. */
public final class Mp3Encoder {
    static { System.load(System.getProperty("tunegrab.native.library")); }
    private static native long nativeInit(int rate, int channels, int kbps, String title, String artist);
    private static native byte[] nativeEncode(long handle, short[] pcm, int frames);
    private static native byte[] nativeFlush(long handle);
    private static native int nativeClose(long handle);
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(nativeInit(0, 2, 128, null, null) == 0, "invalid sample rate");
        check(nativeInit(44100, 0, 128, null, null) == 0, "zero channels");
        check(nativeInit(44100, 3, 128, null, null) == 0, "unsupported channels");
        check(nativeInit(44100, 2, Integer.MAX_VALUE, null, null) == 0, "invalid bitrate");
        check(nativeEncode(0, null, 1) == null, "zero handle");
        check(nativeFlush(0) == null, "zero flush handle");
        check(nativeClose(0) == -1, "zero close handle");
        for (int channels : new int[]{1, 2}) {
            long handle = nativeInit(44100, channels, 128, "Test", "TuneGrab");
            check(handle != 0, "valid init");
            try {
                check(nativeEncode(handle, null, 1) == null, "null PCM");
                check(nativeEncode(handle, new short[2], Integer.MAX_VALUE) == null, "frame multiplication overflow");
                check(nativeEncode(handle, new short[0], 1) == null, "empty PCM");
                check(nativeEncode(handle, new short[2], -1) == null, "negative frames");
                check(nativeEncode(handle, new short[channels * 1152], 1152) != null, "valid mono/stereo encode");
                check(nativeFlush(handle) != null, "valid flush");
            } finally { check(nativeClose(handle) == 0, "close"); }
        }
        System.out.println("JNI regression tests passed: bounds, nulls, mono and stereo.");
    }
}
