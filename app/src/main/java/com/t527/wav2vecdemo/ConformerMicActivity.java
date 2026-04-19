package com.t527.wav2vecdemo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.t527.wav2vecdemo.conformer.AwConformerJni;
import com.t527.wav2vecdemo.conformer.ConformerDecoder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ConformerMicActivity extends AppCompatActivity {
    private static final String TAG = "ConformerMic";
    private static final String ASSET_DIR = "models/Conformer";

    // NPU quantization params (100k aihub100 calib)
    private static final float MEL_SCALE = 0.025880949571728706f;
    private static final int MEL_ZP = 84;

    private static final int SAMPLE_RATE_MIC = 48000;
    private static final int SAMPLE_RATE_MODEL = 16000;
    private static final int RECORD_SECONDS = 3;

    private static final int SEQ_OUT = 76;
    private static final int STRIDE_OUT = 62;

    private TextView mTextResult;
    private TextView mTextPerf;
    private Button mBtnRecord;
    private ScrollView mScrollView;
    private AwConformerJni mJni;
    private ConformerDecoder mDecoder;
    private boolean mRecording = false;

    private String copyAssetToInternal(String assetName) {
        File outFile = new File(getFilesDir(), assetName);
        if (outFile.exists() && outFile.length() > 0) return outFile.getAbsolutePath();
        outFile.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(ASSET_DIR + "/" + assetName);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[1024 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        } catch (IOException e) {
            Log.e(TAG, "Asset copy failed: " + assetName, e);
            return null;
        }
        return outFile.getAbsolutePath();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);

        // 녹음 버튼
        mBtnRecord = new Button(this);
        mBtnRecord.setText("🎤 녹음 시작 (3초)");
        mBtnRecord.setTextSize(20);
        mBtnRecord.setOnClickListener(v -> startRecognition());
        layout.addView(mBtnRecord);

        // 인식 결과
        mTextResult = new TextView(this);
        mTextResult.setTextSize(24);
        mTextResult.setPadding(8, 32, 8, 16);
        mTextResult.setText("버튼을 눌러 말하세요");
        layout.addView(mTextResult);

        // 성능 정보
        mTextPerf = new TextView(this);
        mTextPerf.setTextSize(12);
        mTextPerf.setPadding(8, 8, 8, 8);
        layout.addView(mTextPerf);

        mScrollView = new ScrollView(this);
        mScrollView.addView(layout);
        setContentView(mScrollView);

        // 마이크 권한
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        // NPU 초기화
        String nbPath = copyAssetToInternal("network_binary.nb");
        String vocabPath = copyAssetToInternal("vocab_correct.json");
        if (nbPath == null) {
            nbPath = "/data/local/tmp/kr_conf_sb/network_binary.nb";
            vocabPath = "/data/local/tmp/kr_conf_sb/vocab_correct.json";
        }

        AwConformerJni.initNpu();
        mJni = new AwConformerJni();
        boolean ok = mJni.init(nbPath);
        Log.d(TAG, "NPU init: " + ok);

        mDecoder = new ConformerDecoder();
        mDecoder.loadVocab(vocabPath);

        if (!ok) {
            mTextResult.setText("NPU 초기화 실패");
            mBtnRecord.setEnabled(false);
        }
    }

    private void startRecognition() {
        if (mRecording) return;
        mRecording = true;
        mBtnRecord.setEnabled(false);
        mBtnRecord.setText("🔴 녹음 중...");
        mTextResult.setText("말하세요...");
        mTextPerf.setText("");

        new Thread(() -> {
            try {
                doRecordAndRecognize();
            } catch (Exception e) {
                Log.e(TAG, "Error", e);
                runOnUiThread(() -> mTextResult.setText("에러: " + e.getMessage()));
            } finally {
                mRecording = false;
                runOnUiThread(() -> {
                    mBtnRecord.setEnabled(true);
                    mBtnRecord.setText("🎤 녹음 시작 (3초)");
                });
            }
        }).start();
    }

    private void doRecordAndRecognize() {
        long t0 = System.currentTimeMillis();

        // Step 1: 녹음 (48kHz)
        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_MIC,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        bufSize = Math.max(bufSize, SAMPLE_RATE_MIC * RECORD_SECONDS * 2);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> mTextResult.setText("마이크 권한 없음"));
            return;
        }

        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_MIC, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize);

        int totalSamples = SAMPLE_RATE_MIC * RECORD_SECONDS;
        short[] pcm48k = new short[totalSamples];

        recorder.startRecording();
        long tRecStart = System.currentTimeMillis();

        int read = 0;
        while (read < totalSamples) {
            int chunk = recorder.read(pcm48k, read, Math.min(4096, totalSamples - read));
            if (chunk <= 0) break;
            read += chunk;
        }

        recorder.stop();
        recorder.release();
        long tRecEnd = System.currentTimeMillis();
        long recMs = tRecEnd - tRecStart;

        // Step 2: Resample 48k → 16k (factor 3, linear interp)
        long tResampleStart = System.currentTimeMillis();
        int newLen = read / 3;
        float[] audio16k = new float[newLen];
        for (int i = 0; i < newLen; i++) {
            float srcIdx = i * 3.0f;
            int idx0 = (int) srcIdx;
            float frac = srcIdx - idx0;
            int idx1 = Math.min(idx0 + 1, read - 1);
            audio16k[i] = ((1 - frac) * pcm48k[idx0] + frac * pcm48k[idx1]) / 32768.0f;
        }
        long tResampleEnd = System.currentTimeMillis();
        long resampleMs = tResampleEnd - tResampleStart;

        float duration = newLen / (float) SAMPLE_RATE_MODEL;

        // Step 3: Mel + NPU (sliding window)
        // 3초 chunk 단위로 처리
        int WINDOW_SAMPLES = SAMPLE_RATE_MODEL * 301 / 100; // 301 frames = 48160 samples
        int STRIDE_SAMPLES = SAMPLE_RATE_MODEL * 250 / 100; // 250 frames = 40000 samples

        List<int[]> allArgmax = new ArrayList<>();
        long totalMelMs = 0;
        long totalNpuMs = 0;
        int numChunks = 0;

        int pos = 0;
        while (pos < newLen) {
            int end = Math.min(pos + WINDOW_SAMPLES, newLen);
            int chunkLen = end - pos;

            // Extract chunk
            float[] chunkAudio = new float[chunkLen];
            System.arraycopy(audio16k, pos, chunkAudio, 0, chunkLen);

            // Mel (C/JNI)
            long tMel0 = System.currentTimeMillis();
            byte[] melBytes = mJni.computeMel(chunkAudio, MEL_SCALE, MEL_ZP);
            long tMel1 = System.currentTimeMillis();
            totalMelMs += (tMel1 - tMel0);

            // NPU
            long tNpu0 = System.currentTimeMillis();
            int[] argmax = mJni.runUint8(melBytes);
            long tNpu1 = System.currentTimeMillis();
            totalNpuMs += (tNpu1 - tNpu0);

            if (argmax != null) {
                allArgmax.add(argmax);
            }
            numChunks++;

            if (end >= newLen) break;
            pos += STRIDE_SAMPLES;
        }

        // Step 4: Merge + CTC decode
        long tDec0 = System.currentTimeMillis();
        List<Integer> mergedIds = new ArrayList<>();
        for (int ci = 0; ci < allArgmax.size(); ci++) {
            int[] ids = allArgmax.get(ci);
            int useFrames = (ci < allArgmax.size() - 1) ? STRIDE_OUT : SEQ_OUT;
            for (int t = 0; t < useFrames && t < ids.length; t++) {
                mergedIds.add(ids[t]);
            }
        }
        int[] merged = new int[mergedIds.size()];
        for (int i = 0; i < merged.length; i++) merged[i] = mergedIds.get(i);

        String text = mDecoder.decode(merged);
        int blanks = mDecoder.countBlanks(merged);
        long tDec1 = System.currentTimeMillis();
        long decMs = tDec1 - tDec0;

        long totalMs = System.currentTimeMillis() - t0;
        float rtf = (totalMelMs + totalNpuMs) / 1000.0f / duration;

        // UI 업데이트
        final String resultText = text.isEmpty() ? "(인식 결과 없음)" : text;
        final String perfText = String.format(
                "━━━ 성능 측정 ━━━\n" +
                "음성 길이:  %.2f초 (%d chunks)\n" +
                "녹음:      %dms\n" +
                "리샘플링:   %dms (48k→16k)\n" +
                "Mel 생성:  %dms (C/JNI)\n" +
                "NPU 추론:  %dms\n" +
                "디코딩:    %dms\n" +
                "━━━━━━━━━━━━━━━━\n" +
                "전체 지연:  %dms (녹음 제외: %dms)\n" +
                "RTF:       %.3f (%.1f배 실시간)\n" +
                "blank:     %d/%d",
                duration, numChunks,
                recMs,
                resampleMs,
                totalMelMs,
                totalNpuMs,
                decMs,
                totalMs, totalMs - recMs,
                rtf, 1.0f / rtf,
                blanks, merged.length
        );

        Log.d(TAG, "Result: " + resultText);
        Log.d(TAG, perfText);

        runOnUiThread(() -> {
            mTextResult.setText(resultText);
            mTextPerf.setText(perfText);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mJni != null) mJni.release();
    }
}
