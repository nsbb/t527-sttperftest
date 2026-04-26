# USB mic CER 갭 대응 방법 전체 정리

작성일: 2026-04-26  
대상: T527 STT PerfTest / NeMo mel / Conformer NB  
전제:

- 모델 파일 변경은 현재 제품 조건에서 불가하다.
- 하드웨어 변경도 불가하다.
- 그래도 원인 검증과 장기 개선을 위해 학습 방법까지 같이 정리한다.

## 1. 현재 문제 정의

direct WAV로 앱에 입력하면 clean 기준 CER가 7~9%대까지 나온다.  
하지만 같은 문장을 스피커로 재생하고 USB mic 또는 월패드 DMIC로 녹음해서 넣으면 CER가 크게 올라간다.

현재 확인된 차이:

- mic 녹음은 direct WAV보다 RMS가 낮다.
- noise floor가 높다.
- 고역, 특히 자음 대역이 약하다.
- 녹음 길이와 trim 위치가 direct와 다르다.
- mel 분포도 다르지만, mel 수치만 direct처럼 맞춘다고 성능이 회복되지는 않았다.

중요한 결론:

> direct WAV와 mic 녹음의 수치적 feature를 억지로 같게 만드는 것이 최종 목표가 아니다.  
> 목표는 STT 모델이 안정적으로 해석할 수 있는 mic 입력 분포를 만드는 것이다.

## 2. 현재 best 기준

현재까지 Android 수신측에서 가장 나은 계열은 K4/P5이다.

- `MIC_AUDIO_SOURCE=DEFAULT`
- `MIC_HPF_CUTOFF_HZ=150`
- `MIC_TRIM_ADAPTIVE_MULT=4.0`
- `MIC_TRIM_PAD_MS=200`
- `CHUNK_DROP_LEFT=2`
- `CHUNK_STRIDE_OUT_OVERRIDE=65`
- `MEL_NORM_MODE=0`

대략 성능:

- direct WAV clean300: 약 7.76%
- USB mic live clean300: 약 17%대
- 월패드 DMIC는 USB mic보다 더 불리한 환경으로 판단

따라서 아래 방법들은 K4/P5를 기준선으로 보고, 여기서 더 개선 가능한지 판단한다.

## 3. 방법 1: capture timing / trim 보정

### 목적

마이크 입력에서 발화 앞뒤가 잘리거나, 실제 음성 길이가 direct보다 짧아지는 문제를 줄인다.

### 적용 위치

- Android 앱 녹음 저장 직후
- STT 입력 WAV 생성 전

### 방법

- trim 전 raw 녹음과 trim 후 녹음을 둘 다 저장한다.
- 짧은 발화에서 speech duration이 과도하게 짧아지는지 확인한다.
- adaptive trim 배수를 낮추거나 pad를 늘린다.
- capture 시작 delay와 playback 시작 타이밍을 다시 확인한다.

후보:

- `MIC_TRIM_ADAPTIVE_MULT=3.0`
- `MIC_TRIM_PAD_MS=300~500`
- capture extra duration 증가
- 앞쪽 minimum keep 구간 추가

### 기대효과

- 빈 문자열 결과 감소
- 짧은 명령어 누락 감소
- 긴 문장 앞뒤 잘림 감소

### 리스크

- silence가 길어지면 3초 chunk 모델에서 불필요한 구간이 늘어날 수 있다.
- noise가 같이 들어가면 오히려 CER가 나빠질 수 있다.

### 우선순위

상.  
모델 변경 없이 바로 확인 가능하고, 실제로 일부 샘플에서 길이/trim 문제가 의심된다.

## 4. 방법 2: audio source / Android capture 설정 변경

### 목적

Android AudioRecord가 적용하는 AEC, NS, AGC, voice processing 영향을 줄이거나 더 유리한 입력 경로를 찾는다.

### 적용 위치

- `AudioRecord` 생성 시 audio source

### 후보

- `DEFAULT`
- `MIC`
- `VOICE_RECOGNITION`
- `UNPROCESSED`
- `CAMCORDER`

현재 실험에서는 `DEFAULT`가 가장 나았다.

### 기대효과

