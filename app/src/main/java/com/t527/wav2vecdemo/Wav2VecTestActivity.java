package com.t527.wav2vecdemo;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.t527.wav2vecdemo.utils.Config;
import com.t527.wav2vecdemo.wav2vec.AwWav2VecJni;
import com.t527.wav2vecdemo.wav2vec.DummyWav2VecJni;
import com.t527.wav2vecdemo.wav2vec.IAwWav2VecJni;
import com.t527.wav2vecdemo.wav2vec.Wav2VecResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Wav2VecTestActivity extends Activity {
    private static final String TAG = "Wav2VecTestActivity";
    
    private IAwWav2VecJni wav2vecJni;
    private TextView resultTextView;
    private Button testButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 간단한 레이아웃 생성
        setContentView(createLayout());
        
        // wav2vec JNI 초기화 (실제 NPU 구현 사용)
        // NPU 먼저 초기화
        AwWav2VecJni.initNpu();
        
        wav2vecJni = new AwWav2VecJni();
        boolean initSuccess = wav2vecJni.init(this, Config.WAV2VEC_MODEL_FILE);
        
        if (initSuccess) {
            Log.d(TAG, "wav2vec2 initialization success");
            resultTextView.setText("wav2vec2 English Model Ready\n(facebook/wav2vec2-base-960h)");
        } else {
            Log.e(TAG, "wav2vec2 initialization failed");
            resultTextView.setText("wav2vec2 initialization failed");
        }
    }

    private View createLayout() {
        // 프로그래밍 방식으로 간단한 레이아웃 생성
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        resultTextView = new TextView(this);
        resultTextView.setText("wav2vec2 English Model Ready");
        resultTextView.setTextSize(16);
        layout.addView(resultTextView);

        // Test audio file button (English)
        Button testButton = new Button(this);
        testButton.setText("Test English Audio (test.wav)");
        testButton.setOnClickListener(v -> testWav2Vec("test.wav"));
        layout.addView(testButton);

        // Legacy Korean audio file buttons (will not work with English model)
        String[] audioFiles = {"lights_are_on.wav", "call_elevator.wav", "check_weather.wav", "turn_on_lights.wav"};
        String[] buttonLabels = {"조명이 켜졌습니다 (KR)", "엘리베이터 호출 (KR)", "날씨 확인 (KR)", "조명 켜기 (KR)"};
        
        for (int i = 0; i < audioFiles.length; i++) {
            Button button = new Button(this);
            button.setText(buttonLabels[i]);
            final String audioFile = audioFiles[i];
            button.setOnClickListener(v -> testWav2Vec(audioFile));
            layout.addView(button);
        }

        return layout;
    }

    private void testWav2Vec(String audioFileName) {
        try {
            resultTextView.setText("Processing audio: " + audioFileName);
            
            // Load test audio file
            float[] audioData = loadAudioFromAssets("inputs/" + audioFileName);
            
            if (audioData != null) {
                Log.d(TAG, "Audio data loaded, length: " + audioData.length + " (model requires: 320000)");
                
                // Display audio length info
                float durationSec = audioData.length / 16000.0f;
                resultTextView.setText("Audio loaded\nLength: " + audioData.length + " samples\nDuration: " + 
                                     String.format("%.2f", durationSec) + "s\nProcessing...");
                
                // Process with wav2vec2
                Wav2VecResult result = wav2vecJni.process(audioData, audioData.length, 16000);
                
                String resultText = "File: " + audioFileName + 
                                  "\n\nResult: " + result.getTranscription() + 
                                  "\n\nConfidence: " + String.format("%.3f", result.getConfidence()) +
                                  "\nDuration: " + String.format("%.2f", durationSec) + "s";
                resultTextView.setText(resultText);
                
                Log.d(TAG, "wav2vec2 result: " + result.toString());
            } else {
                resultTextView.setText("Failed to load audio file");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "wav2vec2 test error", e);
            resultTextView.setText("Error: " + e.getMessage());
        }
    }

    private float[] loadAudioFromAssets(String assetName) {
        try {
            AssetFileDescriptor afd = getAssets().openFd(assetName);
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();

            MediaFormat format = extractor.getTrackFormat(0);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            
            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            extractor.selectTrack(0);
            int bytesRead;
            ByteBuffer all = ByteBuffer.allocate(0);

            while ((bytesRead = extractor.readSampleData(buf, 0)) >= 0) {
                ByteBuffer tmp = ByteBuffer.allocate(all.capacity() + bytesRead);
                all.rewind();
                tmp.put(all);
                tmp.put(buf.array(), 0, bytesRead);
                all = tmp;
                extractor.advance();
            }
            extractor.release();
            all.rewind();

            short[] pcm16 = new short[all.capacity() / 2];
            all.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm16);

            // PCM16을 float32로 변환하고 정규화
            float[] f32 = new float[pcm16.length];
            for (int i = 0; i < pcm16.length; i++) {
                f32[i] = pcm16[i] / 32768.0f;
            }

            Log.d(TAG, "Audio loaded: sampleRate=" + sampleRate + ", length=" + f32.length);
            return f32;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load audio file", e);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wav2vecJni != null) {
            wav2vecJni.release();
        }
        // NPU 해제
        AwWav2VecJni.releaseNpu();
    }
}