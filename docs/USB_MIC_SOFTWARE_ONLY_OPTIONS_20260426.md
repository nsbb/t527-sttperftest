# USB mic 성능 갭 소프트웨어-only 대응안

작성일: 2026-04-26  
대상 repo: `/Users/nsbb/AndroidStudioProjects/sttperftest`  
관련 문서:

- `docs/ANALYSIS_USB_MIC_20260426.md`
- `docs/HANDOFF_USB_MIC_GAP_20260426.md`

## 1. 현재 가설 재검토

기존 가설은 "USB mic로 들어오는 녹음과 mel을 direct WAV와 비슷하게 만들면 CER도 direct 수준으로 내려갈 것"이었다.

실험 결과 이 가설은 **일부만 맞고, 핵심은 틀렸을 가능성이 높다**.

맞는 부분:

- USB mic 녹음은 direct WAV와 waveform/mel 분포가 다르다.
- silence ratio, 저주파 노이즈, RMS, 고역 감쇄, mel time-variance 차이가 실제로 존재한다.
- HPF150 + adaptive trim(K4)은 이 차이 일부를 줄여 CER를 크게 개선했다.

틀렸을 가능성이 높은 부분:

- direct와 feature 수치를 억지로 비슷하게 만든다고 CER가 좋아지지는 않았다.
- EQ, RMS norm, FIR tilt, spectral subtraction, expansion, WPE, mel norm 변경은 K4보다 나빠졌다.
- 특히 mel time-variance를 direct에 가깝게 회복한 F 계열은 CER가 크게 악화됐다.

현재 판단:

> 모델은 "자연스러운 녹음 분포"에는 어느 정도 견디지만, 후처리 artifact에는 취약하다.  
> 따라서 direct-like feature 수치 자체가 목표가 아니라, STT 모델이 안정적으로 해석할 수 있는 mic 입력 분포를 찾는 것이 목표여야 한다.

## 2. 현재 best

현재 Android 수신측 best는 K4/P5 계열이다.

설정:

- `MIC_AUDIO_SOURCE=0` (`DEFAULT`, T5 winner)
- `MIC_HPF_CUTOFF_HZ=150`
- `MIC_TRIM_ADAPTIVE_MULT=4.0`
- `MIC_TRIM_PAD_MS=200`
- `CHUNK_DROP_LEFT=2`
- `CHUNK_STRIDE_OUT_OVERRIDE=65`
- `MEL_NORM_MODE=0`

성능:

- direct WAV clean300: 약 7.76%
- USB K4 live clean300: 약 17.48%
- P5 live clean300: 약 17.42%

K4/P5는 Android 수신측 후처리만으로 확인된 best이다.

## 3. 학습 없이 남은 방법

모델 파일을 바꾸지 않는 조건에서 남은 방법은 크게 세 가지다.

## 3.1 TTA: multi-preprocess voting

같은 녹음에 대해 여러 전처리를 적용하고, STT를 여러 번 돌린 뒤 결과를 조합한다.

후보 전처리:

- K4: HPF150 + adaptive trim 4x + pad200
- P5: K4 + chunk merge stride65/dropLeft2
- K4 + high-only mild boost
- K4 + pre-emphasis mild
- adaptive trim mult/pad 다른 값

가능한 조합 방식:

- 문장 단위 선택: confidence proxy가 가장 좋은 hypothesis 선택
- token 단위 voting: CTC token sequence를 align해서 다수결
- CER proxy 기반 선택: blank ratio, repeated token ratio, output length ratio, known bad pattern 수 등을 점수화

장점:

- 모델 파일 변경 없음
- Android 앱 코드만으로 구현 가능
- 샘플마다 잘 맞는 전처리가 다를 경우 개선 가능

단점:

- 추론 시간이 2~5배 증가
- NPU 추론 호출 횟수 증가
- confidence가 없으면 hypothesis 선택이 어려움

권장 1차 실험:

- probe20에서 K4/P5/S5/S2 정도의 결과를 같은 row 기준으로 모은다.
- 각 row마다 oracle best CER를 계산한다.
- oracle best가 K4 평균보다 충분히 낮으면 TTA voting 가치가 있다.
- oracle best도 K4와 비슷하면 TTA는 중단한다.

## 3.2 CTC beam search / language correction

현재 앱은 NPU output을 argmax로 greedy decode한다. mic 입력에서 acoustic이 흔들리면 한 번 틀린 token이 그대로 결과가 된다.

개선안:

- NPU output의 top-k 또는 logits를 가져온다.
- CTC beam search를 적용한다.
- character/word-level language score를 추가한다.

기대 효과:

