# t527-sttperftest

T527용 안드로이드 STT 성능 테스트 앱 소스 저장소다.

이 저장소는 다른 컴퓨터에서 다시 빌드해서 테스트를 이어갈 수 있도록 소스 위주로만 정리했다.

## 저장소 구성

포함한 것:

- Java 소스
- JNI 소스
- 성능 테스트용 훅
- CSV/WAV 저장 로직
- NeMo TorchScript mel 연동 코드
- 작은 설정 파일(`json`, `yml`, `txt`)
- JNI 라이브러리

제외한 것:

- `build/`
- APK/AAB 결과물
- 대용량 모델 파일 (`*.nb`, `*.onnx`, `*.pt`)
- 테스트용 음성 파일 (`*.wav`)

테스트용 앱 패키지명:

- `com.t527.sttperftest`

## 이 저장소에서 가능한 것

- 고정 길이 마이크 캡처 기반 STT 테스트
- 녹음 WAV 저장
- 추론 결과 CSV 저장
- 테스트셋 파일명/GT 매핑 브로드캐스트
- Conformer STT 경로 테스트
- NeMo TorchScript mel 경로 테스트

주요 설정 파일:

- `app/src/main/java/com/t527/wav2vecdemo/perftest/PerfTestConfig.java`

## 별도로 준비해야 하는 파일

이 저장소에는 모델 바이너리와 테스트 음성셋이 포함되어 있지 않다.
다른 컴퓨터에서 실행하려면 아래 파일을 직접 넣어야 한다.

필수 런타임 파일:

- `app/src/main/assets/models/Conformer/network_binary.nb`
- `app/src/main/assets/models/Conformer/stt_log_mel.pt`
  - TorchScript mel 사용 시 필요
- `app/src/main/assets/models/Wakeword/network_binary.nb`
- `app/src/main/assets/models/VAD/silero_vad.onnx`

이미 포함된 작은 설정 파일:

- `app/src/main/assets/models/Conformer/nbg_meta.json`
- `app/src/main/assets/models/Conformer/vocab_correct.json`
- `app/src/main/assets/models/Wakeword/nbg_meta.json`

## 빌드 방법

필요 환경:

- Android Studio / Gradle
- Android SDK + NDK
- Java 11
- arm64 대상 디바이스

디버그 APK 빌드:

```bash
./gradlew assembleDebug
```

빌드 결과물:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

## 설치 방법

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.t527.sttperftest android.permission.RECORD_AUDIO
adb shell am start -n com.t527.sttperftest/com.t527.wav2vecdemo.VadPipelineActivity
```

## 성능 테스트 관련 메모

- 마이크 고정 캡처 모드는 원본 wav 길이에 여유 시간(`extra ms`)을 더해서 녹음한다.
- 앱이 저장하는 CSV 기본 컬럼은 아래와 같다.
  - `FileName`
  - `gt`
  - `ResultText`
  - `Duration(ms)`
  - `saved_ms`
  - `finished_ms`
  - `speech_duration_sec`
  - `mel_ms`
  - `npu_ms`
  - `num_chunks`

- CER 계산은 앱 밖에서 `nlptutti`로 수행한다.
- RTF 계산식:
  - `Duration(ms) / (speech_duration_sec * 1000)`

## 주요 파일

- `app/src/main/java/com/t527/wav2vecdemo/VadPipelineService.java`
- `app/src/main/java/com/t527/wav2vecdemo/perftest/PerfTestConfig.java`
- `app/src/main/java/com/t527/wav2vecdemo/perftest/NextFileReceiver.java`
- `app/src/main/java/com/t527/wav2vecdemo/conformer/SttTorchscriptMel.java`
- `app/src/main/jni/conformer/conformer_mel.c`

## 참고

- 이 저장소는 다른 장비에서 다시 빌드하고 테스트를 이어가기 위한 용도다.
- 전체 테스트를 돌리기 전에는 모델 바이너리와 테스트 음성 파일을 직접 넣어야 한다.
