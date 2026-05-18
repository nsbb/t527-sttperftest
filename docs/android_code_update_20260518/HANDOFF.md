# HANDOFF - Android 코드 업데이트 브랜치

작성일: 2026-05-18

이 문서는 다음 에이전트가 `sttperftest` Android 코드 변경 상태를 빠르게 파악하기 위한 문서다.

## 현재 브랜치

```text
android-code-update-20260518
```

원격 반영:

```text
GitHub    git@github.com:nsbb/t527-sttperftest.git
Bitbucket git@bitbucket.org:hdclabs/t527-sttperftest.git
```

푸시된 최신 커밋:

```text
187622a docs: describe separated android code update
31305c9 feat(stt): separate android mic-gap code update
```

`main`은 문서 결과 중심이고, 이 브랜치는 Android 코드 변경분을 분리해서 보관한 브랜치다.

## 왜 분리했는가

`main`에는 2026-04-27 기준 USB mic/DMIC 분석 문서가 올라가 있었다.  
하지만 실제 Android 코드 변경 7개 파일은 로컬 working tree에만 남아 있었다.

그래서 아래 목적 때문에 별도 브랜치로 분리했다.

```text
1. main에 바로 섞지 않기
2. 모델 파일, 테스트 데이터, 백업 파일 없이 코드만 올리기
3. 다음 작업자가 APK를 다시 빌드하거나 코드 리뷰할 수 있게 하기
```

## 포함된 코드 파일

이번 브랜치에는 아래 7개 코드 파일만 의도적으로 포함했다.

```text
app/src/main/java/com/t527/wav2vecdemo/VadPipelineService.java
app/src/main/java/com/t527/wav2vecdemo/conformer/AwConformerJni.java
app/src/main/java/com/t527/wav2vecdemo/conformer/ConformerDecoder.java
app/src/main/java/com/t527/wav2vecdemo/perftest/PerfTestConfig.java
app/src/main/jni/conformer/awconformersdk.c
app/src/main/jni/conformer/conformer_mel.c
app/src/main/jni/conformer/conformer_mel.h
```

## 제외된 파일

다음 파일들은 로컬에 남아 있을 수 있지만 커밋하지 않았다.

```text
*.nb
*.bak
local.properties*
app/build/
테스트 wav/csv/result
```

현재 로컬에 남아 있는 untracked 예:

```text
app/src/main/assets/models/Conformer/nbg_meta.before_v20_restore_20260427_175116.json
app/src/main/assets/models/Conformer/nbg_meta.v10.bak.json
app/src/main/java/com/t527/wav2vecdemo/*.bak
app/src/main/java/com/t527/wav2vecdemo/conformer/*.bak
app/src/main/jni/conformer/*.bak
local.properties.bak_beam_230647
```

이 파일들은 그대로 올리면 안 된다.

## 코드 변경 핵심

## 성능이 처음 구렸다가 다시 좋아진 이유

이번 코드 변경은 "mel 하나만 바꾼 것"이 아니다. 성능 개선은 아래 3단계가 겹친 결과다.

### 1. direct WAV 성능 개선 - mel/chunk 구조 수정

초기 문제:

```text
3초 audio chunk마다 mel 추출/정규화
마지막 chunk의 padding도 정규화에 섞임
서버 NeMo 평가 방식과 달라져 direct clean도 13%대까지 악화
```

수정 방향:

```text
전체 utterance 기준으로 mel 한 번 추출
전체 utterance 기준 per-feature normalize
그 다음 301-frame window / 250-frame stride로 chunk
마지막 부족분은 정규화 후 0 pad
```

결과:

```text
clean300 direct CER가 약 13.05% → 8.02% 수준으로 개선됨
```

주의:

```text
TorchScript NeMo mel로 바꾼 것이 아니다.
native C/KissFFT mel을 NeMo-compatible 구조로 수정한 것이다.
```

### 2. 마이크 입력 성능 개선 - K4 보정

초기 문제:

```text
스피커→공기→마이크 경로를 지나면서 leading/trailing silence,
저주파 노이즈, 룸 울림, noise floor가 들어와 CER가 크게 악화됨.
```

K4 수정:

```text
MIC_AUDIO_SOURCE = 0
MIC_HPF_CUTOFF_HZ = 150.0f
MIC_TRIM_SILENCE = true
MIC_TRIM_ADAPTIVE_MULT = 4.0f
MIC_TRIM_PAD_MS = 200
```

의미:

```text
HPF 150 Hz로 저주파 노이즈/룸 럼블 제거
adaptive RMS trim으로 불필요한 silence 제거
pad 200 ms로 말 앞뒤 잘림 방지
```

결과:

```text
USB live clean300에서 raw 대비 CER가 명확히 개선됨.
4월 26일 K4 live clean300은 약 17%대까지 내려갔음.
```

### 3. 긴 문장 개선 - P5 chunk merge 보정

초기 문제:

```text
3초 이상 긴 문장에서 chunk 경계 근처 누락/치환이 큼.
뒤쪽 150개 샘플에서 CER가 크게 튀던 현상과 연결됨.
```

P5 수정:

```text
CHUNK_DROP_LEFT = 2
CHUNK_DROP_RIGHT = 0
CHUNK_STRIDE_OUT_OVERRIDE = 65
```

