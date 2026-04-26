# HANDOFF — T527 STT USB mic gap 미해결 (2026-04-26)

작업: `/Users/nsbb/AndroidStudioProjects/sttperftest`
브랜치: `mic-gap-fix` (Bitbucket pushed: `f98a215..bd31e09`)
보고 문서: `docs/ANALYSIS_USB_MIC_20260426.md`

## 1. 문제 한 줄 요약

`mouth simulator(KT_USB_AUDIO speaker) → 공기 → Dev kit USB mic(Britz BE_STM30U) → STT` 경로에서 CER이 direct WAV 대비 ~10pp 더 높음.

## 2. 현재 baseline / best (clean300, 300 utterance, mouth simulator + USB mic)

| measurement | CER | 비고 |
|---|---:|---|
| **Direct WAV (mic 안 거침)** | **~7.76%** | gold standard, HANDOFF 기준 |
| **USB raw (no preprocess) LIVE 추정** | ~37% | probe20 dataset에서 측정, clean300 미측정 |
| Wallpad DMIC LIVE (Apr 24) | 17.96% | 참고 |
| **K4 LIVE clean300** | **17.48%** | production: HPF150 + adaptive trim 4x + pad200 |
| P5 LIVE clean300 | 17.42% | K4 + chunk merge tweak (stride 65 + drop_left 2) |

→ Direct와 +9.7pp gap. 사용자 목표는 direct 수준에 가깝게.

## 3. 디바이스 / 환경

- Dev kit serial: `51475789d0c64881cd3` — **전원 OFF 금지** (adb 재연결 안 됨)
- 스피커: Mac → KT_USB_AUDIO (output-device 0), gain 0.2 고정
- Mic: Britz BE_STM30U (USB), card 3 in `/proc/asound/cards`
- 재생 거리/볼륨/마이크 위치 변경 불가 (사용자 지시)

## 4. 시도한 모든 접근 — 결과 요약

probe20 (20 sample, dataset 모드) 기준 비교. 단순화를 위해 변종별 best만:

| series | 핵심 변화 | best variant | best CER | 결론 |
|---|---|---|---:|---|
| baseline | USB raw passthrough | E0 | 37.34% | reference |
| **D** | host RMS silence trim 파라미터 sweep | D2 thresh0.008 pad80 | 31.6%* | DMIC에서 winner이지만 USB는 약 |
| **E** | USB 전처리 (norm/trim/preemph) | E4 norm+trim025 | 35.82 | -1.5pp |
| F | spectral subtraction + expansion | F* | 50~74% | **FALSIFIED** — 신호 왜곡 |
| **G/H** | adaptive trim (RMS p5 × N) | H6 trim5_pad30 | 31.75 | -5.6pp |
| **I/J** | + HPF 다양한 cutoff | J8 HPF150_trim5_pad150 | 28.59 | -8.7pp |
| **K** | HPF150 + adaptive trim mult/pad fine-tune | **K4 HPF150_trim4_pad200** | **25.17%** | **dataset best** (-12.2pp) |
| L | compander/gate/resynth | 모두 K4보다 나쁨 | 25~37 | non-linear 변환 악영향 |
| M | calibrated inverse EQ (USB→DIRECT) | broken | 37~88% | phase 문제 |
| **N** | mel-domain normalization 변경 (JNI) | mode 0 (default) | 25.31 | **모델이 NeMo std에 학습**, voiced/floor/noise-sub 모두 악화 |
| **O/P** | chunk merge stride/drop_left 변경 | P5 stride65+drop_left2 | 23.49** | long utterance만 효과 |
| Q | WPE dereverberation (nara_wpe) | Q1 wpe-only (taps=10) | 34.84 | -2.5pp 단독, K4 추가 효과 없음 |
| R | spectral gating (noisereduce non-stationary) | R7 mild only | 27.63 | K4 단독 25.17보다 +2.5pp 나쁨 |
| S | iterative K4, preemph variants, multi-HPF, lowshelf | S1 K4_twice | 26.43 | 모두 K4 못 이김 (S1=26.43, S4=37.90 worst) |
| T | LIVE probe20 audio_source 변경 sweep (1/6/7/9/0) | T5 audio=0 DEFAULT | 26.64% (LIVE) | -1.87pp probe20, clean300에선 동일 (17.44%) |

