# t527-sttperftest

T527 Android STT performance-test app source.

This repository is intentionally source-only:

- excluded: `build/`, APK/AAB outputs
- excluded: model binaries (`*.nb`, `*.onnx`, `*.pt`, test audio)
- included: Java/JNI sources, perf-test hooks, small config/json/meta files

The app package used for testing is:

- `com.t527.sttperftest`

## What is in this repo

- Fixed-capture mic STT test flow
- WAV recording + CSV logging
- dataset file mapping via broadcast
- Conformer STT path
- NeMo TorchScript mel integration code path

Important toggles live in:

- `app/src/main/java/com/t527/wav2vecdemo/perftest/PerfTestConfig.java`

## What is not in this repo

You must provide model binaries and test audio yourself.

Required runtime assets:

- `app/src/main/assets/models/Conformer/network_binary.nb`
- `app/src/main/assets/models/Conformer/stt_log_mel.pt`  (only if TorchScript mel is enabled)
- `app/src/main/assets/models/Wakeword/network_binary.nb`
- `app/src/main/assets/models/VAD/silero_vad.onnx`

Already included small config files:

- `app/src/main/assets/models/Conformer/nbg_meta.json`
- `app/src/main/assets/models/Conformer/vocab_correct.json`
- `app/src/main/assets/models/Wakeword/nbg_meta.json`

## Build

Requirements:

- Android Studio / Gradle
- Android SDK + NDK
- Java 11
- arm64 target device

Build debug APK:

```bash
./gradlew assembleDebug
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.t527.sttperftest android.permission.RECORD_AUDIO
adb shell am start -n com.t527.sttperftest/com.t527.wav2vecdemo.VadPipelineActivity
```

## Perf-test notes

- mic fixed-capture mode uses source wav duration + extra capture margin
- CSV columns include:
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

- CER is computed outside the app with `nlptutti`
- RTF is computed as:
  - `Duration(ms) / (speech_duration_sec * 1000)`

## Main files

- `app/src/main/java/com/t527/wav2vecdemo/VadPipelineService.java`
- `app/src/main/java/com/t527/wav2vecdemo/perftest/PerfTestConfig.java`
- `app/src/main/java/com/t527/wav2vecdemo/perftest/NextFileReceiver.java`
- `app/src/main/java/com/t527/wav2vecdemo/conformer/SttTorchscriptMel.java`
- `app/src/main/jni/conformer/conformer_mel.c`

## Notes

- This repo is for rebuilding and continuing test work on another machine.
- Copy your own model binaries and dataset audio before running full tests.