- OS 레벨 음성 처리 artifact 감소
- mic gain/AGC 영향 완화
- 고역 보존 가능성

### 리스크

- 기기/펌웨어별 동작이 다를 수 있다.
- 특정 source는 실제로 같은 HAL path를 탈 수 있다.
- `UNPROCESSED`가 항상 순수 입력을 보장하지 않는다.

### 우선순위

중.  
이미 일부 확인했지만 새 device에서는 다시 probe가 필요하다.

## 5. 방법 3: high-pass filter

### 목적

저주파 rumble, DC drift, 스피커/방 진동 성분을 줄인다.

### 적용 위치

- STT 입력 WAV 생성 전
- native 또는 Java PCM 처리

### 후보

- HPF 80Hz
- HPF 120Hz
- HPF 150Hz
- HPF 200Hz

현재 best는 150Hz 계열이다.

### 기대효과

- noise floor 감소
- silence/trim 안정화
- 저역 노이즈로 인한 mel 오염 감소

### 리스크

- 너무 높이면 남성 저음, 받침, 모음 안정성이 떨어질 수 있다.

### 우선순위

상.  
이미 효과가 확인됐고 K4/P5의 핵심이다.

## 6. 방법 4: EQ / band compensation

### 목적

mic 녹음에서 약해진 음성 대역 또는 고역을 보상한다.

### 적용 위치

- STT 입력 WAV 생성 전

### 후보

- 200~800Hz 보강
- 800~3200Hz 보강
- 3200Hz 이상 고역 보강
- tilt filter
- FIR filter

### 실험 결과

단순 EQ, FIR tilt, high-only boost는 K4보다 좋아지지 않았다.

가능한 이유:

- direct-like band power는 맞춰도 phase/room/reverb/noise artifact는 그대로 남는다.
- 고역 보강 시 noise와 반사음도 같이 커진다.
- 모델이 학습 때 보지 못한 unnatural spectrum이 된다.

### 기대효과

- 특정 mic에서 자음 대역이 너무 죽는 경우 제한적으로 도움 가능

### 리스크

- 현재까지는 악화 가능성이 더 컸다.

### 우선순위

하.  
새 mic나 다른 스피커 조건에서만 제한적으로 재검토한다.

## 7. 방법 5: RMS / loudness normalization

### 목적

mic 녹음의 입력 레벨을 direct WAV와 비슷하게 맞춘다.

### 적용 위치

- STT 입력 WAV 생성 전

### 후보

- peak normalization
- RMS normalization
- LUFS-like normalization
- per-utterance gain clamp

### 실험 결과

단순 RMS normalization은 성능 개선으로 이어지지 않았다.

가능한 이유:

- 음성과 noise가 같이 커진다.
- AGC를 한 번 더 거는 효과가 생긴다.
- 모델이 보는 상대적 dynamic range가 깨질 수 있다.

### 기대효과

- 너무 작은 입력에서 blank가 많이 나오는 경우에는 도움 가능

### 리스크

- noise floor 증가
- clipping 위험
- 발화별 level consistency 악화

### 우선순위

하.  
단독 적용보다 trim/HPF 이후 제한 gain clamp 정도만 검토한다.

## 8. 방법 6: noise reduction / spectral subtraction

### 목적

mic 녹음에 포함된 상시 배경 노이즈를 줄인다.

### 적용 위치

- STT 입력 WAV 생성 전

### 후보

- spectral subtraction
- noise gate
- Wiener filtering
- RNNoise류 denoise

### 실험 결과

간단한 spectral subtraction 계열은 K4보다 좋아지지 않았다.

가능한 이유:

- 음성 자음과 노이즈가 같은 대역에 있어 같이 손상된다.
- musical noise artifact가 생긴다.
- Conformer 입력 mel에서 시간 구조가 깨진다.

### 기대효과

- 실제 환경 noise가 큰 경우 제한적 도움

### 리스크

- 자음 손상
- 긴 문장 누락 증가
- RTF 증가

### 우선순위

중하.  
전통적 spectral subtraction보다 아주 약한 stationary noise floor clamp 정도만 재검토한다.

## 9. 방법 7: dereverb / de-echo

