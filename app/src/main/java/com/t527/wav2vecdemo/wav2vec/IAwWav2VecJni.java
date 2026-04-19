package com.t527.wav2vecdemo.wav2vec;

import android.content.Context;
import com.t527.wav2vecdemo.wav2vec.Wav2VecResult;

public interface IAwWav2VecJni {

    boolean isInit();

    boolean init(Context context, String assetPath);

    void release();

    Wav2VecResult process(float[] audioData, int length, int sampleRate);
}