의미:

```text
chunk별 NPU 출력 frame merge 방식을 조정해서
경계 부근 token 손실을 줄이려는 보정이다.
```

결론:

```text
성능 개선 핵심은 다음 3개다.

1. direct WAV: full-utterance NeMo-compatible native mel/chunk
2. mic 입력: HPF150 + adaptive trim 4x + pad200
3. 긴 문장: dropLeft2 + strideOut65
```

### 1. STT mel 경로

현재 설정:

```java
PerfTestConfig.USE_TORCHSCRIPT_STT_MEL = false
```

즉 STT는 TorchScript mel이 아니라 native C/JNI mel 경로를 탄다.

흐름:

```text
마이크/녹음 audio16k
→ conformer_mel.c
→ KissFFT 기반 native mel
→ full utterance 기준 per-feature normalize
→ 301-frame chunk 생성
→ NPU Conformer NB
→ CTC decode
```

주의:

```text
도구를 NeMo TorchScript로 바꾼 것이 아님.
native C/KissFFT mel을 NeMo-compatible하게 맞춘 구조다.
```

### 2. 마이크 입력 보정

현재 주요 설정:

```java
MIC_AUDIO_SOURCE = 0
MIC_TRIM_SILENCE = true
MIC_TRIM_ADAPTIVE_MULT = 4.0f
MIC_TRIM_PAD_MS = 200
MIC_HPF_CUTOFF_HZ = 150.0f
MEL_NORM_MODE = 0
CHUNK_DROP_LEFT = 2
CHUNK_DROP_RIGHT = 0
CHUNK_STRIDE_OUT_OVERRIDE = 65
```

의미:

```text
K4/P5 계열 설정이다.
USB mic gain 0.2 전체 테스트에서 사용한 best 설정이다.
```

### 3. 긴 문장 chunk merge 보정

기존 stride output 63 대신:

```java
CHUNK_STRIDE_OUT_OVERRIDE = 65
CHUNK_DROP_LEFT = 2
```

긴 문장에서 chunk 경계 누락/치환이 커서 P5 설정으로 보정한 상태다.

### 4. Beam search 실험 코드

이번 코드에는 beam search 실험용 변경도 포함되어 있다.

관련 파일:

```text
ConformerDecoder.java
AwConformerJni.java
awconformersdk.c
PerfTestConfig.java
```

현재 기본값:

```java
USE_BEAM_SEARCH = false
BEAM_WIDTH = 1
BEAM_TOPK_PRUNE = 64
```

즉 코드만 들어 있고 기본 동작은 기존 greedy에 가깝다.

### 5. mel norm mode 실험 코드

`conformer_mel.c`에는 실험용 norm mode가 들어 있다.

```text
0 = standard
1 = voiced-only
2 = floor-clamp
3 = noise-subtract
4 = HEQ
```

현재 기본값:

```java
MEL_NORM_MODE = 0
```

즉 HEQ 등 실험 코드는 있지만 기본 실행에는 사용하지 않는다.

## 관련 실험 결과

USB mic 51 gain 0.2 전체 테스트 결과 문서:

```text
docs/mic_gap_analysis/USB_MIC_51_GAIN02_1800_RESULT_20260427.md
```

핵심 결과:

```text
USB mic 51 + K4/P5 보정 전체 평균 CER = 16.251%

animal     18.489%
appliance  19.062%
bio        17.021%
clean      13.173%
daily      13.884%
outdoor    15.879%
```

관련 결론:

```text
direct WAV와 mel/파형을 억지로 맞추는 방식은 CER 개선으로 이어지지 않았다.
현재는 HPF + adaptive trim + chunk merge 보정이 가장 실효성이 있었다.
```

## 빌드 방법

모델 `.nb` 파일은 Git에 올리지 않는다.  
빌드하려면 아래 위치에 모델 파일이 있어야 한다.

```text
app/src/main/assets/models/Conformer/network_binary.nb
```

빌드:

```text
./gradlew assembleDebug
```

2026-05-18 확인 결과:

```text
BUILD SUCCESSFUL
```

## 주의할 점

1. `main`에 바로 merge하기 전 코드 리뷰가 필요하다.
2. `.bak`, 백업 json, `local.properties*`는 커밋하지 말 것.
3. `.nb` 모델은 `.gitignore` 대상이다. 필요하면 별도 전달해야 한다.
4. `USE_TORCHSCRIPT_STT_MEL=false`가 현재 기본값이다.
5. beam search 코드는 들어 있지만 기본 off다.
6. HEQ/mel norm 실험 코드는 들어 있지만 기본 mode 0이다.
7. USB mic gain 0.2 결과와 DMIC 결과는 동일 조건 A/B가 아니므로 최종 비교 시 주의해야 한다.

## 다음 에이전트가 할 수 있는 일

우선순위:

```text
1. 이 브랜치에서 APK 재빌드
2. 동일 APK로 DMIC와 USB mic를 같은 runner/gain 조건에서 재측정
3. beam search를 켤 경우 CER/RTF를 별도 측정
4. HEQ/mel norm mode는 기본값 변경 없이 실험 브랜치에서만 검증
5. 결과가 안정적이면 main merge 또는 PR 작성
```