### 목적

스피커-마이크 경로에서 생기는 울림과 잔향을 줄인다.

### 적용 위치

- STT 입력 WAV 생성 전

### 후보

- WPE dereverb
- short-time inverse filtering
- late reverb suppression
- echo-tail attenuation

### 실험 결과

WPE류 접근은 현재까지 K4보다 좋지 않았다.

가능한 이유:

- 단일 mic 녹음에서 안정적인 dereverb가 어렵다.
- 처리 artifact가 STT에 더 치명적이다.
- 발화가 짧고 3초 chunk라 dereverb 추정이 불안정하다.

### 기대효과

- 실제 room reverb가 CER의 주 원인인 경우 가능성 있음

### 리스크

- 계산량 증가
- artifact 증가
- mobile native 구현 난이도 높음

### 우선순위

하.  
현재 조건에서는 우선순위 낮다.

## 10. 방법 8: pre-emphasis

### 목적

고역 자음 성분을 강조해서 mic 감쇄를 보정한다.

### 적용 위치

두 위치가 있다.

1. STT 입력 전 mic 녹음에 적용
2. 스피커 출력 전 재생 음원에 적용

### 판단

수신측 mic 녹음에 적용하면 noise와 artifact도 같이 커진다.  
출력측에서 재생 전에 pre-emphasis를 적용할 수 있다면 더 자연스러울 가능성이 있다.

### 기대효과

- 자음 누락 감소
- 고역 감쇄 보상

### 리스크

- 출력음이 날카롭게 들릴 수 있다.
- 제품에서 실제 스피커 출력 PCM을 제어할 수 있어야 한다.
- 테스트 환경과 제품 환경이 다르면 효과가 달라진다.

### 우선순위

중.  
수신측보다 출력측 적용 가능성을 먼저 확인한다.

## 11. 방법 9: chunking / sliding window 보정

### 목적

3초 단위 모델에서 긴 문장 처리 시 누락, 중복, boundary 오류를 줄인다.

### 적용 위치

- STT chunk 생성
- chunk별 decode 결과 merge

### 후보

- chunk stride 조정
- chunk overlap 증가
- 앞 chunk 일부 drop
- 뒤 chunk 일부 drop
- blank-heavy chunk 제거
- chunk별 confidence 기반 merge

현재 P5는 stride/drop 조정으로 일부 개선된 상태다.

### 기대효과

- 긴 문장 누락 감소
- chunk boundary 치환 감소
- 중간 단어 잘림 감소

### 리스크

- stride를 너무 줄이면 추론 횟수 증가
- merge rule이 잘못되면 반복 단어가 생긴다.

### 우선순위

상.  
사용자가 지적한 긴 문장 문제와 직접 관련 있다.

## 12. 방법 10: TTA / multi-preprocess inference

### 목적

같은 녹음에 여러 전처리를 적용한 뒤, 더 좋은 hypothesis를 선택한다.

### 적용 위치

- STT inference wrapper

### 후보 전처리

- K4
- P5
- K4 + 약한 high boost
- K4 + pad 증가
- K4 + trim 완화
- K4 + pre-emphasis 약하게

### 선택 방법

- oracle 분석: GT가 있을 때 각 후보 중 최저 CER 확인
- 운영 모드: GT 없이 proxy score로 선택
- proxy score 후보:
  - blank 비율
  - 반복 token 비율
  - 결과 길이
  - known bad pattern
  - domain phrase distance

### 기대효과

- 샘플마다 잘 맞는 전처리가 다르면 평균 CER 개선 가능

### 리스크

- 추론 시간이 후보 수만큼 증가
- GT 없이 선택하는 로직이 어렵다.
- oracle 개선이 작으면 실사용 효과 없음

### 우선순위

상.  
모델 변경 없이 가능하고, 먼저 offline oracle로 가치 판단이 가능하다.

## 13. 방법 11: CTC beam search

### 목적

greedy decode가 acoustic ambiguity에서 바로 틀리는 문제를 줄인다.

### 적용 위치

- NPU output decode 단계
- 현재 argmax 이전 logits 또는 top-k 접근 필요

### 방법

