package com.t527.wav2vecdemo.deepspeech2;

import android.content.Context;
import android.util.Log;

import com.t527.wav2vecdemo.utils.AssetsUtils;
import com.t527.wav2vecdemo.wav2vec.IAwWav2VecJni;
import com.t527.wav2vecdemo.wav2vec.Wav2VecResult;

import java.io.File;

/**
 * DeepSpeech2 NPU JNI Interface
 */
public class AwDeepSpeech2Jni implements IAwWav2VecJni {
    private static final String TAG = "AwDeepSpeech2Jni";

    static {
        System.loadLibrary("awdeepspeech2");
    }

    private long mNativePtr;

    @Override
    public boolean isInit() {
        return mNativePtr != 0;
    }

    @Override
    public boolean init(Context context, String assetPath) {
        if (mNativePtr != 0) {
            throw new IllegalStateException("Already init");
        }
        final File cacheDir = context.getCacheDir();
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        File cacheModelFile = new File(cacheDir, "deepspeech2_model_cache.nb");
        final boolean succeed = AssetsUtils.copy(context, assetPath, cacheModelFile.getAbsolutePath());

        this.mNativePtr = nativeNew(cacheModelFile.getAbsolutePath());

        return mNativePtr != 0;
    }

    @Override
    public void release() {
        if (mNativePtr != 0) {
            nativeDelete(mNativePtr);
            mNativePtr = 0;
        }
    }

    @Override
    public Wav2VecResult process(float[] audioData, int length, int sampleRate) {
        if (mNativePtr == 0) {
            Log.w(TAG, "Process fail, init first!");
            return new Wav2VecResult("", 0.0f);
        }
        return nativeProcess(mNativePtr, audioData, length, sampleRate);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

    // Native methods
    private native long nativeNew(String path);
    private native void nativeDelete(long ptr);
    private native Wav2VecResult nativeProcess(long ptr, float[] audioData, int length, int sampleRate);

    // Static methods (NPU initialization)
    private static boolean isInitNpu = false;

    public static void initNpu() {
        if (!isInitNpu) {
            nativeInitNpu();
            isInitNpu = true;
        }
    }

    public static void releaseNpu() {
        if (isInitNpu) {
            nativeReleaseNpu();
            isInitNpu = false;
        }
    }

    private static native void nativeInitNpu();
    private static native void nativeReleaseNpu();
}
