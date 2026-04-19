package com.t527.wav2vecdemo.perftest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

// PERF-TEST-CHANGE: 신규 파일. play_sequence.py가 매 wav 재생 직전 broadcast로
// 다음 파일명·gt를 알려주면 여기 저장 → 다음 STT 결과 행에 사용.
public class NextFileReceiver extends BroadcastReceiver {
    private static final String TAG = "NextFileReceiver";

    public static volatile String currentFile = "";
    public static volatile String currentGt = "";
    public static volatile String currentDomain = "";
    public static volatile int currentIndex = -1;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!PerfTestConfig.ACTION_NEXT_FILE.equals(intent.getAction())) return;
        currentFile = nullToEmpty(intent.getStringExtra(PerfTestConfig.EXTRA_FILE));
        currentGt = nullToEmpty(intent.getStringExtra(PerfTestConfig.EXTRA_GT));
        currentDomain = nullToEmpty(intent.getStringExtra(PerfTestConfig.EXTRA_DOMAIN));
        currentIndex = intent.getIntExtra(PerfTestConfig.EXTRA_INDEX, -1);
        Log.d(TAG, "next: idx=" + currentIndex + " domain=" + currentDomain
                + " file=" + currentFile + " gt=" + currentGt);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
