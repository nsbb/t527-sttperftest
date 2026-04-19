package com.t527.wav2vecdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.t527.wav2vecdemo.conformer.AwConformerJni;
import com.t527.wav2vecdemo.conformer.ConformerDecoder;
import com.t527.wav2vecdemo.conformer.SileroVad;
import com.t527.wav2vecdemo.utils.DanjiServerSender;
import com.t527.wav2vecdemo.utils.TtsReceiverServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.json.JSONObject;

public class VadPipelineService extends Service {
    private static final String TAG = "VadPipelineService";
    private static final String CHANNEL_ID = "vad_pipeline_channel";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START_STT = "com.t527.vad_pipeline.ACTION_START_STT";
    public static final String ACTION_MIC_GRANTED = "com.t527.vad_pipeline.ACTION_MIC_GRANTED";

    // Conformer
    private static final float CONF_MEL_SCALE = 0.025880949571728706f;
    private static final int CONF_MEL_ZP = 84;
    private static final int SEQ_OUT = 76;
    private static final int STRIDE_OUT = 62;

    // Wakeword
    private static final float WK_THRESHOLD = 0.40f;

    // VAD
    private static final float VAD_THRESHOLD = 0.5f;
    private static final int VAD_WINDOW = 512;
    private static final int MAX_SPEECH_MS = 10000;
    private static final int SILENCE_TIMEOUT_MS = 800;

    // Audio
    private static final int SR_MIC = 48000;
    private static final int SR_MODEL = 16000;
    private static final int WK_WINDOW_48K = 72000;
    private static final int WK_HOP_48K = 24000;

    private AwConformerJni mJni;
    private ConformerDecoder mDecoder;
    private SileroVad mVad;
    private ToneGenerator mTone;
    private volatile boolean mRunning = false;
    private volatile boolean mSttRequested = false;
    private volatile boolean mMicGranted = false;
    private Handler mHandler;
    private Thread mPipelineThread;
    private TtsReceiverServer mTtsServer;

    private static final int MAX_OVERLAY = 3;
    private static final int OVERLAY_BASE_Y = 300;   // 첫 메시지 Y offset (화면 하단 쪽)
    private static final int OVERLAY_GAP = 160;       // 메시지 간 간격 (px)
    private final Deque<TextView> mOverlayQueue = new ArrayDeque<>();

    private float wkInpScale, wkOutScale;
    private int wkInpZp, wkOutZp;
    private String mWkNbPath;
    private String mLastIp = "";

