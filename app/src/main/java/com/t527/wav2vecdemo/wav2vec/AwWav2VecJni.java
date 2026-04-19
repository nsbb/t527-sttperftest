package com.t527.wav2vecdemo.wav2vec;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import com.t527.wav2vecdemo.utils.AssetsUtils;
import com.t527.wav2vecdemo.wav2vec.IAwWav2VecJni;
import com.t527.wav2vecdemo.wav2vec.Wav2VecResult;

import java.io.File;

/**
 * wav2vec NPU JNI Interface
 */
public class AwWav2VecJni implements IAwWav2VecJni {
    private static final String TAG = "AwWav2VecJni";

    static {
        System.loadLibrary("awwav2vec");               //libawwev2vec.so 불러오는 곳
    }

    //NPU 컨텍스트 포인터
    private long mNativePtr;

    /**
     * @return 초기화 여부
     */
    @Override
    public boolean isInit() {
        return mNativePtr != 0;
    }

    /**
     * @param context   Context
     * @param assetPath 모델 파일 경로
     * @return 초기화 성공 여부
     */
    @Override
    public boolean init(Context context, String assetPath) {
        if (mNativePtr != 0) {
            throw new IllegalStateException("Already init");
        }
        final File cacheDir = context.getCacheDir();
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        File cacheModelFile = new File(cacheDir, "wav2vec_model_cache.nb");
        final boolean succeed = AssetsUtils.copy(context, assetPath, cacheModelFile.getAbsolutePath());

        this.mNativePtr = nativeNew(cacheModelFile.getAbsolutePath());

        return mNativePtr != 0;
    }

    /**
     * 리소스 해제
     */
    @Override
    public void release() {
        if (mNativePtr != 0) {
            nativeDelete(mNativePtr);
            mNativePtr = 0;
        }
    }

    /**
     * 오디오 데이터 처리
     * @param audioData 정규화된 float32 오디오 데이터
     * @param length    오디오 데이터 길이
     * @param sampleRate 샘플링 레이트
     * @return 음성 인식 결과
     */
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

    // Native 메소드들
    private native long nativeNew(String path);
    private native void nativeDelete(long ptr);
    private native Wav2VecResult nativeProcess(long ptr, float[] audioData, int length, int sampleRate);

    //----정적 메소드 (NPU 초기화)
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