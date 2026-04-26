package com.t527.wav2vecdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
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
import com.t527.wav2vecdemo.conformer.SttTorchscriptMel;
import com.t527.wav2vecdemo.utils.DanjiServerSender;
import com.t527.wav2vecdemo.utils.TtsReceiverServer;
// PERF-TEST-CHANGE: 측정 인프라
import com.t527.wav2vecdemo.perftest.PerfTestConfig;
import com.t527.wav2vecdemo.perftest.WavWriter;
import com.t527.wav2vecdemo.perftest.CsvLogger;
import com.t527.wav2vecdemo.perftest.NextFileReceiver;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    private static final int CONF_MEL_BINS = 80;
    private static final int SEQ_OUT = 76;
    private static final int WINDOW_FRAMES = 301;
    private static final int STRIDE_FRAMES = 250;
    private static final int STRIDE_OUT = 63;

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

    // B3: 48k→16k anti-alias decimation FIR.
    // 63-tap Hamming-windowed sinc, cutoff 7500 Hz @ 48 kHz fs.
    // 통과대역 0~7000 Hz 평탄(0 dB), 9 kHz 이상 −54 dB. 합 = 1.0 (DC unity).
    // 기존 2점 linear interp는 8 kHz 위 신호를 alias로 폴드해 mel 자음 영역 왜곡.
    private static final float[] LPF_FIR_48K_TO_16K_63 = new float[] {
            -0.0006839896f, -0.0008085108f, -0.0001917471f, +0.0008138334f,
            +0.0013580218f, +0.0006465351f, -0.0011507747f, -0.0025364808f,
            -0.0017176602f, +0.0014329691f, +0.0044168503f, +0.0038027603f,
            -0.0012445555f, -0.0069486388f, -0.0073330924f, +0.0000000000f,
            +0.0099533348f, +0.0128247091f, +0.0031347828f, -0.0131440116f,
            -0.0211057152f, -0.0095555478f, +0.0161664691f, +0.0341450318f,
            +0.0224921791f, -0.0186549254f, -0.0589144170f, -0.0542500420f,
            +0.0202922127f, +0.1458650904f, +0.2644204213f, +0.3129498158f,
            +0.2644204213f, +0.1458650904f, +0.0202922127f, -0.0542500420f,
            -0.0589144170f, -0.0186549254f, +0.0224921791f, +0.0341450318f,
            +0.0161664691f, -0.0095555478f, -0.0211057152f, -0.0131440116f,
            +0.0031347828f, +0.0128247091f, +0.0099533348f, +0.0000000000f,
            -0.0073330924f, -0.0069486388f, -0.0012445555f, +0.0038027603f,
            +0.0044168503f, +0.0014329691f, -0.0017176602f, -0.0025364808f,
            -0.0011507747f, +0.0006465351f, +0.0013580218f, +0.0008138334f,
            -0.0001917471f, -0.0008085108f, -0.0006839896f
    };

    private AwConformerJni mJni;
    private ConformerDecoder mDecoder;
    private SileroVad mVad;
    private SttTorchscriptMel mSttMel;
    private ToneGenerator mTone;
    private volatile boolean mRunning = false;
    private volatile boolean mSttRequested = false;
    private volatile boolean mMicGranted = false;
    private Handler mHandler;
    private Thread mPipelineThread;
    private TtsReceiverServer mTtsServer;
    // PERF-TEST-CHANGE: CSV 로거 + 카운터 (이하 신규)
    private CsvLogger mCsv;
    private int mRecCounter = 0;
    private String mSessionId = "";

    private static final int MAX_OVERLAY = 3;
    private static final int OVERLAY_BASE_Y = 300;   // 첫 메시지 Y offset (화면 하단 쪽)
    private static final int OVERLAY_GAP = 160;       // 메시지 간 간격 (px)
    private final Deque<TextView> mOverlayQueue = new ArrayDeque<>();

    private float wkInpScale, wkOutScale;
    private int wkInpZp, wkOutZp;
    private String mWkNbPath;
    private String mLastIp = "";
    private Thread mDatasetThread;

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
        // PERF-TEST-CHANGE: 측정 모드에서는 단지서버 init/send 모두 skip (네트워크 latency 배제)
        if (!PerfTestConfig.PERF_TEST_MODE) {
            DanjiServerSender.init(this);
        }
        // PERF-TEST-CHANGE: CSV 로거 세션 시작
        if (PerfTestConfig.PERF_TEST_MODE) {
            PerfTestConfig.initPaths(getExternalFilesDir(null));
            mSessionId = CsvLogger.newSessionId();
            mCsv = new CsvLogger(PerfTestConfig.RESULTS_DIR, mSessionId);
            Log.d(TAG, "PERF: csv=" + mCsv.getPath());
            // NextFileReceiver 동적 등록 (Manifest 등록 + 동적 등록 둘 다 가능, 동적이 lifecycle 명확)
            try {
                android.content.IntentFilter f = new android.content.IntentFilter(PerfTestConfig.ACTION_NEXT_FILE);
                registerReceiver(new NextFileReceiver(), f, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) { Log.e(TAG, "NextFileReceiver register fail", t); }
        }
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

        if (!(PerfTestConfig.PERF_TEST_MODE && !PerfTestConfig.MIC_TEST_USE_VAD)) {
            // TTS 수신 HTTP 서버 (port 8030)
            try {
                mTtsServer = new TtsReceiverServer(this);
                mTtsServer.start();
                Log.d(TAG, "TTS receiver server started on port 8030");
            } catch (Exception e) {
                Log.e(TAG, "TTS server start failed", e);
            }
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
        AwConformerJni.initNpu();
        // N series: configure mel-domain normalization mode
        AwConformerJni.nativeSetMelNormMode(
                PerfTestConfig.MEL_NORM_MODE,
                PerfTestConfig.MEL_NORM_VOICED_PCT,
                PerfTestConfig.MEL_NORM_FLOOR_LOG);
        mJni = new AwConformerJni();
        boolean confOk = mJni.init(confNb);

        mDecoder = new ConformerDecoder();
        mDecoder.loadVocab(confVocab);
        boolean sttMelOk = true;
        if (PerfTestConfig.USE_TORCHSCRIPT_STT_MEL) {
            String sttMel = copyAsset("models/Conformer", "stt_log_mel.pt");
            mSttMel = new SttTorchscriptMel();
            sttMelOk = sttMel != null && mSttMel.init(sttMel);
        }

        if (PerfTestConfig.PERF_TEST_MODE
                && (PerfTestConfig.STT_ONLY_DATASET_MODE || !PerfTestConfig.MIC_TEST_USE_VAD)) {
            if (confOk && sttMelOk) {
                mRunning = true;
                showToast("STT 테스트 모드 준비 완료");
            } else {
                showToast("Conformer/STT mel 초기화 실패");
            }
            return;
        }

        String wkNb = copyAsset("models/Wakeword", "network_binary.nb");
        String wkMeta = copyAsset("models/Wakeword", "nbg_meta.json");
        String vadOnnx = copyAsset("models/VAD", "silero_vad.onnx");
        loadWakewordMeta(wkMeta);
        mWkNbPath = wkNb;
        boolean wkOk = mJni.initWakeword(wkNb);
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
                Log.d(TAG, "BR: mic granted");
                if (!mMicGranted) {
                    mMicGranted = true;
                    if (PerfTestConfig.PERF_TEST_MODE && PerfTestConfig.STT_ONLY_DATASET_MODE) {
                        mDatasetThread = new Thread(this::runDatasetSttLoop, "stt-dataset-loop");
                        mDatasetThread.start();
                        showToast("STT 테스트셋 실행 시작");
                    } else if (PerfTestConfig.PERF_TEST_MODE && !PerfTestConfig.MIC_TEST_USE_VAD) {
                        mPipelineThread = new Thread(this::micSttOnlyLoop, "stt-only-mic-loop");
                        mPipelineThread.start();
                        showToast("STT 마이크 테스트 대기");
                    } else {
                        mPipelineThread = new Thread(this::pipelineLoop);
                        mPipelineThread.start();
                        showToast("마이크 획득 - VT 시작");
                    }
                }
            } else if (ACTION_START_STT.equals(action)) {
                Log.d(TAG, "BR: STT requested");
                mSttRequested = true;
            }
        }
        return START_STICKY;
    }

    private float[] resample48to16(short[] pcm48k, int len) {
        final float[] h = LPF_FIR_48K_TO_16K_63;
        final int N = h.length;
        final int half = N / 2;
        int newLen = len / 3;
        float[] out = new float[newLen];
        for (int i = 0; i < newLen; i++) {
            int center = i * 3;
            float acc = 0f;
            for (int k = 0; k < N; k++) {
                int j = center + k - half;
                float s = (j >= 0 && j < len) ? (float) pcm48k[j] : 0f;
                acc += s * h[k];
            }
            out[i] = acc / 32768.0f;
        }
        return out;
    }

    private void micSttOnlyLoop() {
        int bufSize = Math.max(
                AudioRecord.getMinBufferSize(SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                SR_MIC * 2 * 2);
        AudioRecord recorder = new AudioRecord(PerfTestConfig.MIC_AUDIO_SOURCE,
                SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        if (PerfTestConfig.MIC_DISABLE_AUDIO_EFFECTS) {
            disableAudioEffects(recorder.getAudioSessionId());
        }
        recorder.startRecording();
        Log.d(TAG, "STT-only mic loop started. source=" + PerfTestConfig.MIC_AUDIO_SOURCE + ", AudioRecord state=" + recorder.getState());
        short[] discard = new short[SR_MIC / 50];

        while (mRunning) {
            if (mSttRequested) {
                mSttRequested = false;
                Log.d(TAG, "BR trigger: running fixed STT capture");
                runSttFixedCapture(recorder);
            } else {
                recorder.read(discard, 0, discard.length);
            }
        }

        recorder.stop();
        recorder.release();
        Log.d(TAG, "STT-only mic loop stopped");
    }

    private void disableAudioEffects(int audioSessionId) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl agc = AutomaticGainControl.create(audioSessionId);
                if (agc != null) {
                    agc.setEnabled(false);
                    Log.d(TAG, "AudioEffect: AGC disabled, enabled=" + agc.getEnabled());
                    agc.release();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioEffect: AGC disable failed", e);
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(audioSessionId);
                if (ns != null) {
                    ns.setEnabled(false);
                    Log.d(TAG, "AudioEffect: NS disabled, enabled=" + ns.getEnabled());
                    ns.release();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioEffect: NS disable failed", e);
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler aec = AcousticEchoCanceler.create(audioSessionId);
                if (aec != null) {
                    aec.setEnabled(false);
                    Log.d(TAG, "AudioEffect: AEC disabled, enabled=" + aec.getEnabled());
                    aec.release();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioEffect: AEC disable failed", e);
        }
    }

    private static class DatasetItem {
        final String fileName;
        final String gt;

        DatasetItem(String fileName, String gt) {
            this.fileName = fileName;
            this.gt = gt;
        }
    }

    private static class WavData {
        final short[] pcm16;
        final int sampleRate;

        WavData(short[] pcm16, int sampleRate) {
            this.pcm16 = pcm16;
            this.sampleRate = sampleRate;
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (c == ',' && !inQuote) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private int findColumnIndex(List<String> headers, String... candidates) {
        for (String c : candidates) {
            for (int i = 0; i < headers.size(); i++) {
                if (c.equalsIgnoreCase(headers.get(i).trim())) return i;
            }
        }
        return -1;
    }

    private List<DatasetItem> loadDatasetItems(String csvPath) {
        List<DatasetItem> items = new ArrayList<>();
        File csv = new File(csvPath);
        if (!csv.exists()) {
            Log.e(TAG, "Dataset CSV not found: " + csvPath);
            return items;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String header = br.readLine();
            if (header == null) return items;
            List<String> headers = parseCsvLine(header);
            int fileIdx = findColumnIndex(headers, "FileName", "file_path", "filepath", "path");
            int gtIdx = findColumnIndex(headers, "gt", "GT", "transcript", "text");
            if (fileIdx < 0) {
                Log.e(TAG, "Dataset CSV missing file column");
                return items;
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> cols = parseCsvLine(line);
                if (fileIdx >= cols.size()) continue;
                String f = cols.get(fileIdx).trim();
                String gt = (gtIdx >= 0 && gtIdx < cols.size()) ? cols.get(gtIdx).trim() : "";
                if (!f.isEmpty()) items.add(new DatasetItem(f, gt));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load dataset CSV", e);
        }
        return items;
    }

    private String resolveDatasetFilePath(String fileName) {
        if (fileName.startsWith("/")) return fileName;
        return new File(PerfTestConfig.TESTSET_DIR, fileName).getAbsolutePath();
    }

    private WavData readWavPcm16(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] riff = new byte[12];
            int n = fis.read(riff);
            if (n < 12) return null;
            if (!(riff[0] == 'R' && riff[1] == 'I' && riff[2] == 'F' && riff[3] == 'F')) return null;
            if (!(riff[8] == 'W' && riff[9] == 'A' && riff[10] == 'V' && riff[11] == 'E')) return null;

            int channels = -1;
            int sampleRate = -1;
            int bitsPerSample = -1;
            byte[] pcmBytes = null;
            byte[] chunkHeader = new byte[8];
            while (fis.read(chunkHeader) == 8) {
                String chunkId = new String(chunkHeader, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (chunkSize < 0) return null;
                byte[] chunk = new byte[chunkSize];
                int read = 0;
                while (read < chunkSize) {
                    int r = fis.read(chunk, read, chunkSize - read);
                    if (r <= 0) break;
                    read += r;
                }
                if (read < chunkSize) return null;
                if ("fmt ".equals(chunkId)) {
                    if (chunkSize < 16) return null;
                    channels = ((chunk[3] & 0xFF) << 8) | (chunk[2] & 0xFF);
                    sampleRate = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    bitsPerSample = ((chunk[15] & 0xFF) << 8) | (chunk[14] & 0xFF);
                } else if ("data".equals(chunkId)) {
                    pcmBytes = chunk;
                    break;
                }
                if ((chunkSize & 1) == 1) fis.skip(1);
            }
            if (channels != 1 || bitsPerSample != 16 || pcmBytes == null || pcmBytes.length <= 0) {
                Log.e(TAG, "Unsupported wav format: ch=" + channels + " bps=" + bitsPerSample + " path=" + path);
                return null;
            }
            int read = pcmBytes.length;
            int samples = read / 2;
            short[] pcm = new short[samples];
            ByteBuffer bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < samples; i++) pcm[i] = bb.getShort();
            return new WavData(pcm, sampleRate);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read wav: " + path, e);
            return null;
        }
    }

    private short[] resamplePcmTo16k(short[] src, int srcRate) {
        if (srcRate == SR_MODEL) return src;
        int dstLen = (int) ((long) src.length * SR_MODEL / srcRate);
        short[] dst = new short[dstLen];
        for (int i = 0; i < dstLen; i++) {
            float srcPos = i * (float) srcRate / SR_MODEL;
            int idx0 = (int) srcPos;
            float frac = srcPos - idx0;
            int idx1 = Math.min(idx0 + 1, src.length - 1);
            dst[i] = (short) ((1f - frac) * src[idx0] + frac * src[idx1]);
        }
        return dst;
    }

    private float[] toFloatAudio(short[] pcm16) {
        float[] out = new float[pcm16.length];
        for (int i = 0; i < pcm16.length; i++) out[i] = pcm16[i] / 32768.0f;
        return out;
    }

    private static class SttResult {
        final String text;
        final long melMs;
        final long npuMs;
        final int chunks;

        SttResult(String text, long melMs, long npuMs, int chunks) {
            this.text = text;
            this.melMs = melMs;
            this.npuMs = npuMs;
            this.chunks = chunks;
        }
    }

    private SttResult runConformerStt(float[] audio16k) {
        if (!PerfTestConfig.USE_TORCHSCRIPT_STT_MEL) {
            return runConformerSttWithCMel(audio16k);
        }
        if (mSttMel == null || !mSttMel.isInit()) {
            Log.e(TAG, "STT TorchScript mel is not initialized");
            return new SttResult("", 0, 0, 0);
        }

        long tM0 = System.currentTimeMillis();
        SttTorchscriptMel.MelResult fullMel = mSttMel.compute(audio16k);
        long tM1 = System.currentTimeMillis();

        List<int[]> allArgmax = new ArrayList<>();
        long tNpuTotal = 0;
        int numChunks = 0;

        int startFrame = 0;
        while (startFrame < fullMel.nFrames) {
            byte[] mel = mSttMel.quantizeChunk(fullMel, startFrame, CONF_MEL_SCALE, CONF_MEL_ZP);

            long tN0 = System.currentTimeMillis();
            int[] argmax = mJni.runUint8(mel);
            long tN1 = System.currentTimeMillis();
            tNpuTotal += (tN1 - tN0);

            if (argmax != null) allArgmax.add(argmax);
            numChunks++;
            if (startFrame + WINDOW_FRAMES >= fullMel.nFrames) break;
            startFrame += STRIDE_FRAMES;
        }

        List<Integer> mergedIds = new ArrayList<>();
        // O series: configurable chunk merge with drop_left/drop_right and stride_out override
        int strideOut = (PerfTestConfig.CHUNK_STRIDE_OUT_OVERRIDE > 0)
                ? PerfTestConfig.CHUNK_STRIDE_OUT_OVERRIDE : STRIDE_OUT;
        int dropLeft = Math.max(0, PerfTestConfig.CHUNK_DROP_LEFT);
        int dropRight = Math.max(0, PerfTestConfig.CHUNK_DROP_RIGHT);
        for (int ci = 0; ci < allArgmax.size(); ci++) {
            int[] ids = allArgmax.get(ci);
            int useFrames = (ci < allArgmax.size() - 1) ? strideOut : SEQ_OUT;
            int startT = (ci > 0) ? dropLeft : 0;
            int endT = useFrames - ((ci < allArgmax.size() - 1) ? dropRight : 0);
            for (int t = startT; t < endT && t < ids.length; t++) mergedIds.add(ids[t]);
        }
        int[] merged = new int[mergedIds.size()];
        for (int i = 0; i < merged.length; i++) merged[i] = mergedIds.get(i);

        return new SttResult(mDecoder.decode(merged), tM1 - tM0, tNpuTotal, numChunks);
    }

    private SttResult runConformerSttWithCMel(float[] audio16k) {
        long tMelTotal = 0;
        long tNpuTotal = 0;
        List<int[]> allArgmax = new ArrayList<>();

        long tM0 = System.currentTimeMillis();
        byte[] melChunks = mJni.computeMelChunks(audio16k, CONF_MEL_SCALE, CONF_MEL_ZP);
        long tM1 = System.currentTimeMillis();
        tMelTotal += (tM1 - tM0);

        final int chunkSize = CONF_MEL_BINS * WINDOW_FRAMES;
        int numChunks = melChunks == null ? 0 : melChunks.length / chunkSize;
        for (int ci = 0; ci < numChunks; ci++) {
            byte[] mel = new byte[chunkSize];
            System.arraycopy(melChunks, ci * chunkSize, mel, 0, chunkSize);

            long tN0 = System.currentTimeMillis();
            int[] argmax = mJni.runUint8(mel);
            long tN1 = System.currentTimeMillis();
            tNpuTotal += (tN1 - tN0);

            if (argmax != null) allArgmax.add(argmax);
        }

        List<Integer> mergedIds = new ArrayList<>();
        // O series: configurable chunk merge with drop_left/drop_right and stride_out override
        int strideOut = (PerfTestConfig.CHUNK_STRIDE_OUT_OVERRIDE > 0)
                ? PerfTestConfig.CHUNK_STRIDE_OUT_OVERRIDE : STRIDE_OUT;
        int dropLeft = Math.max(0, PerfTestConfig.CHUNK_DROP_LEFT);
        int dropRight = Math.max(0, PerfTestConfig.CHUNK_DROP_RIGHT);
        for (int ci = 0; ci < allArgmax.size(); ci++) {
            int[] ids = allArgmax.get(ci);
            int useFrames = (ci < allArgmax.size() - 1) ? strideOut : SEQ_OUT;
            int startT = (ci > 0) ? dropLeft : 0;
            int endT = useFrames - ((ci < allArgmax.size() - 1) ? dropRight : 0);
            for (int t = startT; t < endT && t < ids.length; t++) mergedIds.add(ids[t]);
        }
        int[] merged = new int[mergedIds.size()];
        for (int i = 0; i < merged.length; i++) merged[i] = mergedIds.get(i);

        return new SttResult(mDecoder.decode(merged), tMelTotal, tNpuTotal, numChunks);
    }

    private void playPcm16(short[] pcm16, int sampleRate) {
        AudioTrack at = null;
        try {
            int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, pcm16.length * 2);
            at = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, buf, AudioTrack.MODE_STREAM);
            at.play();
            at.write(pcm16, 0, pcm16.length);
            at.stop();
        } catch (Exception e) {
            Log.e(TAG, "Audio playback failed", e);
        } finally {
            if (at != null) at.release();
        }
    }

    private void runDatasetSttLoop() {
        List<DatasetItem> items = loadDatasetItems(PerfTestConfig.TESTSET_CSV);
        if (items.isEmpty()) {
            Log.e(TAG, "No dataset items. expected csv: " + PerfTestConfig.TESTSET_CSV);
            showToast("테스트셋 CSV 없음");
            return;
        }
        Log.d(TAG, "Dataset mode started: items=" + items.size());
        for (int i = 0; i < items.size() && mRunning; i++) {
            DatasetItem item = items.get(i);
            String path = resolveDatasetFilePath(item.fileName);
            WavData wav = readWavPcm16(path);
            if (wav == null) {
                Log.e(TAG, "Skip unreadable wav: " + path);
                continue;
            }
            short[] pcm16k = resamplePcmTo16k(wav.pcm16, wav.sampleRate);
            if (PerfTestConfig.PLAY_AUDIO_BEFORE_INFER) {
                playPcm16(wav.pcm16, wav.sampleRate);
                try { Thread.sleep(PerfTestConfig.FILE_GAP_MS); } catch (InterruptedException ignored) {}
            }
            runSttOnDatasetAudio(item.fileName, item.gt, i, toFloatAudio(pcm16k));
            try { Thread.sleep(PerfTestConfig.FILE_GAP_MS); } catch (InterruptedException ignored) {}
        }
        Log.d(TAG, "Dataset mode finished");
        showToast("테스트셋 STT 완료");
    }

    private void runSttOnDatasetAudio(String fileName, String gt, int idx, float[] audio16k) {
        if (audio16k == null || audio16k.length == 0) return;
        long savedMs = System.currentTimeMillis();
        // mic preprocess (silence trim 등) — dataset 모드로 mic 녹음 재투입 시 효과 검증 가능
        audio16k = preprocessMicAudioForStt(audio16k);
        int totalSamples = audio16k.length;
        float speechDuration = totalSamples / (float) SR_MODEL;

        SttResult stt = runConformerStt(audio16k);
        String text = stt.text;
        long finishedMs = System.currentTimeMillis();
        long durationMs = finishedMs - savedMs;
        if (mCsv != null) {
            mCsv.append(fileName, gt, text, durationMs, savedMs, finishedMs, speechDuration, stt.melMs, stt.npuMs, stt.chunks);
        }
        Log.d(TAG, String.format("DATASET STT idx=%d file=%s text=[%s] dur=%dms audio=%.2fs chunks=%d",
                idx, fileName, text, durationMs, speechDuration, stt.chunks));
    }

    private void pipelineLoop() {
        int bufSize = Math.max(
                AudioRecord.getMinBufferSize(SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                SR_MIC * 2 * 2);
        AudioRecord recorder = new AudioRecord(PerfTestConfig.MIC_AUDIO_SOURCE,
                SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        recorder.startRecording();
        Log.d(TAG, "AudioRecord: source=" + PerfTestConfig.MIC_AUDIO_SOURCE + ", rate=" + SR_MIC + ", state=" + recorder.getState());
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
        if (PerfTestConfig.PERF_TEST_MODE && !PerfTestConfig.MIC_TEST_USE_VAD) {
            runSttFixedCapture(recorder);
            return;
        }

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
        if (speechOnlyDuration < PerfTestConfig.MIN_SPEECH_SEC) {
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

        // PERF-TEST-CHANGE: VAD 종료 직후 WAV 저장 (RTF 시작점 = saved_ms). STT 추론 전.
        long savedMs = 0L;
        String wavPath = "";
        String mappedFile = "";
        String mappedGt = "";
        int mappedIndex = -1;
        if (PerfTestConfig.PERF_TEST_MODE) {
            mappedFile = NextFileReceiver.currentFile;
            mappedGt = NextFileReceiver.currentGt;
            mappedIndex = NextFileReceiver.currentIndex;
            mRecCounter++;
            wavPath = String.format(java.util.Locale.US,
                    "%s/%s_%05d.wav", PerfTestConfig.RECORDINGS_DIR, mSessionId, mRecCounter);
            WavWriter.writeMonoPcm16(wavPath, fullAudio, SR_MODEL);
            savedMs = System.currentTimeMillis();
        }

        SttResult stt = runConformerStt(fullAudio);
        String text = stt.text;
        String resultText = text.isEmpty() ? "(인식 없음)" : text;

        Log.d(TAG, String.format("STT: [%s] (%.1fs, mel=%dms, npu=%dms, %d chunks)",
                resultText, speechDuration, stt.melMs, stt.npuMs, stt.chunks));

        showToast(String.format("%s\n(mel %dms, npu %dms, %d chunks)", resultText, stt.melMs, stt.npuMs, stt.chunks));

        // PERF-TEST-CHANGE: CSV append (finished_ms = STT 완료 직후) + 단지서버 호출 가드
        if (PerfTestConfig.PERF_TEST_MODE) {
            long finishedMs = System.currentTimeMillis();
            long durationMs = finishedMs - savedMs;
            if (mCsv != null) {
                mCsv.append(mappedFile, mappedGt, text,
                        durationMs, savedMs, finishedMs,
                        speechDuration, stt.melMs, stt.npuMs, stt.chunks);
            }
            Log.d(TAG, String.format("PERF: idx=%d wav=%s text=[%s] dur=%dms speech=%.2fs",
                    mappedIndex, wavPath, text, durationMs, speechDuration));
        } else {
            // 실전 모드: 단지서버로 STT 결과 전송
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
    }

    private float[] preprocessMicAudioForStt(float[] audio) {
        if (audio == null || audio.length == 0) return audio;
        float[] x = audio;
        // K series: 저주파 노이즈/룸 럼블 컷 (USB mic 대상). HPF 150Hz가 sweet spot.
        if (PerfTestConfig.MIC_HPF_CUTOFF_HZ > 0.0f) {
            x = biquadHighpass(x, PerfTestConfig.MIC_HPF_CUTOFF_HZ, PerfTestConfig.MIC_HPF_Q);
        }
        if (PerfTestConfig.MIC_TRIM_SILENCE) {
            x = trimSilenceRms(x);
        }
        if (!PerfTestConfig.MIC_PREPROCESS_FOR_STT) {
            return x;
        }

        float mean = 0.0f;
        for (float v : x) mean += v;
        mean /= x.length;

        float[] out = new float[x.length];
        double sumSq = 0.0;
        for (int i = 0; i < x.length; i++) {
            float v = x[i] - mean;
            out[i] = v;
            sumSq += v * v;
        }

        float rms = (float) Math.sqrt(sumSq / Math.max(1, out.length));
        if (rms > 1.0e-5f) {
            float gain = PerfTestConfig.MIC_PREPROCESS_TARGET_RMS / rms;
            gain = Math.max(0.25f, Math.min(gain, PerfTestConfig.MIC_PREPROCESS_MAX_GAIN));
            for (int i = 0; i < out.length; i++) {
                float v = out[i] * gain;
                if (v > 1.0f) v = 1.0f;
                else if (v < -1.0f) v = -1.0f;
                out[i] = v;
            }
            Log.d(TAG, String.format(java.util.Locale.US,
                    "MIC_PREPROCESS: rms=%.6f target=%.6f gain=%.3f",
                    rms, PerfTestConfig.MIC_PREPROCESS_TARGET_RMS, gain));
        }
        return out;
    }

    // K series: 2nd-order biquad HPF (RBJ cookbook). USB mic 저주파 노이즈 컷용.
    private float[] biquadHighpass(float[] x, float fcHz, float q) {
        if (x == null || x.length == 0) return x;
        double omega = 2.0 * Math.PI * fcHz / SR_MODEL;
        double cosw = Math.cos(omega), sinw = Math.sin(omega);
        double alpha = sinw / (2.0 * q);
        double b0 = (1.0 + cosw) / 2.0;
        double b1 = -(1.0 + cosw);
        double b2 = (1.0 + cosw) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw;
        double a2 = 1.0 - alpha;
        b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
        float[] y = new float[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double xn = x[i];
            double yn = b0 * xn + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            y[i] = (float) yn;
            x2 = x1; x1 = xn;
            y2 = y1; y1 = yn;
        }
        return y;
    }

    // D1: 10ms RMS-window 기반 leading/trailing silence trim.
    // mic 녹음의 acoustic latency로 인한 leading silence (~518ms 평균)를 제거해
    // mel per-feature 정규화에서 silence-bias된 mean/std 분포 shift를 완화.
    private float[] trimSilenceRms(float[] x) {
        if (x == null || x.length == 0) return x;
        int win = SR_MODEL / 100; // 10ms @ 16k = 160 samples
        int n = x.length / win;
        if (n < 4) return x;
        // 1단계: 프레임별 RMS 계산
        float[] rmsArr = new float[n];
        for (int i = 0; i < n; i++) {
            int s = i * win, e = (i + 1) * win;
            double sum = 0.0;
            for (int j = s; j < e; j++) sum += x[j] * x[j];
            rmsArr[i] = (float) Math.sqrt(sum / win);
        }
        // 2단계: 적응형 임계값 (H series): noise_p5 * mult.
        // adaptive_mult=0 이면 기존 fixed thresh 사용.
        float thresh = PerfTestConfig.MIC_TRIM_RMS_THRESH;
        float adaptMult = PerfTestConfig.MIC_TRIM_ADAPTIVE_MULT;
        if (adaptMult > 0.0f && n >= 10) {
            float[] sortedRms = rmsArr.clone();
            java.util.Arrays.sort(sortedRms);
            float noiseP5 = sortedRms[Math.max(0, n / 20)]; // 5th percentile
            float adaptiveThresh = noiseP5 * adaptMult;
            thresh = Math.max(adaptiveThresh, PerfTestConfig.MIC_TRIM_RMS_THRESH);
            Log.d(TAG, String.format(java.util.Locale.US,
                    "MIC_TRIM_ADAPTIVE: noise_p5=%.5f mult=%.1f -> thresh=%.5f (floor=%.5f)",
                    noiseP5, adaptMult, thresh, PerfTestConfig.MIC_TRIM_RMS_THRESH));
        }
        boolean[] voiced = new boolean[n];
        for (int i = 0; i < n; i++) {
            voiced[i] = rmsArr[i] >= thresh;
        }
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) if (voiced[i]) { first = i; break; }
        for (int i = n - 1; i >= 0; i--) if (voiced[i]) { last = i; break; }
        if (first < 0 || last < 0) return x; // all silent — leave as-is
        int padFrames = Math.max(0, PerfTestConfig.MIC_TRIM_PAD_MS / 10); // 10ms units
        int sStart = Math.max(0, (first - padFrames) * win);
        int eEnd = Math.min(x.length, (last + padFrames + 1) * win);
        if (eEnd - sStart >= x.length) return x;
        // D4 guard: trim 결과가 너무 짧으면 원본 반환 — short 발화 과다 trim 방지.
        float guardS = PerfTestConfig.MIC_TRIM_MIN_DUR_GUARD_S;
        if (guardS > 0.0f && (eEnd - sStart) < (int)(guardS * SR_MODEL)) {
            Log.d(TAG, String.format(java.util.Locale.US,
                    "MIC_TRIM_GUARD: trimmed %.2fs < %.2fs guard, revert to original",
                    (eEnd - sStart) / (float) SR_MODEL, guardS));
            return x;
        }
        float[] out = new float[eEnd - sStart];
        System.arraycopy(x, sStart, out, 0, out.length);
        Log.d(TAG, String.format(java.util.Locale.US,
                "MIC_TRIM: %.2fs -> %.2fs (cut %.0fms lead, %.0fms tail)",
                x.length / (float) SR_MODEL,
                out.length / (float) SR_MODEL,
                (sStart) / (float) SR_MODEL * 1000,
                (x.length - eEnd) / (float) SR_MODEL * 1000));
        return out;
    }

    private void runSttFixedCapture(AudioRecord recorder) {
        String mappedFile = NextFileReceiver.currentFile;
        String mappedGt = NextFileReceiver.currentGt;
        int mappedIndex = NextFileReceiver.currentIndex;
        int sourceDurationMs = NextFileReceiver.currentDurationMs;
        int captureMs = sourceDurationMs > 0
                ? sourceDurationMs + PerfTestConfig.FIXED_CAPTURE_EXTRA_MS
                : PerfTestConfig.FIXED_CAPTURE_DEFAULT_MS;
        captureMs = Math.max(500, Math.min(captureMs, PerfTestConfig.FIXED_CAPTURE_MAX_MS));

        int targetSamples48k = SR_MIC * captureMs / 1000;
        short[] pcm48k = new short[targetSamples48k];
        int offset = 0;
        long captureStartMs = System.currentTimeMillis();
        while (mRunning && offset < targetSamples48k) {
            int read = recorder.read(pcm48k, offset, targetSamples48k - offset);
            if (read > 0) offset += read;
        }
        if (offset <= 0) {
            Log.d(TAG, "FIXED STT: no audio captured");
            return;
        }

        float[] fullAudio = preprocessMicAudioForStt(resample48to16(pcm48k, offset));
        float speechDuration = fullAudio.length / (float) SR_MODEL;
        mRecCounter++;
        String wavPath = String.format(java.util.Locale.US,
                "%s/%s_%05d.wav", PerfTestConfig.RECORDINGS_DIR, mSessionId, mRecCounter);
        WavWriter.writeMonoPcm16(wavPath, fullAudio, SR_MODEL);
        long savedMs = System.currentTimeMillis();

        SttResult stt = runConformerStt(fullAudio);
        String text = stt.text;
        String resultText = text.isEmpty() ? "(인식 없음)" : text;
        long finishedMs = System.currentTimeMillis();
        long durationMs = finishedMs - savedMs;

        Log.d(TAG, String.format("FIXED STT: idx=%d src=%dms cap=%dms actual=%dms text=[%s] (audio=%.2fs, mel=%dms, npu=%dms, %d chunks)",
                mappedIndex, sourceDurationMs, captureMs, finishedMs - captureStartMs, resultText,
                speechDuration, stt.melMs, stt.npuMs, stt.chunks));
        showToast(String.format("%s\n(mel %dms, npu %dms, %d chunks)", resultText, stt.melMs, stt.npuMs, stt.chunks));

        if (mCsv != null) {
            mCsv.append(mappedFile, mappedGt, text,
                    durationMs, savedMs, finishedMs,
                    speechDuration, stt.melMs, stt.npuMs, stt.chunks);
        }
        Log.d(TAG, String.format("PERF: idx=%d wav=%s text=[%s] dur=%dms speech=%.2fs",
                mappedIndex, wavPath, text, durationMs, speechDuration));
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
        // PERF-TEST-CHANGE: 측정 모드에서는 view 단(overlay/Toast) 모두 skip — logcat만 기록
        if (PerfTestConfig.PERF_TEST_MODE) {
            Log.d(TAG, "showToast(suppressed): " + msg);
            return;
        }
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