    private final Runnable mIpChecker = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            String ip = getDeviceIp();
            if (!ip.equals(mLastIp)) {
                mLastIp = ip;
                if (!ip.isEmpty()) {
                    showToast("IP: " + ip + ":8030");
                    Log.d(TAG, "Network IP: " + ip);
                }
            }
            mHandler.postDelayed(this, 5000);
        }
    };

    private String getDeviceIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getDeviceIp failed", e);
        }
        return "";
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        try { Runtime.getRuntime().exec(new String[]{"setprop", "vendor.audio.output.active.mic", "DMIC"}).waitFor(); } catch (Exception e) {}
        mHandler = new Handler(Looper.getMainLooper());
        DanjiServerSender.init(this);
        mTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);

        createNotificationChannel();
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("음성 AI 서비스")
                .setContentText("'하이 원더' 대기 중")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);

        initModels();

        // TTS 수신 HTTP 서버 (port 8030)
        try {
            mTtsServer = new TtsReceiverServer(this);
            mTtsServer.start();
            Log.d(TAG, "TTS receiver server started on port 8030");
        } catch (Exception e) {
            Log.e(TAG, "TTS server start failed", e);
        }

    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "VAD Pipeline", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("음성 AI 백그라운드 서비스");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private String copyAsset(String dir, String name) {
        File out = new File(getFilesDir(), dir.replace("/", "_") + "_" + name);
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
        } catch (Exception e) {
            Log.e(TAG, "WK meta load failed", e);
            wkInpScale = 0.063f; wkInpZp = 218; wkOutScale = 0.0077f; wkOutZp = 128;
        }
    }

    private void initModels() {
        String confNb = copyAsset("models/Conformer", "network_binary.nb");
        String confVocab = copyAsset("models/Conformer", "vocab_correct.json");
        String wkNb = copyAsset("models/Wakeword", "network_binary.nb");
        String wkMeta = copyAsset("models/Wakeword", "nbg_meta.json");
        String vadOnnx = copyAsset("models/VAD", "silero_vad.onnx");

        loadWakewordMeta(wkMeta);

        AwConformerJni.initNpu();
        mJni = new AwConformerJni();
        boolean confOk = mJni.init(confNb);
        mWkNbPath = wkNb;
        boolean wkOk = mJni.initWakeword(wkNb);

        mDecoder = new ConformerDecoder();
        mDecoder.loadVocab(confVocab);

        mVad = new SileroVad();
        mVad.setThreshold(VAD_THRESHOLD);
        boolean vadOk = mVad.init(vadOnnx);

        Log.d(TAG, "Conformer: " + confOk + ", Wakeword: " + wkOk + ", VAD: " + vadOk);

        if (confOk && wkOk && vadOk) {
            mRunning = true;
            showToast("음성 AI 서비스 대기 중 (마이크 대기)");
        } else {
            showToast("모델 초기화 실패");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_MIC_GRANTED.equals(action)) {
                Log.d(TAG, "BR: mic granted, starting VT");
                if (!mMicGranted) {
                    mMicGranted = true;
                    mPipelineThread = new Thread(this::pipelineLoop);
                    mPipelineThread.start();
                    showToast("마이크 획득 - VT 시작");
                }
            } else if (ACTION_START_STT.equals(action)) {
                Log.d(TAG, "BR: STT requested");
                mSttRequested = true;
            }
        }
        return START_STICKY;
    }

    private float[] resample48to16(short[] pcm48k, int len) {
        int newLen = len / 3;
        float[] out = new float[newLen];
        for (int i = 0; i < newLen; i++) {
            float srcIdx = i * 3.0f;
            int idx0 = (int) srcIdx;
            float frac = srcIdx - idx0;
            int idx1 = Math.min(idx0 + 1, len - 1);
            out[i] = ((1 - frac) * pcm48k[idx0] + frac * pcm48k[idx1]) / 32768.0f;
        }
        return out;
    }

    private void pipelineLoop() {
        int bufSize = Math.max(
                AudioRecord.getMinBufferSize(SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                SR_MIC * 2 * 2);
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        recorder.startRecording();
        Log.d(TAG, "AudioRecord: source=VOICE_RECOGNITION, rate=" + SR_MIC + ", state=" + recorder.getState());
        // NOTE: 새 월패드에서는 com.android.inputdevices 등 uid:1000 시스템 서비스가 UNPROCESSED로
        // mic을 항상 점유해서 silenced:true됨. 구 월패드(00f75c)에서는 정상 동작.

        // AlsaCapture placeholder (not used - ALSA is exclusively held by AudioFlinger)
        AlsaCapture alsa = null;

        short[] ringBuffer = new short[WK_WINDOW_48K];
        int ringPos = 0;
        boolean bufferFull = false;
        int logCounter = 0;

        Log.d(TAG, "Pipeline loop started");

        while (mRunning) {
            // BR로 STT 요청이 들어왔으면 바로 STT 실행
            if (mSttRequested) {
                mSttRequested = false;
                Log.d(TAG, "BR trigger: running STT now");
                showToast("STT 시작...");
                runStt(recorder);
                mJni.releaseWakeword();
                mJni.initWakeword(mWkNbPath);
                java.util.Arrays.fill(ringBuffer, (short) 0);
                ringPos = 0; bufferFull = false;
                continue;
            }

            // === VT MODE: 0.5초마다 wakeword 체크 ===
            short[] chunk = new short[WK_HOP_48K];
            int read = recorder.read(chunk, 0, WK_HOP_48K);
            if (read <= 0) continue;

            // 오디오 레벨 로그 (2초마다)
            logCounter++;
            if (logCounter % 4 == 0) {
                short maxVal = 0;
                long sum = 0;
                for (int s = 0; s < read; s++) {
                    short abs = (short) Math.abs(chunk[s]);
                    if (abs > maxVal) maxVal = abs;
                    sum += abs;
                }
                int avg = (int)(sum / read);
                Log.d(TAG, String.format("AudioLevel: max=%d avg=%d (0=무음, 100+=소리있음)", maxVal, avg));
            }

            for (int i = 0; i < read; i++) {
                ringBuffer[ringPos % WK_WINDOW_48K] = chunk[i];
                ringPos++;
            }
            if (ringPos >= WK_WINDOW_48K) bufferFull = true;
            if (!bufferFull) continue;

            short[] audio48k = new short[WK_WINDOW_48K];
            int start = ringPos % WK_WINDOW_48K;
            for (int i = 0; i < WK_WINDOW_48K; i++)
                audio48k[i] = ringBuffer[(start + i) % WK_WINDOW_48K];

            float[] audio16k = resample48to16(audio48k, WK_WINDOW_48K);

            long tWkMel0 = System.currentTimeMillis();
            byte[] wkMel = mJni.computeWakewordMel(audio16k, wkInpScale, wkInpZp);
            long tWkMel1 = System.currentTimeMillis();
            // 진단: mel 값, audio 값, scale/zp 확인
            if (logCounter % 4 == 0 && wkMel != null) {
                float audioMax = 0;
                for (int a = 0; a < Math.min(100, audio16k.length); a++)
                    audioMax = Math.max(audioMax, Math.abs(audio16k[a]));
                Log.d(TAG, String.format("DIAG: melLen=%d mel[0..3]=[%d,%d,%d,%d] audioMax=%.4f scale=%.6f zp=%d",
                    wkMel.length, wkMel[0]&0xFF, wkMel[1]&0xFF, wkMel[2]&0xFF, wkMel[3]&0xFF,
                    audioMax, wkInpScale, wkInpZp));
            }
            float[] wkProbs = mJni.runWakeword(wkMel, wkOutScale, wkOutZp);
            long tWkNpu1 = System.currentTimeMillis();

            if (wkProbs == null || wkProbs.length < 2) continue;
            float wakeProb = wkProbs[1];

            if (wakeProb >= WK_THRESHOLD) {
                long vtMel = tWkMel1 - tWkMel0;
                long vtNpu = tWkNpu1 - tWkMel1;
                Log.d(TAG, String.format("WAKEWORD! prob=%.2f (mel=%dms, npu=%dms)", wakeProb, vtMel, vtNpu));
                showToast(String.format("'하이 원더' 감지!\n(mel %dms, npu %dms)", vtMel, vtNpu));
                runStt(recorder);
                // STT 후 wakeword reinit
                mJni.releaseWakeword();
                mJni.initWakeword(mWkNbPath);
                Log.d(TAG, "Wakeword model reinitialized");
                java.util.Arrays.fill(ringBuffer, (short) 0);
                ringPos = 0; bufferFull = false;
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
        }

        recorder.stop();
        recorder.release();
        Log.d(TAG, "Pipeline loop stopped");
    }

    private void runStt(AudioRecord recorder) {
        // wakeword 꼬리("더") 제거 + 명령어 보존 균형: 0.3초
        short[] flush = new short[SR_MIC * 3 / 10];  // 14400 = 0.3초
        recorder.read(flush, 0, flush.length);

        // 삐 소리 = "녹음 준비 완료, 말하세요" 신호
        if (mTone != null) mTone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);

        mVad.resetState();
        List<float[]> speechChunks = new ArrayList<>();
        int silenceFrames = 0;
        int speechFrames = 0;
        int vadFrameSize48k = VAD_WINDOW * 3;
        int maxFrames = MAX_SPEECH_MS * SR_MIC / 1000 / vadFrameSize48k;
        int silenceLimit = SILENCE_TIMEOUT_MS * SR_MODEL / 1000 / VAD_WINDOW;
        boolean speechStarted = false;
        long tSttStart = System.currentTimeMillis();

        while (mRunning && speechFrames < maxFrames) {
            short[] vadChunk48k = new short[vadFrameSize48k];
            int vadRead = recorder.read(vadChunk48k, 0, vadFrameSize48k);
            if (vadRead <= 0) continue;

            float[] vadAudio = resample48to16(vadChunk48k, vadRead);
            float vadProb = mVad.process(vadAudio);
            boolean isSpeech = vadProb >= VAD_THRESHOLD;

            if (isSpeech) {
                speechStarted = true;
                silenceFrames = 0;
                speechChunks.add(vadAudio);
                speechFrames++;
            } else if (speechStarted) {
                silenceFrames++;
                speechChunks.add(vadAudio);
                if (silenceFrames >= silenceLimit) break;
            } else {
                if (System.currentTimeMillis() - tSttStart > 9000) {
                    Log.d(TAG, "VAD: timeout, no speech");
                    showToast("(음성 없음)");
                    return;
                }
            }
        }

        // trailing silence 제거 (뒤에 "오" 붙는 현상 방지)
        int removeCount = Math.min(silenceFrames, speechChunks.size());
        for (int i = 0; i < removeCount; i++) {
            speechChunks.remove(speechChunks.size() - 1);
        }

        if (!speechStarted || speechChunks.isEmpty()) {
            showToast("(음성 없음)");
            return;
        }

        int totalSamples = speechChunks.size() * VAD_WINDOW;
        float speechOnlyDuration = speechFrames * VAD_WINDOW / (float) SR_MODEL;
        if (speechOnlyDuration < 0.5f) {
            Log.d(TAG, String.format("VAD: speech only %.2fs (total %.2fs), skipping",
                    speechOnlyDuration, totalSamples / (float) SR_MODEL));
            return;
        }
        float speechDuration = totalSamples / (float) SR_MODEL;

        float[] fullAudio = new float[totalSamples];
        for (int i = 0; i < speechChunks.size(); i++) {
            System.arraycopy(speechChunks.get(i), 0, fullAudio, i * VAD_WINDOW,
                    Math.min(speechChunks.get(i).length, VAD_WINDOW));
        }
        Log.d(TAG, String.format("VAD: speech %.2fs (%d samples)", speechDuration, totalSamples));

        // STT 슬라이딩 윈도우
        long tMelTotal = 0, tNpuTotal = 0;
        int WINDOW_SAMPLES = SR_MODEL * 301 / 100;
        int STRIDE_SAMPLES = SR_MODEL * 250 / 100;
        List<int[]> allArgmax = new ArrayList<>();
        int numChunks = 0;

        int pos = 0;
        while (pos < totalSamples) {
            int end = Math.min(pos + WINDOW_SAMPLES, totalSamples);
            int chunkLen = end - pos;
            float[] chunkAudio = new float[chunkLen];
            System.arraycopy(fullAudio, pos, chunkAudio, 0, chunkLen);

            long tM0 = System.currentTimeMillis();
            byte[] mel = mJni.computeMel(chunkAudio, CONF_MEL_SCALE, CONF_MEL_ZP);
            long tM1 = System.currentTimeMillis();
            tMelTotal += (tM1 - tM0);

            long tN0 = System.currentTimeMillis();
            int[] argmax = mJni.runUint8(mel);
            long tN1 = System.currentTimeMillis();
            tNpuTotal += (tN1 - tN0);

            if (argmax != null) allArgmax.add(argmax);
            numChunks++;
            if (end >= totalSamples) break;
            pos += STRIDE_SAMPLES;
        }

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
        String resultText = text.isEmpty() ? "(인식 없음)" : text;

        Log.d(TAG, String.format("STT: [%s] (%.1fs, mel=%dms, npu=%dms, %d chunks)",
                resultText, speechDuration, tMelTotal, tNpuTotal, numChunks));

        showToast(String.format("%s\n(mel %dms, npu %dms, %d chunks)", resultText, tMelTotal, tNpuTotal, numChunks));

        // 단지서버로 STT 결과 전송
        if (!text.isEmpty()) {
            Log.d(TAG, "Sending STT result to danji server: " + resultText);
            DanjiServerSender.send(resultText, (success, message) -> {
                if (success) {
                    Log.d(TAG, "Server send OK: " + message);
                    showToast("서버 전송 완료");
                } else {
                    Log.e(TAG, "Server send FAIL: " + message);
                    showToast("서버 전송 실패: " + message);
                }
            });
        }
    }

    private void removeOverlay(TextView tv) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.removeView(tv);
        } catch (Exception e) {}
        mOverlayQueue.remove(tv);
        // 남은 오버레이 위치 재배치
        int idx = 0;
        for (TextView v : mOverlayQueue) {
            try {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) v.getLayoutParams();
                p.y = OVERLAY_BASE_Y + idx * OVERLAY_GAP;
                ((WindowManager) getSystemService(WINDOW_SERVICE)).updateViewLayout(v, p);
            } catch (Exception e) {}
            idx++;
        }
    }

    private void showIpAddress() {
        String ip = getDeviceIp();
        if (!ip.isEmpty()) {
            mLastIp = ip;
            showToast("IP: " + ip + ":8030");
        } else {
            showToast("네트워크 없음 (랜선 연결 대기)");
        }
    }

    private void showToast(String msg) {
        mHandler.post(() -> {
            Log.d(TAG, "showToast: " + msg);
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);

                // 최대 개수 초과 시 가장 오래된 것 제거
                while (mOverlayQueue.size() >= MAX_OVERLAY) {
                    TextView oldest = mOverlayQueue.pollFirst();
                    if (oldest != null) {
                        try { wm.removeView(oldest); } catch (Exception e) {}
                    }
                }

                TextView tv = new TextView(this);
                tv.setText(msg);
                tv.setTextSize(32);
                tv.setTextColor(Color.WHITE);
                tv.setBackgroundColor(0xDD6A0DAD);
                tv.setPadding(48, 32, 48, 32);
                tv.setMaxLines(2);

                int yPos = OVERLAY_BASE_Y + mOverlayQueue.size() * OVERLAY_GAP;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                params.y = yPos;

                wm.addView(tv, params);
                mOverlayQueue.addLast(tv);

                mHandler.postDelayed(() -> removeOverlay(tv), 5000);
            } catch (Exception e) {
                Log.e(TAG, "Overlay failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        mRunning = false;
        mHandler.removeCallbacks(mIpChecker);
        if (mTtsServer != null) mTtsServer.stopServer();
        if (mTone != null) { mTone.release(); mTone = null; }
        if (mJni != null) { mJni.releaseWakeword(); mJni.release(); }
        if (mVad != null) mVad.release();
    }
}