- "에어컨 모두 뭐야" → "에어컨 모드 뭐야" 같은 치환 오류 복구 가능
- 조사/띄어쓰기/짧은 단어 치환 완화 가능
- acoustic confidence가 애매한 구간에서 greedy보다 안정적

한계:

- 완전히 누락된 음소는 복구 어렵다.
- 현재 JNI가 argmax만 반환하면 native output 접근을 바꿔야 한다.
- 한국어 LM 또는 domain phrase list가 필요하다.

구현 난이도:

- 중간 이상
- `awconformersdk.c`에서 output tensor를 argmax 전 float/uint8 배열로 Java에 넘기는 API 추가 필요

## 3.3 domain phrase / command correction

성능 테스트셋 전체가 아니라 실제 월패드 명령어가 목적이라면, STT 뒤에서 domain correction을 붙일 수 있다.

예:

- "에어컨 모두 뭐야" → "에어컨 모드 뭐야"
- "공기 동화 장치" → "공기정화장치"
- "취침 신 꺼줘" → "취침등 꺼줘"

방식:

- 명령어 phrase dictionary 구성
- STT 결과와 dictionary 후보의 edit distance / jamo distance 계산
- threshold 이내면 correction 적용

장점:

- 모델 변경 없음
- 실제 제품 명령어 정확도에는 효과 큼
- 구현 빠름

단점:

- 일반 STT 성능 지표 CER에는 공정하게 반영하기 어렵다.
- 테스트셋 전체 문장에는 적용 불가.
- 잘못된 correction 위험이 있다.

## 3.4 playback-side software pre-emphasis

하드웨어 변경은 안 되지만, 테스트/제품에서 스피커로 음성을 내보내는 쪽을 제어할 수 있다면 출력 PCM에 EQ를 적용할 수 있다.

현재 USB mic 녹음은 direct 대비:

- 200-800Hz: 약 -8.6dB
- 800-3200Hz: 약 -6.7dB
- 3200-6400Hz: 약 -17dB
- 6400-8000Hz: 약 -19dB

Android 수신 후처리에서 잃어버린 고역을 복원하는 것보다, speaker 출력 전에 보상해서 mic에 들어오는 녹음 자체를 direct-like하게 만드는 편이 더 자연스럽다.

단, 현재 테스트 환경에서 사용자가 "스피커 gain/출력 조건 고정"이라고 했으므로 제품에 적용 가능한지 확인 필요.

## 3.5 capture timing 재검토

K4/P5 live probe20에서 일부 발화의 `speech_duration_sec`가 원본보다 짧게 잡히는 샘플이 있었다.

예:

- "열심히 안 했어"가 `speech_duration_sec=0.41`로 저장되고 결과가 빈 문자열

가능성:

- capture start가 늦음
- adaptive trim이 너무 공격적으로 앞/뒤를 잘라냄
- fixed capture extra 900ms는 충분하지만, broadcast/playback delay와 실제 재생 시작 타이밍이 어긋남

확인할 것:

- 저장된 recordings를 직접 들어보고 잘렸는지 확인
- trim 전 raw 48k/16k도 같이 저장하는 debug build 작성
- `MIC_TRIM_ADAPTIVE_MULT=3.0`, `pad=300~400ms`로 짧은 발화 보호 확인

이건 모델 변경 없이 바로 확인 가능하다.

## 4. 추천 우선순위

1. **capture/trim으로 잘리는 샘플이 있는지 확인**
   - 짧은 발화가 빈 문자열/과도한 누락이면 K4/P5보다 pad를 늘리는 쪽이 필요하다.

2. **TTA oracle 분석**
   - 이미 있는 K4/R/S 결과 CSV를 row별로 합쳐 oracle best를 계산한다.
   - oracle best가 의미 있게 낮으면 TTA 구현.

3. **CTC beam search 가능성 확인**
   - JNI에서 argmax 이전 output을 가져올 수 있는지 확인한다.
   - 가능하면 small beam search를 구현한다.

4. **domain correction**
   - 일반 STT CER가 아니라 월패드 명령어 성공률 목표라면 가장 실용적이다.

5. **playback-side pre-emphasis**
   - speaker 출력 PCM을 제어할 수 있는 경우에만 진행한다.

## 5. 현재 결론

학습 없이 direct 7~9% 수준까지 내리는 것은 가능성이 낮다.  
하지만 제품 관점에서 개선 여지는 있다.

가장 현실적인 소프트웨어-only 경로:

1. K4/P5 유지
2. 잘림 방지용 capture/trim 파라미터 보정
3. TTA 또는 beam search로 치환 오류 감소
4. 실제 명령어 domain correction 적용

