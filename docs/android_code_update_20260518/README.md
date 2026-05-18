# Android 코드 업데이트 분리 기록

작성일: 2026-05-18

이 브랜치는 문서만 올라가 있던 `main`과 분리해서, 로컬에 남아 있던 Android 코드 변경분을 올리기 위한 브랜치다.

브랜치:

```text
android-code-update-20260518
```

## 포함한 코드

아래 7개 파일만 커밋했다.

```text
app/src/main/java/com/t527/wav2vecdemo/VadPipelineService.java
app/src/main/java/com/t527/wav2vecdemo/conformer/AwConformerJni.java
app/src/main/java/com/t527/wav2vecdemo/conformer/ConformerDecoder.java
app/src/main/java/com/t527/wav2vecdemo/perftest/PerfTestConfig.java
app/src/main/jni/conformer/awconformersdk.c
app/src/main/jni/conformer/conformer_mel.c
app/src/main/jni/conformer/conformer_mel.h
```

## 제외한 파일

아래 항목은 커밋하지 않았다.

```text
*.nb
*.bak
local.properties*
app/build/
테스트 음성 파일
테스트 결과 CSV/WAV
```

## 코드 변경 핵심

```text
1. native C/KissFFT 기반 Conformer mel 경로 유지
2. full-utterance 기준 NeMo-compatible mel/chunk 구조 사용
3. mic 입력 보정 설정 유지
   - HPF 150 Hz
   - adaptive trim 4.0
   - trim pad 200 ms
   - CHUNK_DROP_LEFT = 2
   - CHUNK_STRIDE_OUT_OVERRIDE = 65
4. CTC beam search 실험용 nativeRunLogits / decoder 코드 포함
5. mel norm mode 실험 코드 포함
   - standard
   - voiced-only
   - floor-clamp
   - noise-subtract
   - HEQ
```

## 검증

아래 명령으로 빌드 성공을 확인했다.

```text
./gradlew assembleDebug
```

결과:

```text
BUILD SUCCESSFUL
```
