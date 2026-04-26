package com.t527.wav2vecdemo.perftest;

// PERF-TEST-CHANGE: 신규 파일. 측정 모드 글로벌 토글 + 경로 상수.
public final class PerfTestConfig {
    private PerfTestConfig() {}

    // true: 측정 모드 (overlay/Toast off, 단지서버 off, WAV 저장 + CSV 로깅 on)
    // false: 실전 모드 (원본 동작)
    public static final boolean PERF_TEST_MODE = true;
    // true: VT/VAD 루프를 사용하지 않고 테스트셋 WAV 파일을 직접 STT 추론.
    public static final boolean STT_ONLY_DATASET_MODE = false;
    // true: 각 테스트셋 파일을 추론 전에 스피커로 재생.
    public static final boolean PLAY_AUDIO_BEFORE_INFER = false;
    // false: Python 스피커 재생 + 마이크 측정 모드에서 VAD를 쓰지 않고 파일 길이 기반 고정 녹음.
    public static final boolean MIC_TEST_USE_VAD = false;
    // true: models/Conformer/stt_log_mel.pt NeMo TorchScript mel 사용.
    // false: 기존 t527_vad_pipeline과 동일한 C/KissFFT mel 사용.
    public static final boolean USE_TORCHSCRIPT_STT_MEL = false;
    // 고정 녹음 시 파일 길이에 더해 녹음할 여유 시간(ms). adb broadcast/재생 시작 지연 흡수.
    public static final int FIXED_CAPTURE_EXTRA_MS = 900;
    // 마이크 입력 실험용 전처리. direct WAV 경로에는 적용하지 않는다.
    public static final boolean MIC_PREPROCESS_FOR_STT = false;
    public static final boolean MIC_DISABLE_AUDIO_EFFECTS = false;
    public static final float MIC_PREPROCESS_TARGET_RMS = 0.045f;
    public static final float MIC_PREPROCESS_MAX_GAIN = 4.0f;
    // D1: leading/trailing silence를 제거해 mel 정규화 분포 shift를 완화.
    // mic 녹음은 broadcast→스피커→공기→mic acoustic latency로 leading silence가 ~500ms 더 김.
    // 호스트 검증에서 dataset 모드 CER 18.11% → 16.68% (-1.42 pp).
    public static final boolean MIC_TRIM_SILENCE = true;
    public static final float MIC_TRIM_RMS_THRESH = 0.008f;
    public static final int MIC_TRIM_PAD_MS = 80;
    // D4 guard: trim 결과가 이 길이(초) 미만이면 원본 반환 (짧은 발화 보호).
    // 0.0 = guard off. <2s 발화에서 과도 trim 방지 목적.
    public static final float MIC_TRIM_MIN_DUR_GUARD_S = 0.0f;
    // H series: 적응형 trim — noise floor 추정값(quietest 5%) × multiplier를 임계값으로.
    // 0.0 = adaptive off (MIC_TRIM_RMS_THRESH 사용). H6 winner: 5.0
    // USB mic처럼 노이즈가 변동하는 환경에서 효과적.
    // 실제 사용 임계값 = max(noise_p5 * mult, MIC_TRIM_RMS_THRESH)
    public static final float MIC_TRIM_ADAPTIVE_MULT = 5.0f;
    public static final int FIXED_CAPTURE_DEFAULT_MS = 4000;
    public static final int FIXED_CAPTURE_MAX_MS = 12000;
    // 파일 간 간격(ms). 재생/추론 안정화를 위한 텀.
    public static final int FILE_GAP_MS = 300;
    // 최소 발화 길이(초). 성능측정 모드에서는 짧은 발화도 CSV에 남기기 위해 0.0f 권장.
    public static final float MIN_SPEECH_SEC = 0.0f;

    // 런타임에 Context.getExternalFilesDir() 결과로 채워짐 (앱 전용 외부 저장소, scoped 권한 불필요)
    // 실 경로: /sdcard/Android/data/com.t527.sttperftest/files/{recordings,results}
    public static String ROOT_DIR = "";
    public static String RECORDINGS_DIR = "";
    public static String RESULTS_DIR = "";
    public static String TESTSET_DIR = "";
    public static String TESTSET_CSV = "";

    public static void initPaths(java.io.File externalFilesDir) {
        if (externalFilesDir == null) {
            ROOT_DIR = "/sdcard/sttperftest"; // fallback (보통 실패)
        } else {
            ROOT_DIR = externalFilesDir.getAbsolutePath();
        }
        RECORDINGS_DIR = ROOT_DIR + "/recordings";
        RESULTS_DIR = ROOT_DIR + "/results";
        TESTSET_DIR = ROOT_DIR + "/testset";
        TESTSET_CSV = TESTSET_DIR + "/testset.csv";
        new java.io.File(RECORDINGS_DIR).mkdirs();
        new java.io.File(RESULTS_DIR).mkdirs();
        new java.io.File(TESTSET_DIR).mkdirs();
    }

    // play_sequence.py 가 보내는 broadcast action
    public static final String ACTION_NEXT_FILE = "com.t527.sttperftest.NEXT";
    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_GT = "gt";
    public static final String EXTRA_DOMAIN = "domain";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_DURATION_MS = "duration_ms";
}