*D series는 DMIC에서 측정. USB는 nominally 응답하지만 효과 약함.
**O/P는 long utterance(>=4.5s) subset 측정. 전체 평균은 K4와 거의 동일.

### 4.1 LIVE clean300 검증 (300 sample, 진짜 평가)

| 적용 변경 | LIVE clean300 CER |
|---|---:|
| K4 (HPF150 + trim 4x + pad 200) | **17.48%** |
| P5 (K4 + chunk merge stride 65 + drop_left 2) | 17.42 |
| Wallpad DMIC LIVE Apr 24 | 17.96 |
| Direct WAV clean300 (HANDOFF) | **7.76%** |

K4/P5 LIVE는 wallpad DMIC LIVE보다 살짝 우수. Direct WAV(7.76%)에는 +9.66pp 못 미침.

## 5. 파형 / mel 분석 결과 (matched 20 utterances probe20)

WAV-level (mean over 20):

| feature | DIRECT | DMIC (Apr24) | USB (현재) |
|---|---:|---:|---:|
| peak | 0.55 | 0.59 | 0.32 |
| RMS | 0.080 | 0.078 | 0.036 |
| **noise floor** | **0.0017** | **0.0015** | **0.0057** |
| **SNR (dB)** | **72** | **29** | **14** |
| 길이(s) | 2.52 | 3.42 | 3.41 |

Spectral band dB (loudest 2s FFT):

| band | DIRECT | DMIC | USB | USB-DIR |
|---|---:|---:|---:|---:|
| 50-200Hz | +4.16 | +2.36 | +1.39 | -2.77 |
| 200-800Hz (vowel) | +14.58 | +11.97 | +5.97 | **-8.61** |
| 800-3200Hz (consonant) | +0.35 | -0.96 | -6.34 | **-6.68** |
| 3200-6400Hz (sibilant) | -9.25 | -12.50 | -26.41 | **-17.16** |
| 6400-8000Hz | -18.99 | -20.43 | -38.13 | **-19.14** |

Log-mel features (80 mel bins, mean over 20):

| metric | DIRECT | DMIC | USB |
|---|---:|---:|---:|
| mel_mean | -8.42 | -6.54 | -7.46 |
| mel_std | 6.01 | 3.89 | 4.01 |
| **per-bin std (시간축)** | **5.22** | **2.64** | **2.09** |
| low bin mean | -7.17 | -5.24 (+1.93) | -4.71 (+2.46) |

핵심 차이: **USB는 SNR -15dB 약함, 음성 핵심 대역 -7dB 약함, 고주파 -17dB 강하게 cut, mel 시간축 분산 60% 감소**.

## 6. 코드 변경 현황 (mic-gap-fix branch)

`PerfTestConfig.java`:
```java
PERF_TEST_MODE = true;
STT_ONLY_DATASET_MODE = false;  // 또는 true (sweep 시)
MIC_TRIM_SILENCE = true;
MIC_TRIM_RMS_THRESH = 0.008f;        // floor (adaptive 사용 시)
MIC_TRIM_PAD_MS = 200;                // K4 winner
MIC_TRIM_ADAPTIVE_MULT = 4.0f;        // K4: noise_p5 × 4
MIC_HPF_CUTOFF_HZ = 150.0f;           // K4: biquad RBJ
MIC_HPF_Q = 0.7f;
MEL_NORM_MODE = 0;                    // standard (N series에서 검증, 변경 시 악화)
CHUNK_DROP_LEFT = 2;                  // P5: 비-첫 chunk 첫 N 프레임 drop
CHUNK_STRIDE_OUT_OVERRIDE = 65;       // P5: 기본 63 → 65
USE_TORCHSCRIPT_STT_MEL = false;
```

`VadPipelineService.java`:
- `biquadHighpass(float[], float fc, float q)` — RBJ HPF
- `trimSilenceRms(float[])` — adaptive thresh = max(noise_p5 × MIC_TRIM_ADAPTIVE_MULT, MIC_TRIM_RMS_THRESH)
- `preprocessMicAudioForStt`: HPF → trim → (옵션) DC remove + RMS norm
- chunk merge: drop_left/drop_right + stride_out override

