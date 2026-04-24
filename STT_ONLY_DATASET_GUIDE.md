# STT-Only Dataset Mode Guide

## 목적
- VT/VAD 없이 테스트셋 WAV 파일을 순차 처리해서 STT 결과 CSV를 생성.

## 현재 동작 모드
- `PerfTestConfig.PERF_TEST_MODE = true`
- `PerfTestConfig.STT_ONLY_DATASET_MODE = true`
- `PerfTestConfig.PLAY_AUDIO_BEFORE_INFER = true`

## 테스트셋 배치 경로
- CSV:
  - `/sdcard/Android/data/com.t527.sttperftest/files/testset/testset.csv`
- WAV:
  - CSV의 `FileName` 값이 절대경로(`/...`)면 그대로 사용
  - 상대경로면 아래 기준으로 해석:
    - `/sdcard/Android/data/com.t527.sttperftest/files/testset/<FileName>`

## CSV 컬럼
- 필수: `FileName`
- 선택: `gt` (`GT`, `transcript`, `text`도 자동 인식)

## 실행
1. 앱 실행 (런처 아이콘)
2. 서비스 시작 후 테스트셋 루프 자동 실행
3. 결과 CSV 생성 확인:
   - `/sdcard/Android/data/com.t527.sttperftest/files/results/STT_Result_<session>.csv`

## 로그 확인
- `adb logcat | grep -E "VadPipelineService|DATASET STT"`
