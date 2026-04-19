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

import com.t527.wav2vecdemo.citrinet.AwCitrinetJni;
import com.t527.wav2vecdemo.utils.Config;
import com.t527.wav2vecdemo.wav2vec.IAwWav2VecJni;
import com.t527.wav2vecdemo.wav2vec.Wav2VecResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class CitrinetTestActivity extends Activity {
    private static final String TAG = "CitrinetTest";
    
    private IAwWav2VecJni citrinetJni;
    private TextView resultTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(createLayout());
        
        // NPU 초기화
        AwCitrinetJni.initNpu();
        
        citrinetJni = new AwCitrinetJni();
        boolean initSuccess = citrinetJni.init(this, Config.CITRINET_MODEL_FILE);
        
        if (initSuccess) {
            Log.d(TAG, "Citrinet initialization success");
            resultTextView.setText("Citrinet Model Ready");
        } else {
            Log.e(TAG, "Citrinet initialization failed");
            resultTextView.setText("Citrinet initialization failed");
        }
    }

    private View createLayout() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        resultTextView = new TextView(this);
        resultTextView.setText("Citrinet Model Ready");
        resultTextView.setTextSize(16);
        layout.addView(resultTextView);

        // Test audio files
        String[] audioFiles = {
            "test.wav",
            "turn_on_lights.wav",
            "check_weather.wav",
            "call_elevator.wav",
            "lights_are_on.wav"
        };
        String[] buttonLabels = {
            "Test Audio 1",
            "Turn On Lights",
            "Check Weather",
            "Call Elevator",
            "Lights Are On"
        };
        
        for (int i = 0; i < audioFiles.length; i++) {
            Button button = new Button(this);
            button.setText(buttonLabels[i]);
            final String audioFile = audioFiles[i];
            button.setOnClickListener(v -> testCitrinet(audioFile));
            layout.addView(button);
        }

        return layout;
    }

    private void testCitrinet(String audioFileName) {
        try {
            resultTextView.setText("Processing audio: " + audioFileName);
            
            float[] audioData = loadAudioFromAssets("inputs/" + audioFileName);
            
            if (audioData != null) {
                Log.d(TAG, "Audio data loaded, length: " + audioData.length);
                
                // Citrinet expects 3 seconds (48000 samples @ 16kHz)
                int maxLength = 48000;
                if (audioData.length > maxLength) {
                    float[] trimmed = new float[maxLength];
                    System.arraycopy(audioData, 0, trimmed, 0, maxLength);
                    audioData = trimmed;
                    Log.d(TAG, "Audio trimmed to " + maxLength + " samples (3 seconds)");
                }
                
                float durationSec = audioData.length / 16000.0f;
                resultTextView.setText("Audio loaded\nLength: " + audioData.length + " samples\nDuration: " + 
                                     String.format("%.2f", durationSec) + "s\nProcessing...");
                
                // Process with Citrinet
                Wav2VecResult result = citrinetJni.process(audioData, audioData.length, 16000);
                
                String resultText = "File: " + audioFileName + 
                                  "\n\nResult: " + result.getTranscription() + 
                                  "\n\nConfidence: " + String.format("%.3f", result.getConfidence()) +
                                  "\nDuration: " + String.format("%.2f", durationSec) + "s";
                resultTextView.setText(resultText);
                
                Log.d(TAG, "Citrinet result: " + result.toString());
            } else {
                resultTextView.setText("Failed to load audio file");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Citrinet test error", e);
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
        if (citrinetJni != null) {
            citrinetJni.release();
        }
        AwCitrinetJni.releaseNpu();
    }
}