`conformer_mel.h/c` (JNI):
- `conformer_mel_set_norm_mode(mode, voiced_pct, floor_log)` — runtime setter
- mode 0 (default) = standard NeMo per_feature
- mode 1 = voiced-frame norm (FALSIFIED)
- mode 2 = floor clamp log-mel (FALSIFIED)
- mode 3 = subtract per-bin 5th percentile (FALSIFIED)

`awconformersdk.c`: JNI binding for nativeSetMelNormMode.
`AwConformerJni.java`: Java native declaration.

## 7. 검증된 개념 / 가설

✅ **Confirmed**:
- USB silence ratio + leading silence가 mel per-feature mean/std shift (D1)
- Adaptive trim threshold가 fixed thresh보다 USB에서 우수 (G/H series)
- HPF 150Hz가 USB low-freq 노이즈 컷에 효과적 (I/J/K series)
- Per-bin time-variance compression은 STT 저하의 SYMPTOM이지 cause 아님 (F series)
- 모델은 NeMo per_feature 표준 norm 분포에 학습됨 — 변경 시 악화 (N series)
- 신호 도메인 비선형 처리 (specsub, expansion, gate)는 모두 STT 악화

❌ **Falsified** (시도해도 효과 없음):
- Per-bin time-variance 회복 시도 (F1-F8)
- Mel-domain normalization 변경 (N1-N6)
- Channel inverse EQ (C1, M1-M5)
- Dynamic range compression/expansion
- Aggressive frame-level gating

## 8. 사용자 환경 제약

- **하드웨어 변경 불가**: 스피커 gain, 마이크 거리, 마이크 종류 모두 고정
- **모델 변경 불가**: NeMo Conformer QAT 1m_ep01 NPU model 그대로
- **모델 재학습 불가**: training pipeline 미보유
- **수신 측 (Android) 코드만 변경 가능**

## 9. 다음 에이전트가 시도할 것 (priority 순)

### A. 시도 완료 — 신호 도메인 saturation 최종 확인
- **Q series (WPE dereverberation, nara_wpe)**: 단독 -2.5pp, K4 추가 효과 없음
- **R series (noisereduce spectral gating, non-stat/stat)**: 단독 -8pp 개선 (R6 28.94%), K4 +2.5pp 나쁨 (R4)
- **S series (iterative K4, multi-preemph, multi-HPF, lowshelf)**: 모두 K4 못 이김
- **결론**: 신호 도메인 inference-time 처리 90+ 변종 검증 → K4 (25.17% probe20 dataset, 17.48% LIVE clean300)이 global optimum
- 이유: K4의 HPF150 + adaptive trim 4x + pad 200 자체가 reverb tail 영향과 silence ratio shift를 동시에 줄임
- 더 정교한 enhancement (MetricGAN+, DeepFilterNet)는 NPU 임베디드에 무거움 + 학습 분포 의존
- **유일한 진정한 진척 경로 = 모델 측 변경** (재학습/finetune/TorchScript NeMo mel)

### B. 미시도 — 가능성 있음
1. **Test-time augmentation (TTA)** — 같은 utterance를 다른 전처리로 N번 추론 → token-level voting
   - 어려움: token alignment 필요 (CTC blank tokens 정렬)
   - 가능성: 노이즈 평균화 효과
2. **Beam search decoder** — 현재 greedy argmax. JNI에서 top-k logits 받아서 beam search
   - 모델 출력 변경 필요 (단순 argmax → softmax score)
   - 한국어 character LM 적용 가능
3. **Spectral floor clamping in JNI** (mel value 후) — N3/N4 시도했지만 floor 너무 aggressive였음. 더 약한 floor 시도 (-10, -12)
4. **Frame-level inner-silence trim** — 발화 중간의 noise frame 제거 (현재는 leading/trailing만)
5. **Multi-band gain compensation** — 200-1600Hz +N dB EQ (linear 도메인, 신중히)
6. **Adaptive HPF cutoff** — 노이즈 floor 추정 후 cutoff 가변
7. **NPU mel chunk overlap 변경** — STRIDE_FRAMES=250 → 240 또는 260 (chunk count 변동)