- CTC beam search
- char-level language score
- domain phrase score
- blank/repeat penalty 조정

### 기대효과

- 치환 오류 감소
- 비슷한 발음의 명령어 복구 가능
- mic 입력 흔들림에 greedy보다 강할 수 있음

### 리스크

- 현재 JNI가 argmax만 반환하면 native API 수정 필요
- output tensor 크기 때문에 Java 전달 비용 증가 가능
- language score가 과하면 실제 발화를 잘못 고칠 수 있음

### 우선순위

중상.  
모델 변경 없이 가능한 강한 후보지만 구현 확인이 필요하다.

## 14. 방법 12: domain phrase correction

### 목적

STT 결과를 월패드 명령어 domain에 맞게 후처리한다.

### 적용 위치

- STT 결과 텍스트 생성 후
- NLU 전

### 방법

- 명령어 phrase dictionary 구성
- edit distance / jamo distance 계산
- threshold 이내면 후보 문장으로 보정
- room/device/action slot 단위 correction

예:

- "에어컨 모두 뭐야" -> "에어컨 모드 뭐야"
- "공기 동화 장치" -> "공기정화장치"
- "취침 신 꺼줘" -> "취침등 꺼줘"

### 기대효과

- 실제 제품 명령어 성공률 개선
- 구현 빠름
- STT 모델 변경 없음

### 리스크

- 일반 STT CER 평가에는 부적합할 수 있다.
- domain 밖 발화를 잘못 보정할 수 있다.
- threshold 관리가 필요하다.

### 우선순위

상.  
제품 명령어 성공률 기준이면 가장 실용적이다. 단, 순수 STT CER 평가와는 분리해서 봐야 한다.

## 15. 방법 13: NLU fallback / intent-level 보정

### 목적

문자 단위 STT가 조금 틀려도 최종 intent를 맞춘다.

### 적용 위치

- STT 뒤 NLU

### 방법

- STT hypothesis를 그대로 쓰지 않고, 유사 문장 후보 top-k를 NLU에 전달
- device/action/room slot을 fuzzy matching
- confidence 낮은 경우 서버 LLM fallback

### 기대효과

- 최종 사용자 명령 처리 성공률 개선
- CER가 높아도 제품 기능은 동작 가능

### 리스크

- 순수 STT 성능 개선은 아니다.
- fallback 정책이 복잡해질 수 있다.

### 우선순위

중상.  
제품 목적이면 중요하지만, 현재 CER 원인 분석과는 별도 트랙이다.

## 16. 방법 14: playback-side 보정

### 목적

mic에 들어가기 전 재생 음성을 보정해서 녹음 결과 자체를 더 안정적으로 만든다.

### 적용 위치

- 테스트 재생 Python
- 제품 스피커 출력부

### 후보

- gain 조정
- compressor/limiter
- high-frequency pre-emphasis
- EQ
- clipping 방지
- 재생 전후 silence 삽입

### 기대효과

- 수신측 후처리보다 artifact가 적을 수 있다.
- mic가 받는 물리 신호를 직접 바꿀 수 있다.

### 리스크

- 제품에서 출력 PCM을 제어할 수 없으면 적용 불가
- 사람 귀에 부자연스러울 수 있다.
- 테스트용 개선이 실제 제품 개선과 다를 수 있다.

### 우선순위

중.  
테스트 환경에서는 유효하지만 제품 적용 가능성을 먼저 확인해야 한다.

## 17. 방법 15: test/eval pipeline 검증

### 목적

실제 성능 문제가 아니라 테스트 파이프라인 문제인지 배제한다.

### 확인 항목

- CSV filename과 실제 재생 filename 매칭
- GT 매핑 정확성
- folder별 CSV 분리
- `nlptutti` CER 계산 사용
- 특수기호/공백 제거 규칙 일치
- AVG row를 다시 평균에 포함하지 않기
- direct WAV와 mic 녹음의 동일 문장 매칭
- 모델 NB sha256 확인
- device 내부 asset stale copy 여부 확인

### 기대효과

- 잘못된 CER 보고 방지
- direct와 mic 비교 신뢰성 확보

### 리스크

- 성능 자체를 개선하는 방법은 아니다.

