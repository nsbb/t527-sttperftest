package com.t527.wav2vecdemo.perftest;

// PERF-TEST-CHANGE: 신규 파일. 측정 모드 글로벌 토글 + 경로 상수.
public final class PerfTestConfig {
    private PerfTestConfig() {}

    // true: 측정 모드 (overlay/Toast off, 단지서버 off, WAV 저장 + CSV 로깅 on)
    // false: 실전 모드 (원본 동작)
    public static final boolean PERF_TEST_MODE = true;

    // 런타임에 Context.getExternalFilesDir() 결과로 채워짐 (앱 전용 외부 저장소, scoped 권한 불필요)
    // 실 경로: /sdcard/Android/data/com.t527.sttperftest/files/{recordings,results}
    public static String ROOT_DIR = "";
    public static String RECORDINGS_DIR = "";
    public static String RESULTS_DIR = "";

    public static void initPaths(java.io.File externalFilesDir) {
        if (externalFilesDir == null) {
            ROOT_DIR = "/sdcard/sttperftest"; // fallback (보통 실패)
        } else {
            ROOT_DIR = externalFilesDir.getAbsolutePath();
        }
        RECORDINGS_DIR = ROOT_DIR + "/recordings";
        RESULTS_DIR = ROOT_DIR + "/results";
        new java.io.File(RECORDINGS_DIR).mkdirs();
        new java.io.File(RESULTS_DIR).mkdirs();
    }

    // play_sequence.py 가 보내는 broadcast action
    public static final String ACTION_NEXT_FILE = "com.t527.sttperftest.NEXT";
    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_GT = "gt";
    public static final String EXTRA_DOMAIN = "domain";
    public static final String EXTRA_INDEX = "index";
}
