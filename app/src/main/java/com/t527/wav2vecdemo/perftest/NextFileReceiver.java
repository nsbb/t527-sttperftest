package com.t527.wav2vecdemo.perftest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.t527.wav2vecdemo.VadPipelineService;

// PERF-TEST-CHANGE: 신규 파일. play_sequence.py가 매 wav 재생 직전 broadcast로
// 다음 파일명·gt를 알려주면 여기 저장 → 다음 STT 결과 행에 사용.
public class NextFileReceiver extends BroadcastReceiver {
    private static final String TAG = "NextFileReceiver";

    public static volatile String currentFile = "";
    public static volatile String currentGt = "";
    public static volatile String currentDomain = "";
    public static volatile int currentIndex = -1;
    public static volatile int currentDurationMs = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!PerfTestConfig.ACTION_NEXT_FILE.equals(intent.getAction())) return;
        currentFile = nullToEmpty(intent.getStringExtra(PerfTestConfig.EXTRA_FILE));
        currentGt = nullToEmpty(intent.getStringExtra(PerfTestConfig.EXTRA_GT));
        currentDomain = nullToEmpty(intent.getStringExtra(PerfTestConfig.EXTRA_DOMAIN));
        currentIndex = intent.getIntExtra(PerfTestConfig.EXTRA_INDEX, -1);
        currentDurationMs = intent.getIntExtra(PerfTestConfig.EXTRA_DURATION_MS, 0);
        Log.d(TAG, "next: idx=" + currentIndex + " domain=" + currentDomain
                + " durationMs=" + currentDurationMs + " file=" + currentFile + " gt=" + currentGt);

        // 노트북(play_sequence.py)에서 NEXT를 보낼 때마다 STT 1회 트리거.
        // VT wakeword 감지 없이 성능측정 루프를 돌리기 위한 경로.
        try {
            Intent sttIntent = new Intent(context, VadPipelineService.class);
            sttIntent.setAction(VadPipelineService.ACTION_START_STT);
            context.startForegroundService(sttIntent);
        } catch (Exception e) {
            Log.e(TAG, "failed to trigger ACTION_START_STT", e);
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