### 우선순위

상.  
성능 개선 전 반드시 고정해야 한다.

## 18. 방법 16: sample rate / resampler / PCM format 검증

### 목적

마이크 입력이 모델 학습 때 사용한 음성 포맷과 다르게 변환되면서 성능이 떨어지는지 확인한다.

### 적용 위치

- Android `AudioRecord`
- 48kHz -> 16kHz downsample
- WAV 저장
- mel 입력 전 PCM 변환

### 확인 항목

- mic capture sample rate
- channel count
- 16-bit PCM signed little-endian 여부
- 48kHz -> 16kHz resampler 품질
- stereo/mono mixdown 방식
- clipping 여부
- DC offset
- pre-roll/post-roll silence

### 후보 방법

- Android 기본 resampler 대신 native high-quality resampler 사용
- low-pass anti-alias filter 후 downsample
- mono 변환 시 한 채널만 사용 vs average 비교
- WAV 저장 전후 sha/checksum 또는 RMS 비교

### 기대효과

- 알 수 없는 format 변환 오류 제거
- 고역 aliasing 또는 downsample artifact 감소
- device별 capture 차이 분리 가능

### 리스크

- resampler를 바꿔도 acoustic channel gap 자체는 남는다.
- native 구현과 검증 시간이 필요하다.

### 우선순위

중상.  
성능이 비정상적으로 나쁠 때 반드시 배제해야 하는 기본 항목이다.

## 19. 방법 17: mel frontend 완전 고정

### 목적

학습/평가/Android 앱의 mel 추출이 다르면 모델 입력 분포가 달라지므로, NeMo mel과 앱 mel을 완전히 맞춘다.

### 적용 위치

- native mel 추출
- TorchScript/NeMo mel 비교
- Android asset/config

### 확인 항목

- sample rate
- n_fft
- win_length
- hop_length
- window type
- center/pad mode
- mel filterbank
- f_min/f_max
- power vs magnitude
- log 처리 방식
- dither
- preemphasis
- per-feature normalization

### 기대효과

- direct WAV 성능이 서버/기존 평가와 어긋나는 문제 방지
- 모델 변경 없이 입력 frontend mismatch 제거

### 리스크

- 이미 NeMo mel로 맞춘 상태라면 추가 개선 폭은 작을 수 있다.
- native에서 NeMo와 bit-exact하게 맞추는 데 시간이 든다.

### 우선순위

상.  
STT 모델은 mel mismatch에 민감하므로 계속 고정해야 한다.

## 20. 방법 18: clipping / limiter / level 관리

### 목적

스피커 출력이나 mic capture에서 clipping, saturation, AGC pumping이 생기는지 확인하고 줄인다.

### 적용 위치

- 재생 Python
- speaker output
- Android mic capture
- STT 입력 WAV 생성 전

### 확인 항목

- max amplitude
- clipped sample ratio
- RMS
- peak/RMS ratio
- 발화 중 gain이 흔들리는지
- gain 0.1~1.0별 녹음 비교

### 후보 방법

- 재생 gain 고정
- limiter 적용
- peak normalize 금지
- mic 입력에서 clipped sample이 있으면 해당 utterance 제외 또는 재녹음
- 너무 작은 입력은 retry

### 기대효과

- 과도한 볼륨으로 인한 왜곡 방지
- 발화별 level 편차 감소

### 리스크

- 단순히 level만 맞추면 noise도 같이 변한다.
- 제품 실제 출력 조건과 테스트 조건이 달라질 수 있다.

### 우선순위

중상.  
현재 gain 실험을 이미 했기 때문에, 녹음 파일 기준 clipping ratio를 같이 남기는 것이 좋다.

## 21. 방법 19: confidence / quality 기반 reject-retry

### 목적

품질이 나쁜 녹음 또는 STT confidence가 낮은 결과를 그대로 쓰지 않고 재시도한다.

### 적용 위치

- STT 결과 생성 후
- 제품 UX 레이어

### 후보 quality score

- blank ratio
- result length ratio
- repeated token ratio
- speech duration
- RMS
- SNR proxy
- clipped sample ratio
- domain phrase distance