### C. 큰 변경 (재학습 등)
- 모델 재학습/finetune (USB mic 녹음으로 fine-tune) — pipeline 필요
- TorchScript NeMo mel (`USE_TORCHSCRIPT_STT_MEL=true`) 검증 — 학습 시 mel과 100% 일치 가능 (현재는 C/KissFFT mel)
- Different STT model (Whisper 등) — 모델 교체

## 10. 자료 / 도구 위치

### 데이터
- 원본 testset: `~/hdc_labs/work/stt/device_test/testset/clean/0000.wav ... 0299.wav` (16k PCM)
- USB 녹음 (probe20, 2026-04-26 12:54): `/tmp/usb_compare/recordings/`
- K4 LIVE clean300 녹음: `/tmp/k4_live_recordings/recordings/`
- 변종 WAV: `~/hdc_labs/work/stt/device_test/eq_prototype/{D2..Q7}_*/`

### 결과 CSV
- 모든 sweep 결과: `~/hdc_labs/work/stt/device_test/results/{각변종}_<timestamp>/result.csv`
- LIVE clean300: `~/hdc_labs/work/stt/device_test/results/{K4,P5}_USB_live_clean300_*`
- Direct probe20 baseline: `~/hdc_labs/work/stt/device_test/results/direct_probe20_163811/result.csv` (=18.60%)

### 스크립트 (모두 `/tmp/`)
- `gen_*_variants.py` — 변종 WAV 생성
- `run_all_*.sh` — 변종 sweep
- `run_e_variant.sh` — 단일 변종 dataset 모드 실행 (push WAV + CSV → run STT → pull → score)
- `run_dataset_variant.sh` — 같은 용도 (DMIC용)
- `score_a1.py` — nlptutti CER 계산
- `score_by_duration.py` — duration bucket별 CER
- `compare_3sources.py` — DIRECT/DMIC/USB 3-way feature 비교

### 호스트 도구
- `~/hdc_labs/work/stt/device_test/play_sequence.py` — 스피커 재생
- `~/hdc_labs/work/stt/device_test/run_mic_folder_tests.py` — folder LIVE orchestrator
- `nara_wpe` Python package (pip installed) — WPE dereverberation

## 11. 측정 프로토콜

### Dataset 모드 (빠른 sweep, 호스트 전처리 검증)
1. APK config: `STT_ONLY_DATASET_MODE=true`, `MIC_TRIM_SILENCE=false`, `MIC_HPF_CUTOFF_HZ=0` (호스트 전처리 검증 시)
2. 변종 WAV directory + matching GT CSV 생성
3. `/tmp/run_e_variant.sh <name> <local_dir> <device_subdir>` 실행
4. CER = `nlptutti.get_cer` (한글/영숫자 외 제거 후 계산)

### LIVE 모드 (production 검증)
1. APK config: `STT_ONLY_DATASET_MODE=false`, K4/P5 production setting
2. `play_sequence.py --csv testset_clean.csv --output-device 0 --gain 0.2 --device 51475789d0c64881cd3`
3. 디바이스에서 mouth simulator → mic 녹음 → STT → result CSV
4. ~25분 (300 utterance × 5초/each)
5. Pull `STT_Result_*.csv`, score

## 12. 절대 깨지면 안 되는 것
- Direct WAV path CER ≤ 9% (변경 후 재확인 필수)
- `MIC_TEST_USE_VAD = false` 유지 (orchestration 호환)
- Dev kit 전원 ON 유지
- Speaker gain = 0.2 고정

## 13. 핵심 메시지

신호 도메인에서 K4(17.48% LIVE)가 saturation. 모델 측 (mel-domain) 변경은 학습 분포 깨서 악화.

**유망 미시도 = WPE dereverb (Q), TTA, beam search**.

직관적으로 **acoustic chain이 "blackbox channel"이고, 학습된 모델은 그 채널의 출력을 본 적 없음**. 채널을 inverse하거나 모델을 채널에 맞추는 두 방향이 있는데, 후자(재학습)가 robust하지만 pipeline 없음.

WPE는 수학적으로 검증된 dereverberation 기법으로 reverb-induced 분포 shift을 줄임 → 가장 가능성 높은 미시도 후보.
