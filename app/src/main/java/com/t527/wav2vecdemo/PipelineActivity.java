package com.t527.wav2vecdemo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.t527.wav2vecdemo.conformer.AwConformerJni;
import com.t527.wav2vecdemo.conformer.ConformerDecoder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;

import org.json.JSONObject;

public class PipelineActivity extends AppCompatActivity {
    private static final String TAG = "Pipeline";

    // Conformer
    private static final float CONF_MEL_SCALE = 0.025880949571728706f;
    private static final int CONF_MEL_ZP = 84;
    private static final int SEQ_OUT = 76;

    // Wakeword
    private static final float WK_THRESHOLD = 0.40f;
    private static final int WK_N_MELS = 40;
    private static final int WK_FRAMES = 151;

    // Audio
    private static final int SR_MIC = 48000;
    private static final int SR_MODEL = 16000;
    private static final int STT_SECONDS = 3;
    private static final int WK_WINDOW_SAMPLES_48K = 72000; // 1.5s @ 48kHz
    private static final int WK_HOP_SAMPLES_48K = 24000;    // 0.5s @ 48kHz

    private TextView mTextStatus;
    private TextView mTextResult;
    private TextView mTextPerf;
    private AwConformerJni mJni;
    private ConformerDecoder mDecoder;
    private boolean mRunning = false;

    // Wakeword quantization params
    private float wkInpScale, wkOutScale;
    private int wkInpZp, wkOutZp;