### 기대효과

- 틀린 명령 실행 방지
- 한 번 더 말하도록 유도해서 제품 오동작 감소

### 리스크

- 순수 CER를 낮추는 방법은 아니다.
- 사용자 재발화 부담이 생긴다.

### 우선순위

중.  
제품 안정성 관점에서는 중요하지만, 현재 성능 개선 실험과는 분리한다.

## 22. 방법 20: 모델 학습 / fine-tune

### 목적

모델이 mic channel 분포를 직접 학습하게 한다.

### 적용 위치

- 학습 pipeline
- NB export 전

### 후보

1. USB mic only fine-tune
2. direct + USB mic mixed fine-tune
3. wallpad DMIC 포함 fine-tune
4. channel augmentation
5. room impulse / speaker-mic simulation

### 기대효과

- direct와 mic의 근본적 distribution gap을 가장 직접적으로 줄일 수 있다.
- K4/P5 후처리만으로 포화된 구간을 넘을 가능성이 가장 크다.

### 리스크

- 현재 제품 조건에서는 모델 변경 불가
- direct 성능이 떨어질 수 있다.
- 특정 mic/room에 overfit 가능
- NPU NB export와 양자화 후 성능 확인 필요

### 우선순위

장기 상, 단기 적용 불가.  
현재는 문서화만 하고, 제품 조건이 풀리면 진행한다.

## 23. 방법 21: 모델 구조/출력 변경

### 목적

3초 chunk 한계, greedy decode 한계, mic robustness 한계를 모델 구조에서 해결한다.

### 후보

- 긴 context 모델
- streaming Conformer
- RNNT
- stronger decoder
- larger vocabulary
- auxiliary noise/channel augmentation head

### 기대효과

- 긴 문장 누락과 chunk boundary 문제를 구조적으로 줄일 수 있다.

### 리스크

- 현재 조건에서 불가
- NPU 호환성 재검증 필요
- 개발/검증 비용 큼

### 우선순위

현재 제외.  
장기 연구 항목으로만 둔다.

## 24. 추천 실행 순서

모델 변경 불가 조건에서 우선순위는 다음과 같다.

1. eval pipeline 고정
   - `nlptutti`
   - GT mapping
   - filename/folder/csv matching
   - AVG row 제외

2. frontend/format 고정
   - NeMo mel 파라미터 고정
   - resampler/PCM format 확인
   - NB sha256 확인

3. capture/trim 검증
   - raw/trim 녹음 동시 저장
   - 짧은 발화와 긴 문장 잘림 확인
   - pad/mult 조정

4. K4/P5 유지 후 chunk merge 개선
   - 긴 문장 누락/중복 케이스 집중 분석
   - stride/drop/merge rule 조정

5. clipping/level 지표 추가
   - RMS
   - peak
   - clipped ratio
   - speech duration

6. TTA oracle 분석
   - 여러 전처리 후보 CSV를 같은 row 기준으로 비교
   - oracle CER가 충분히 낮으면 운영용 selection proxy 구현

7. CTC beam search 가능성 확인
   - JNI에서 logits/top-k 접근 가능한지 확인
   - 가능하면 small beam부터 적용

8. domain phrase correction
   - 순수 STT CER와 별도 지표로 관리
   - 실제 월패드 명령어 성공률 개선용

9. playback-side 보정
   - 제품에서 출력 PCM 제어 가능할 때만 진행

10. 학습/fine-tune
   - 모델 변경 가능 조건이 열릴 때 진행

## 25. 현재 판단

단순 후처리만으로 direct WAV 7~9% 수준까지 바로 내릴 가능성은 낮다.  
하지만 모델 변경 없이도 아직 확인할 가치가 있는 방법은 남아 있다.

가장 현실적인 단기 조합:

- K4/P5 유지
- NeMo mel/PCM/resampler 고정
- capture/trim 잘림 방지
- chunk merge 보정
- TTA oracle 확인
- 가능하면 CTC beam search
- 제품 명령어용 domain correction

장기적으로 direct와 mic 성능 차이를 근본적으로 줄이려면 mic channel을 포함한 fine-tune이 가장 가능성이 높다.
