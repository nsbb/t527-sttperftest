package com.t527.wav2vecdemo;

import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.t527.wav2vecdemo.conformer.AwConformerJni;
import com.t527.wav2vecdemo.conformer.ConformerDecoder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ConformerTestActivity extends AppCompatActivity {
    private static final String TAG = "ConformerTest";
    private static final String ASSET_DIR = "models/Conformer";
    private static final String DEVICE_DIR = "/data/local/tmp/kr_conf_sb";
    private static final int SEQ_OUT = 76;
    private static final int STRIDE_OUT = 63; // 76 * 250/301

    private TextView mTextResult;
    private ScrollView mScrollView;
    private AwConformerJni mJni;
    private ConformerDecoder mDecoder;

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

        mScrollView = new ScrollView(this);
        mTextResult = new TextView(this);
        mTextResult.setPadding(16, 16, 16, 16);
        mTextResult.setTextSize(11);
        mScrollView.addView(mTextResult);
        setContentView(mScrollView);

        appendText("=== SungBeom Conformer CTC — T527 NPU ===\n\n");

        // assets에서 내부 저장소로 복사
        appendText("Loading model from assets...\n");
        String nbPath = copyAssetToInternal("network_binary.nb");
        String vocabPath = copyAssetToInternal("vocab_correct.json");

        if (nbPath == null) {
            // fallback: 기존 방식 (adb push)
            nbPath = DEVICE_DIR + "/network_binary.nb";
            vocabPath = DEVICE_DIR + "/vocab_correct.json";
            appendText("Asset not found, fallback to " + DEVICE_DIR + "\n");
        }

        if (!new File(nbPath).exists()) {
            appendText("ERROR: NB not found at " + nbPath + "\n");
            return;
        }

        AwConformerJni.initNpu();
        mJni = new AwConformerJni();
        boolean ok = mJni.init(nbPath);
        appendText("NPU init: " + (ok ? "OK" : "FAIL") + " (" + nbPath + ")\n");
        if (!ok) return;

        mDecoder = new ConformerDecoder();
        mDecoder.loadVocab(vocabPath);

        new Thread(this::runSlidingWindowTests).start();
    }

    private void runSlidingWindowTests() {
        String[] gt = {
            "관리사무소 전화번호가 어떻게 되지?",
            "관리사무소 전화번호 알려줘",
            "관리사무소 번호가 뭐지?",
            "관리사무소 번호 알려줘",
            "관리소 전화번호 알려줘",
            "관리소 전화번호 말해줘",
            "뭔데?, 해줘",
            "알려줘",
            "됐어",
            "뭐지? 알려줘",
            "이십오년 십이월 전기세는 얼마 나왔어?",
            "대전 벽화마을 가는데 얼마나 걸리지?",
            "어제 한국 대 브라질 축구 어떻게 됐어?",
            "대전 중앙시장 가는데 얼마나 걸려?",
            "나 지금 나갈게, 엘리베이터 불러줘",
            "여기서 미팅장소까지 얼마나 걸려?",
            "지금 집으로 출발하면 언제 도착해?",
            "대전 중앙시장 가는데 얼마나 걸려?",
            "나 지금 외출해, 엘리베이터 불러줘",
            "나 지금 나갈게, 엘리베이터 불러줘",
            "칠월 오일 공지 알려줘",
            "소리 조금만 작게해줘",
            "양자 세션 브리핑 해줘",
            "응 닫아줘",
            "대전 엑스포 아쿠아리움 가는길 알려줘",
            "문 열어줘",
            "문 열어줘",
            "집까지 가장 빠른길 알려줘",
            "성심당 가는데 얼마나 걸려?",
            "세대소독 알려줘",
        };

        appendLine("--- Sliding Window (30 samples, QAT 100k NB) ---\n");

        for (int idx = 0; idx < 30; idx++) {
            // Read chunk count from meta file
            String metaPath = DEVICE_DIR + "/chunks/meta_" + String.format("%04d", idx) + ".txt";
            int numChunks = 0;
            try {
                BufferedReader br = new BufferedReader(new FileReader(metaPath));
                numChunks = Integer.parseInt(br.readLine().trim());
                br.close();
            } catch (Exception e) {
                appendLine(String.format("[%02d] SKIP (no meta)", idx));
                continue;
            }

            // Run each chunk through NPU
            List<int[]> allArgmax = new ArrayList<>();
            long totalMs = 0;

            for (int ci = 0; ci < numChunks; ci++) {
                String chunkPath = DEVICE_DIR + "/chunks/chunk_" +
                    String.format("%04d", idx) + "_" + String.format("%02d", ci) + ".dat";

                long t1 = System.currentTimeMillis();
                int[] argmax = mJni.runDatFile(chunkPath);
                long t2 = System.currentTimeMillis();
                totalMs += (t2 - t1);

                if (argmax != null) {
                    allArgmax.add(argmax);
                }
            }

            if (allArgmax.isEmpty()) {
                appendLine(String.format("[%02d] ERROR: no results", idx));
                continue;
            }

            // Merge: use STRIDE_OUT frames from each chunk except last
            List<Integer> mergedIds = new ArrayList<>();
            for (int ci = 0; ci < allArgmax.size(); ci++) {
                int[] ids = allArgmax.get(ci);
                int useFrames = (ci < allArgmax.size() - 1) ? STRIDE_OUT : SEQ_OUT;
                for (int t = 0; t < useFrames && t < ids.length; t++) {
                    mergedIds.add(ids[t]);
                }
            }

            // Convert to int array for decoder
            int[] merged = new int[mergedIds.size()];
            for (int i = 0; i < merged.length; i++) merged[i] = mergedIds.get(i);

            String text = mDecoder.decode(merged);
            int blanks = mDecoder.countBlanks(merged);
            String gtStr = (idx < gt.length) ? gt[idx] : "";

            String hdr = String.format("[%02d] %dms (%d chunks) blank=%d/%d",
                idx, totalMs, numChunks, blanks, merged.length);
            Log.d(TAG, hdr);
            Log.d(TAG, "  GT:  " + gtStr);
            Log.d(TAG, "  NPU: " + text);

            appendLine(hdr);
            appendLine("  GT:  " + gtStr);
            appendLine("  NPU: " + text);
            appendLine("");
        }

        appendLine("=== Done ===");
    }

    private void appendText(String s) {
        runOnUiThread(() -> mTextResult.append(s));
    }

    private void appendLine(String s) {
        appendText(s + "\n");
        runOnUiThread(() -> mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mJni != null) { mJni.release(); AwConformerJni.releaseNpu(); }
    }
}
