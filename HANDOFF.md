# sttperftest HANDOFF

## 목적
- 노트북 Python(`play_sequence.py`)이 WAV를 스피커로 재생.
- 디바이스 APK(`com.t527.sttperftest`)는 마이크 입력을 STT(Conformer, CER 8.86 NB)로 변환.
- 결과를 CSV로 저장(성능 지표 포함).

## 현재 동작 구조
1. 앱 실행 후 `VadPipelineService` 시작.
2. Python이 매 파일 재생 직전에 `NEXT` broadcast 전송.
3. 앱의 `NextFileReceiver`가 파일명/GT를 저장하고 `ACTION_START_STT`를 서비스에 트리거.
4. 서비스가 마이크 입력으로 STT 1회 수행 후 CSV append.

## 핵심 설정 파일
- `app/src/main/java/com/t527/wav2vecdemo/perftest/PerfTestConfig.java`
  - `PERF_TEST_MODE = true`
  - `STT_ONLY_DATASET_MODE = false` (중요: 현재는 파이썬 재생 + 마이크 입력 모드)
  - `MIN_SPEECH_SEC = 0.0f` (짧은 발화도 CSV 기록)

## 주요 코드 위치
- STT 서비스:
  - `app/src/main/java/com/t527/wav2vecdemo/VadPipelineService.java`
- NEXT 수신/트리거:
  - `app/src/main/java/com/t527/wav2vecdemo/perftest/NextFileReceiver.java`
- CSV 기록:
  - `app/src/main/java/com/t527/wav2vecdemo/perftest/CsvLogger.java`

## 결과 파일 경로
- `/sdcard/Android/data/com.t527.sttperftest/files/results/STT_Result_<session>.csv`
- 컬럼:
  - `FileName, gt, ResultText, Duration(ms), saved_ms, finished_ms, speech_duration_sec, mel_ms, npu_ms, num_chunks`

## 빌드/설치
- 빌드:
  - `gradlew.bat assembleDebug`
- APK:
  - `app/build/outputs/apk/debug/app-debug.apk`
- 설치:
  - `adb -s <serial> install -r app-debug.apk`

## 런타임 확인 포인트
- 서비스/트리거 로그:
  - `adb logcat | grep -E "next: idx=|BR: STT requested|BR trigger: running STT|STT:\\[|PERF: idx="`
- CSV 라인 수:
  - `adb shell wc -l /sdcard/Android/data/com.t527.sttperftest/files/results/STT_Result_*.csv`

## Python 연동 시 주의
- `play_sequence.py`에서 `--no-broadcast`를 쓰면 안 됨(기본은 broadcast ON).
- 재생 볼륨/스피커-마이크 거리/주변 소음에 따라 인식률 변동.
- 필요시 파일 간 텀(`--gap`)을 늘려 겹침 방지.