    private String copyAsset(String dir, String name) {
        File out = new File(getFilesDir(), dir + "_" + name);
        if (out.exists() && out.length() > 0) return out.getAbsolutePath();
        out.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(dir + "/" + name);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[1024 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) fos.write(buf, 0, len);
        } catch (IOException e) {
            Log.e(TAG, "copy failed: " + dir + "/" + name, e);
            return null;
        }
        return out.getAbsolutePath();
    }

    private void loadWakewordMeta(String metaPath) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(metaPath));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject json = new JSONObject(sb.toString());
            JSONObject inp = json.getJSONObject("Inputs").getJSONObject(
                    json.getJSONObject("Inputs").keys().next()).getJSONObject("quantize");
            wkInpScale = (float) inp.getDouble("scale");
            wkInpZp = inp.getInt("zero_point");
            JSONObject outp = json.getJSONObject("Outputs").getJSONObject(
                    json.getJSONObject("Outputs").keys().next()).getJSONObject("quantize");
            wkOutScale = (float) outp.getDouble("scale");
            wkOutZp = outp.getInt("zero_point");
            Log.d(TAG, "WK meta: inp s=" + wkInpScale + " z=" + wkInpZp + " out s=" + wkOutScale + " z=" + wkOutZp);
        } catch (Exception e) {
            Log.e(TAG, "WK meta load failed", e);
            wkInpScale = 0.063f; wkInpZp = 218; wkOutScale = 0.0077f; wkOutZp = 128;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);

        mTextStatus = new TextView(this);
        mTextStatus.setTextSize(18);
        mTextStatus.setText("초기화 중...");
        layout.addView(mTextStatus);

        mTextResult = new TextView(this);
        mTextResult.setTextSize(28);
        mTextResult.setPadding(8, 32, 8, 16);
        layout.addView(mTextResult);

        mTextPerf = new TextView(this);
        mTextPerf.setTextSize(11);
        mTextPerf.setPadding(8, 8, 8, 8);
        layout.addView(mTextPerf);

        ScrollView sv = new ScrollView(this);
        sv.addView(layout);
        setContentView(sv);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        // 모델 로드
        String confNb = copyAsset("models/Conformer", "network_binary.nb");
        String confVocab = copyAsset("models/Conformer", "vocab_correct.json");
        String wkNb = copyAsset("models/Wakeword", "network_binary.nb");
        String wkMeta = copyAsset("models/Wakeword", "nbg_meta.json");

        loadWakewordMeta(wkMeta);

        AwConformerJni.initNpu();
        mJni = new AwConformerJni();
        boolean confOk = mJni.init(confNb);
        boolean wkOk = mJni.initWakeword(wkNb);

        mDecoder = new ConformerDecoder();
        mDecoder.loadVocab(confVocab);

        Log.d(TAG, "Conformer: " + confOk + ", Wakeword: " + wkOk);

        if (confOk && wkOk) {
            mRunning = true;
            updateStatus("🎤 '하이 원더'라고 말하세요");
            new Thread(this::pipelineLoop).start();
        } else {
            updateStatus("NPU 초기화 실패 (conf=" + confOk + " wk=" + wkOk + ")");
        }
    }

    /**
     * HTK mel spectrogram for wakeword (40 mel, 151 frames, 1.5s audio @ 16kHz)
     */
    private byte[] computeWakewordMel(float[] audio16k) {
        int nFft = 512, winLen = 480, hopLen = 160, nMels = 40;
        int padLen = nFft / 2;
        int targetLen = 24000; // 1.5s

        // trim/pad to 1.5s
        float[] wav = new float[targetLen];
        System.arraycopy(audio16k, 0, wav, 0, Math.min(audio16k.length, targetLen));

        // reflect pad
        float[] padded = new float[targetLen + 2 * padLen];
        for (int i = 0; i < padLen; i++) {
            int idx = padLen - i;
            if (idx >= targetLen) idx = targetLen - 1;
            padded[i] = wav[idx];
        }
        System.arraycopy(wav, 0, padded, padLen, targetLen);
        for (int i = 0; i < padLen; i++) {
            int idx = targetLen - 2 - i;
            if (idx < 0) idx = 0;
            padded[padLen + targetLen + i] = wav[idx];
        }

        // hann window (symmetric)
        float[] hann = new float[winLen];
        for (int i = 0; i < winLen; i++)
            hann[i] = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / (winLen - 1));

        // HTK mel filterbank
        int fftBins = nFft / 2 + 1;
        float[] allFreqs = new float[fftBins];
        for (int i = 0; i < fftBins; i++) allFreqs[i] = i * (float) SR_MODEL / nFft;

        float melMin = 2595f * (float) Math.log10(1 + 0f / 700f);
        float melMax = 2595f * (float) Math.log10(1 + (float) SR_MODEL / 2 / 700f);
        float[] melPts = new float[nMels + 2];
        for (int i = 0; i < nMels + 2; i++)
            melPts[i] = melMin + (melMax - melMin) * i / (nMels + 1);
        float[] hzPts = new float[nMels + 2];
        for (int i = 0; i < nMels + 2; i++)
            hzPts[i] = 700f * ((float) Math.pow(10, melPts[i] / 2595f) - 1);

        float[][] fb = new float[nMels][fftBins];
        for (int m = 0; m < nMels; m++) {
            float left = hzPts[m], center = hzPts[m + 1], right = hzPts[m + 2];
            for (int k = 0; k < fftBins; k++) {
                float f = allFreqs[k];
                if (f > left && f < center) fb[m][k] = (f - left) / (center - left);
                else if (f >= center && f < right) fb[m][k] = (right - f) / (right - center);
            }
        }

        // STFT + mel
        int nFrames = (padded.length - nFft) / hopLen + 1;
        if (nFrames > WK_FRAMES) nFrames = WK_FRAMES;
        float[][] logMel = new float[nMels][WK_FRAMES];

        for (int t = 0; t < nFrames; t++) {
            // window
            float[] frame = new float[nFft];
            for (int i = 0; i < winLen && (t * hopLen + i) < padded.length; i++)
                frame[i] = padded[t * hopLen + i] * hann[i];

            // FFT (DFT for simplicity — small model, speed not critical)
            float[] power = new float[fftBins];
            for (int k = 0; k < fftBins; k++) {
                float re = 0, im = 0;
                for (int n = 0; n < nFft; n++) {
                    float angle = (float) (2 * Math.PI * k * n / nFft);
                    re += frame[n] * Math.cos(angle);
                    im -= frame[n] * Math.sin(angle);
                }
                power[k] = re * re + im * im;
            }

            // mel filterbank
            for (int m = 0; m < nMels; m++) {
                float sum = 0;
                for (int k = 0; k < fftBins; k++) sum += fb[m][k] * power[k];
                logMel[m][t] = (float) Math.log(sum + 1e-6);
            }
        }

        // quantize: uint8
        byte[] out = new byte[nMels * WK_FRAMES];
        for (int m = 0; m < nMels; m++) {
            for (int t = 0; t < WK_FRAMES; t++) {
                float val = logMel[m][t] / wkInpScale + wkInpZp;
                int ival = Math.round(val);
                if (ival < 0) ival = 0;
                if (ival > 255) ival = 255;
                out[m * WK_FRAMES + t] = (byte) ival;
            }
        }
        return out;
    }

    private void pipelineLoop() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("마이크 권한 없음");
            return;
        }

        int bufSize = Math.max(
                AudioRecord.getMinBufferSize(SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                SR_MIC * 2 * 2);

        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        recorder.startRecording();

        short[] ringBuffer = new short[WK_WINDOW_SAMPLES_48K];
        int ringPos = 0;
        boolean bufferFull = false;

        while (mRunning) {
            // 0.5초 읽기
            short[] chunk = new short[WK_HOP_SAMPLES_48K];
            int read = recorder.read(chunk, 0, WK_HOP_SAMPLES_48K);
            if (read <= 0) continue;

            for (int i = 0; i < read; i++) {
                ringBuffer[ringPos % WK_WINDOW_SAMPLES_48K] = chunk[i];
                ringPos++;
            }
            if (ringPos >= WK_WINDOW_SAMPLES_48K) bufferFull = true;
            if (!bufferFull) continue;

            // ring → linear
            short[] audio48k = new short[WK_WINDOW_SAMPLES_48K];
            int start = ringPos % WK_WINDOW_SAMPLES_48K;
            for (int i = 0; i < WK_WINDOW_SAMPLES_48K; i++)
                audio48k[i] = ringBuffer[(start + i) % WK_WINDOW_SAMPLES_48K];

            // 48k → 16k
            int len16k = WK_WINDOW_SAMPLES_48K / 3;
            float[] audio16k = new float[len16k];
            for (int i = 0; i < len16k; i++) {
                float srcIdx = i * 3.0f;
                int idx0 = (int) srcIdx;
                float frac = srcIdx - idx0;
                int idx1 = Math.min(idx0 + 1, WK_WINDOW_SAMPLES_48K - 1);
                audio16k[i] = ((1 - frac) * audio48k[idx0] + frac * audio48k[idx1]) / 32768.0f;
            }

            // Wakeword mel (C/KissFFT — fast) + NPU
            long tWkMel0 = System.currentTimeMillis();
            byte[] wkMel = mJni.computeWakewordMel(audio16k, wkInpScale, wkInpZp);
            long tWkMel1 = System.currentTimeMillis();
            float[] wkProbs = mJni.runWakeword(wkMel, wkOutScale, wkOutZp);
            long tWkNpu1 = System.currentTimeMillis();

            if (wkProbs == null || wkProbs.length < 2) continue;
            float wakeProb = wkProbs[1];
            long wkMelMs = tWkMel1 - tWkMel0;
            long wkNpuMs = tWkNpu1 - tWkMel1;

            if (wakeProb >= WK_THRESHOLD) {
                Log.d(TAG, String.format("WAKEWORD DETECTED! prob=%.2f mel=%dms npu=%dms", wakeProb, wkMelMs, wkNpuMs));
                updateStatus(String.format("✅ '하이 원더' 감지! (prob=%.2f, mel=%dms, npu=%dms) STT 시작...", wakeProb, wkMelMs, wkNpuMs));

                // 3초 STT 녹음
                int sttSamples = SR_MIC * STT_SECONDS;
                short[] sttBuf = new short[sttSamples];
                int sttRead = 0;
                while (sttRead < sttSamples) {
                    int r = recorder.read(sttBuf, sttRead, Math.min(4096, sttSamples - sttRead));
                    if (r <= 0) break;
                    sttRead += r;
                }

                // resample
                long tStart = System.currentTimeMillis();
                int sttLen16k = sttRead / 3;
                float[] stt16k = new float[sttLen16k];
                for (int i = 0; i < sttLen16k; i++) {
                    float srcIdx = i * 3.0f;
                    int idx0 = (int) srcIdx;
                    float frac = srcIdx - idx0;
                    int idx1 = Math.min(idx0 + 1, sttRead - 1);
                    stt16k[i] = ((1 - frac) * sttBuf[idx0] + frac * sttBuf[idx1]) / 32768.0f;
                }

                // mel + NPU
                long tMel = System.currentTimeMillis();
                byte[] mel = mJni.computeMel(stt16k, CONF_MEL_SCALE, CONF_MEL_ZP);
                long tMelEnd = System.currentTimeMillis();

                long tNpu = System.currentTimeMillis();
                int[] argmax = mJni.runUint8(mel);
                long tNpuEnd = System.currentTimeMillis();

                String text = (argmax != null) ? mDecoder.decode(argmax) : "";
                long totalMs = System.currentTimeMillis() - tStart;

                final String resultText = text.isEmpty() ? "(인식 없음)" : text;
                final String perfText = String.format(
                        "━━━ Wakeword ━━━\n" +
                        "prob: %.2f (th=%.2f)\n" +
                        "mel: %dms | npu: %dms\n" +
                        "━━━ STT ━━━\n" +
                        "mel: %dms | npu: %dms\n" +
                        "total: %dms (녹음 제외)",
                        wakeProb, WK_THRESHOLD,
                        wkMelMs, wkNpuMs,
                        tMelEnd - tMel, tNpuEnd - tNpu,
                        totalMs);

                runOnUiThread(() -> {
                    mTextResult.setText(resultText);
                    mTextPerf.setText(perfText);
                });

                updateStatus("🎤 '하이 원더'라고 말하세요");

                // ring buffer 초기화 (이전 음성 잔여 감지 방지)
                java.util.Arrays.fill(ringBuffer, (short) 0);
                ringPos = 0;
                bufferFull = false;

                // 잠시 대기 (중복 감지 방지)
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
        }

        recorder.stop();
        recorder.release();
    }

    private void updateStatus(String s) {
        Log.d(TAG, s);
        runOnUiThread(() -> mTextStatus.setText(s));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mRunning = false;
        if (mJni != null) {
            mJni.releaseWakeword();
            mJni.release();
        }
    }
}
