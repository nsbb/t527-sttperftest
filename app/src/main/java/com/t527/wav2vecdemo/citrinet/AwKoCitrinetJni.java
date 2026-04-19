package com.t527.wav2vecdemo.citrinet;

import android.content.Context;
import android.util.Log;

import com.t527.wav2vecdemo.utils.AssetsUtils;
import com.t527.wav2vecdemo.wav2vec.Wav2VecResult;

import java.io.File;

/**
 * Korean Citrinet NPU JNI Interface (INT8, T527 NPU)
 * Model I/O: input [1,80,1,300] int8 / output [1,2049,1,38] int8
 */
public class AwKoCitrinetJni {
    private static final String TAG = "AwKoCitrinetJni";

    static {
        System.loadLibrary("awko_citrinet");
    }

    private long mNativePtr;

    public long getNativePtr() { return mNativePtr; }

    public boolean isInit() {
        return mNativePtr != 0;
    }

    /**
     * @param modelAssetPath  e.g. "models/KoCitriNet/network_binary.nb"
     * @param vocabAssetPath  e.g. "models/KoCitriNet/vocab_ko.txt"
     */
    public boolean init(Context context, String modelAssetPath, String vocabAssetPath) {
        if (mNativePtr != 0) throw new IllegalStateException("Already init");

        File cacheDir = context.getCacheDir();
        if (!cacheDir.exists()) cacheDir.mkdirs();

        File cacheModel = new File(cacheDir, "ko_citrinet_model.nb");
        File cacheVocab = new File(cacheDir, "ko_citrinet_vocab.txt");

        boolean modelOk = AssetsUtils.copy(context, modelAssetPath, cacheModel.getAbsolutePath());
        boolean vocabOk = AssetsUtils.copy(context, vocabAssetPath, cacheVocab.getAbsolutePath());

        if (!modelOk || !vocabOk) {
            Log.e(TAG, "Failed to copy assets: model=" + modelOk + " vocab=" + vocabOk);
            return false;
        }

        mNativePtr = nativeNew(cacheModel.getAbsolutePath(), cacheVocab.getAbsolutePath());
        return mNativePtr != 0;
    }

    public void release() {
        if (mNativePtr != 0) {
            nativeDelete(mNativePtr);
            mNativePtr = 0;
        }
    }

    public Wav2VecResult process(float[] audioData, int length, int sampleRate) {
        if (mNativePtr == 0) {
            Log.w(TAG, "process() called before init");
            return new Wav2VecResult("", 0.0f);
        }
        return nativeProcess(mNativePtr, audioData, length, sampleRate);
    }

    /**
     * Run NPU inference with pre-computed int8 bytes from KoMelFrontend.
     * Bypasses C mel preprocessing — uses Java mel pipeline for exact JVM arithmetic.
     */
    public Wav2VecResult processWithJavaMel(float[] audioAt16kHz) {
        if (mNativePtr == 0) {
            Log.w(TAG, "processWithJavaMel() called before init");
            return new Wav2VecResult("", 0.0f);
        }
        byte[] int8 = KoMelFrontend.computeAndQuantize(audioAt16kHz);
        return nativeRunInt8(mNativePtr, int8);
    }

    private native Wav2VecResult nativeRunInt8(long ptr, byte[] int8Array);

    @Override
    protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }

    private native long nativeNew(String modelPath, String vocabPath);
    private native void nativeDelete(long ptr);
    private native Wav2VecResult nativeProcess(long ptr, float[] audioData, int length, int sampleRate);
    public native Wav2VecResult nativeProcessRawInput(long ptr, String datPath);

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
