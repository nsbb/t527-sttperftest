package com.t527.wav2vecdemo.conformer;

import android.util.Log;

/**
 * SungBeom Conformer CTC Medium — T527 NPU uint8
 * Input: [1, 80, 301] uint8 mel spectrogram
 * Output: [1, 76, 2049] uint8 log softmax
 *
 * NB file: 102MB — too large for assets, load from /data/local/tmp/
 */
public class AwConformerJni {
    private static final String TAG = "AwConformerJni";

    static {
        System.loadLibrary("awconformer");
    }

    private long mNativePtr;

    public boolean isInit() { return mNativePtr != 0; }

    /**
     * @param nbPath  absolute path to network_binary.nb (e.g. /data/local/tmp/kr_conf_sb/network_binary.nb)
     */
    public boolean init(String nbPath) {
        if (mNativePtr != 0) throw new IllegalStateException("Already init");
        mNativePtr = nativeNew(nbPath);
        Log.d(TAG, "init: ptr=" + mNativePtr + " nb=" + nbPath);
        return mNativePtr != 0;
    }

    public void release() {
        if (mNativePtr != 0) {
            nativeDelete(mNativePtr);
            mNativePtr = 0;
        }
    }

    /**
     * Run NPU with pre-computed uint8 mel bytes [80*301]
     * @return argmax token IDs [76] for CTC decoding in Java
     */
    public int[] runUint8(byte[] uint8Mel) {
        if (mNativePtr == 0) return null;
        return nativeRunUint8(mNativePtr, uint8Mel);
    }

    /**
     * Run NPU with .dat file path (for testing with pre-computed NeMo mel)
     * @return argmax token IDs [76] for CTC decoding in Java
     */
    public int[] runDatFile(String datPath) {
        if (mNativePtr == 0) return null;
        return nativeRunDatFile(mNativePtr, datPath);
    }

    @Override
    protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }

    /**
     * Compute mel from 16kHz float audio → uint8 mel bytes [80*301]
     * @param audio16k  float[] at 16kHz, range [-1, 1]
     * @param scale     uint8 quantization scale (from nbg_meta.json)
     * @param zeroPoint uint8 quantization zero point
     * @return uint8 mel bytes [80*301] ready for runUint8()
     */
    public byte[] computeMel(float[] audio16k, float scale, int zeroPoint) {
        return nativeComputeMel(audio16k, audio16k.length, scale, zeroPoint);
    }

    /**
     * One-shot: 16kHz float audio → NPU → argmax token IDs
     * @return argmax token IDs [76] for CTC decoding
     */
    public int[] audioToArgmax(float[] audio16k, float melScale, int melZp) {
        if (mNativePtr == 0) return null;
        return nativeAudioToArgmax(mNativePtr, audio16k, audio16k.length, melScale, melZp);
    }

    private native long nativeNew(String modelPath);
    private native void nativeDelete(long ptr);
    private native int[] nativeRunUint8(long ptr, byte[] uint8Mel);
    private native int[] nativeRunDatFile(long ptr, String datPath);
    private native byte[] nativeComputeMel(float[] audio16k, int audioLen, float scale, int zeroPoint);
    private native int[] nativeAudioToArgmax(long ptr, float[] audio16k, int audioLen, float melScale, int melZp);

    // ===== Wakeword (BCResNet) =====
    private long mWakewordPtr;

    public boolean initWakeword(String nbPath) {
        mWakewordPtr = nativeNewWakeword(nbPath);
        Log.d(TAG, "Wakeword init: ptr=" + mWakewordPtr);
        return mWakewordPtr != 0;
    }

    public void releaseWakeword() {
        if (mWakewordPtr != 0) { nativeDeleteWakeword(mWakewordPtr); mWakewordPtr = 0; }
    }

    /**
     * Run wakeword detection: uint8 mel [1,1,40,151] → float[2] (non-wake, wake)
     */
    public float[] runWakeword(byte[] uint8Mel, float outScale, int outZp) {
        if (mWakewordPtr == 0) return null;
        return nativeRunWakeword(mWakewordPtr, uint8Mel, outScale, outZp);
    }

    /**
     * Compute wakeword mel from 16kHz audio (C/KissFFT — fast, HTK scale)
     */
    public byte[] computeWakewordMel(float[] audio16k, float scale, int zeroPoint) {
        return nativeComputeWakewordMel(audio16k, audio16k.length, scale, zeroPoint);
    }

    private native long nativeNewWakeword(String modelPath);
    private native void nativeDeleteWakeword(long ptr);
    private native float[] nativeRunWakeword(long ptr, byte[] uint8Mel, float outScale, int outZp);
    private native byte[] nativeComputeWakewordMel(float[] audio16k, int audioLen, float scale, int zeroPoint);

    private static boolean sNpuInit = false;
    public static void initNpu() {
        if (!sNpuInit) { nativeInitNpu(); sNpuInit = true; }
    }
    public static void releaseNpu() {
        if (sNpuInit) { nativeReleaseNpu(); sNpuInit = false; }
    }
    private static native void nativeInitNpu();
    private static native void nativeReleaseNpu();
}
