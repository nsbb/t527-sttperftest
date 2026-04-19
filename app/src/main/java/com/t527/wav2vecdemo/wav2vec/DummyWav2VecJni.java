package com.t527.wav2vecdemo.wav2vec;

import android.content.Context;
import android.util.Log;

/**
 * 더미 wav2vec JNI 구현 (테스트용)
 */
public class DummyWav2VecJni implements IAwWav2VecJni {
    private static final String TAG = "DummyWav2VecJni";

    @Override
    public boolean isInit() {
        return true;
    }

    @Override
    public boolean init(Context context, String assetPath) {
        Log.d(TAG, "더미 초기화 성공: " + assetPath);
        return true;
    }

    @Override
    public void release() {
        Log.d(TAG, "더미 해제");
    }

    @Override
    public Wav2VecResult process(float[] audioData, int length, int sampleRate) {
        long startTime = System.currentTimeMillis();
        
        Log.d(TAG, "=== 더미 wav2vec 처리 시작 ===");
        Log.d(TAG, "입력: length=" + length + ", sampleRate=" + sampleRate);
        Log.d(TAG, "NPU 사용: false (더미 구현 - CPU만 사용)");
        
        // 실제 처리 시뮬레이션 (약간의 지연)
        try {
            Thread.sleep(150 + (int)(Math.random() * 100)); // 150-250ms 랜덤 지연
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 더미 신뢰도 계산 (오디오 길이 기반으로 가짜 계산)
        float confidence = Math.min(0.95f, 0.7f + (length / 100000.0f));
        
        // 오디오 길이에 따른 더미 텍스트 생성
        String transcription;
        if (length < 20000) {
            transcription = "짧은 음성";
        } else if (length < 40000) {
            transcription = "안녕하세요";
        } else if (length < 60000) {
            transcription = "음성 인식 테스트입니다";
        } else {
            transcription = "긴 음성 파일 처리 완료";
        }
        
        long endTime = System.currentTimeMillis();
        long processingTime = endTime - startTime;
        
        Log.d(TAG, "처리 시간: " + processingTime + "ms");
        Log.d(TAG, "신뢰도 계산: 더미 알고리즘 (길이 기반) = " + String.format("%.3f", confidence));
        Log.d(TAG, "=== 더미 wav2vec 처리 완료 ===");
        
        return new Wav2VecResult(transcription, confidence);
    }
}