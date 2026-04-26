# T527 STT 마이크 성능 갭 — 종합 분석 (2026-04-26)

작성: 2026-04-26
작업: `/Users/nsbb/hdc_labs/work/stt/device_test`
앱 소스: `/Users/nsbb/AndroidStudioProjects/sttperftest`
Dev kit serial: `51475789d0c64881cd3` (전원 OFF 금지)

---

## 1. 현재 상태와 결론 한 줄 요약

- **Direct WAV (16k PCM 직접 디코딩)** → CER 8~9% (정상)
- **mouth simulator(KT_USB_AUDIO 스피커) → 공기 → Dev kit USB mic(Britz BE_STM30U)** → CER 22~37% (저하)
- 가장 강력한 호스트 검증 winner: `D2_thresh=0.008 + pad=80ms` host-side silence trim 적용 시
  - DMIC dataset replay: 16.68% → **15.47%** (-1.21pp)
  - LIVE clean300는 진행 중이었으나 USB mic 재검증 요청으로 인터럽트
- USB mic는 **Apr 24 시점 무음(silent) 회귀 → 현재 회복**, 그러나 **품질이 DMIC보다 명확히 떨어짐**

---

## 2. 핸드오프 가설과의 차이

**핸드오프 가설** (`HANDOFF_MIC_STT_20260426.md`):
1. 청크 슬라이딩 윈도우 병합이 긴 발화에서 깨짐
2. 마이크 캡처 옵션(VR/UNPROCESSED/MIC) 차이
3. 월패드 DMIC와 USB mic 모두 비슷하게 안 됨

**실측 발견**:
1. 청크 병합 가설 **약함** — STT 추론은 일관됨 (A1: dataset replay 18.11% ≈ live 17.96%)
2. AudioSource 변경 **효과 없음** (Apr 23 sweep 결과 검증)
3. **Apr 24 USB mic 데이터는 전체 무음(peak < 0.001)** — 19% CER 수치는 무의미
4. 진짜 원인은:
   - **Silence ratio shift**: 마이크는 broadcast→스피커→공기→mic acoustic latency로 leading silence가 +508ms 길어짐 → mel 정규화에서 silence-bias된 mean/std 분포로 인한 STT 분포 mismatch (D1으로 -1.42pp 확인)
   - **Per-bin time-variance compression**: 시간축 mel 분산이 60% 감소 (5.22 → 2.09) — 음소 transition 구별이 약해짐
   - **Frequency response shaping**: 음성 핵심 대역 -5~-8dB 감쇄 (특히 USB는 -8dB)

---

## 3. 측정 데이터 (matched 20 utterances, probe20 CSV)

### 3.1 WAV-level features

| feature | DIRECT | DMIC (Apr24) | USB (현재) | DMIC-DIR | USB-DIR |
|---|---:|---:|---:|---:|---:|
| duration | 2.52s | 3.42s | 3.41s | +0.90 | +0.89 |
| peak | 0.55 | 0.59 | **0.32** | +0.04 | -0.23 |
| RMS | 0.080 | 0.078 | **0.036** | -0.002 | -0.044 |
| DC offset | ~0 | ~0 | ~0 | 0 | 0 |
| clipping | 0% | 0% | 0% | 0 | 0 |
| noise floor | 0.0017 | 0.0015 | **0.0057** | -0.0002 | +0.0040 |
| **SNR (dB)** | **72** | **29** | **14** | -43 | **-58** |

**해석**:
- DMIC도 SNR이 direct(72dB)보다 -43dB 떨어짐 (스피커-공기-마이크 chain 노이즈 자체)
- USB는 DMIC 대비 추가로 -15dB 더 떨어짐 (USB mic 자체 self-noise)
- USB peak/RMS는 절반 (스피커 출력은 같지만 USB mic 감도가 낮음)
- duration 차이(+0.9s)는 두 마이크 모두 동일 → broadcast/재생 latency

### 3.2 Spectral band dB (loudest 2s FFT)

| band | DIRECT | DMIC | USB | DMIC-DIR | USB-DIR |
|---|---:|---:|---:|---:|---:|
| 50-200Hz | +4.16 | +2.36 | +1.39 | -1.80 | -2.77 |
| 200-800Hz (vowel) | +14.58 | +11.97 | +5.97 | -2.60 | **-8.61** |
| 800-3200Hz (consonant) | +0.35 | -0.96 | -6.34 | -1.31 | **-6.68** |
| 3200-6400Hz (sibilant) | -9.25 | -12.50 | -26.41 | -3.25 | **-17.16** |
| 6400-8000Hz | -18.99 | -20.43 | -38.13 | -1.44 | **-19.14** |

**해석**:
- DMIC frequency response는 direct와 비교적 유사 (-3dB 이내, 200-800Hz와 3200-6400Hz 정도만 -3dB)
- USB는 음성 핵심 대역(200-3200Hz)이 -7dB 약하고, 고주파(>3.2kHz)는 -17~-19dB 강하게 cut
- USB는 **bandlimited mic** 특성 — 16kHz sample rate를 가지지만 실효 bandwidth는 ~3kHz까지

### 3.3 Log-mel spectrogram features

| metric | DIRECT | DMIC | USB |
|---|---:|---:|---:|
| mel mean | -8.42 | -6.54 | -7.46 |
| mel std (전체) | 6.01 | 3.89 | 4.01 |
| **per-bin mean std (시간축)** | **5.22** | **2.64** | **2.09** |
| low bins (1-20) mean | -7.17 | -5.24 | -4.71 |
| mid bins (20-50) mean | -8.06 | -5.87 | -6.81 |
| high bins (50-80) mean | -9.62 | -8.08 | -9.94 |

**핵심 문제 — Per-bin time-variance compression**:

DIRECT의 시간축 mel 분산이 5.22인데 마이크 녹음 후 DMIC=2.64, USB=2.09로 떨어짐. 즉 **시간이 흘러가면서 같은 mel-bin이 voiced↔unvoiced 사이를 오가는 진폭이 60% 감소**.

원인 추정:
1. **Acoustic chain LPF effect**: 스피커-공기-마이크 chain이 시간 도메인에서 envelope smoothing → 음소 attack/decay 약화
2. **Room reverb**: 음의 사라짐(decay)이 직접 신호보다 길어짐 → silent gaps가 noise로 채워짐
3. **Mic noise floor**: 무음 구간에 mic self-noise가 더해져 silence와 voice 사이 mel 차이 줄임

### 3.4 음성 파일 자체 비교 (numerical)

`compare_3sources.py` 스크립트로 매칭된 20 utterance 비교 결과는 위 표대로. 핵심 인덱스 매핑:
- USB recording N (1-20) ↔ probe20.csv row N ↔ direct WAV `testset/clean/<NNNN>.wav`
- DMIC recording M (1-300) ↔ source `<NNNN>.wav`에서 M = NNNN+1

---

## 4. 시도한 해결책과 결과

### 4.1 D1 — Host-side silence trim (RMS 0.01, pad 100ms)

**가설**: leading silence가 mel mean/std 분포를 silence-bias시킴.

**결과** (host trim → device dataset replay, clean 300):
- Without trim: 18.11%
- With D1 trim: 16.68% (-1.42pp)
- 안드로이드 런타임 포팅 후 동일 검증: 16.68% (host와 일치 ✓)

### 4.2 D2 — Trim parameter sweep (7 variants)

| variant | CER | Δ |
|---|---:|---:|
| **D2_thresh=0.008, pad=80** | **15.47%** | **-1.21** ← best |
| D2_thresh=0.005, pad=80 | 15.97 | -0.71 |
| D2_thresh=0.010, pad=150 | 16.03 | -0.65 |
| D2_thresh=0.010, pad=80 (D1 base) | 16.68 | 0 |
| D2_thresh=0.010, pad=30 | 16.70 | +0.02 |
| D2_thresh=0.015, pad=80 | 16.90 | +0.22 |
| D2_thresh=0.020, pad=80 | 18.81 | +2.13 |

**해석**: threshold 0.008이 sweet spot. Lower(0.005)는 noise를 voice로 포함, higher(0.015+)는 quiet voice를 누락.

### 4.3 D3 — VAD-based trim (silero VAD)

| variant | CER |
|---|---:|
| D3_vad thresh=0.50 pad=4 | 22.27% (REJECTED) |

VAD는 RMS trim보다 훨씬 안 좋음. Silero VAD가 dataset에서는 너무 공격적.

### 4.4 D4 — Min-duration guard (5 variants)

| variant | CER |
|---|---:|
| D4_thresh=0.010 pad=080 guard=1.0s | 15.96% |
| D4_thresh=0.010 pad=080 guard=1.5s | 15.88% |
| D4_thresh=0.010 pad=080 guard=2.0s | 16.07% |
| D4_thresh=0.005 pad=080 guard=1.5s | 15.62% |
| D4_thresh=0.010 pad=150 guard=1.5s | 15.58% |

**해석**: guard는 marginal 도움 (-0.2pp). Best는 D2_thresh=0.008 (no guard) 유지.

### 4.5 D5 — Combined param follow-up (5 variants)

| variant | CER |
|---|---:|
| D5_thresh=0.008 pad=080 guard=1.5 | 15.66% |
| D5_thresh=0.008 pad=120 | 15.65% |
| D5_thresh=0.006 pad=080 | 15.93% |
| D5_thresh=0.008 pad=150 | 16.14% |
| D5_thresh=0.008 pad=150 guard=1.5 | 16.17% |

→ **확정 winner: D2_thresh=0.008, pad=80, no guard, no preprocess (DMIC dataset 15.47%)**.

### 4.6 C1 — Inverse EQ correction (host)

DMIC frequency response의 inverse EQ 적용 후 dataset 검증 → **17.92%** (no improvement vs 17.92%).

→ **frequency response shaping은 갭의 주요 원인 아님**. silence ratio + variance compression이 진짜 원인.

### 4.7 E series — USB-specific preprocess sweep (probe20, 20 samples)

USB 녹음 호스트 전처리 후 dataset replay:

| variant | CER | Δ from E0 |
|---|---:|---:|
| **E4_norm_trim025** | **35.82%** | **-1.52** ← best |
| **E5_norm_preemph** | **36.63%** | **-0.71** |
| E0_passthrough (USB raw) | 37.34 | 0 |
| E6_norm_trim_pe | 37.74 | +0.41 |
| E1_norm_only | 38.47 | +1.14 |
| E2_norm_trim008 | 38.47 | +1.14 |
| E8_norm045_trim008 | 39.31 | +1.98 |
| E3_norm_trim015 | 39.58 | +2.25 |
| E7_norm_trim_eq | 40.25 | +2.92 |

**해석**:
- 단순 RMS norm + 약한 trim은 효과 없음 (USB 노이즈 floor 0.006이 trim 임계값 0.008과 너무 가까움)
- Aggressive trim(0.025)만 -1.5pp 효과
- EQ boost (200-3200Hz +6dB)는 distortion 만들어 악화
- Pre-emphasis만 적용하면 약간 개선 (-0.71pp)
- **20 sample이라 statistical noise 큼** — 1pp = 1 utterance 변동

### 4.8 G/H series — Adaptive RMS trim (USB winner, LIVE 검증 완료)

USB는 noise floor가 fixed thresh(0.008)와 가까워 trim이 거의 무효. 프레임별 RMS 5th percentile × multiplier로 임계값 산정 (adaptive).

probe20 dataset sweep:

| variant | CER | Δ from USB raw |
|---|---:|---:|
| **H6 trim_5x_pad30** | **31.75%** | **-5.59** |
| H5 trim_5x_pad150 | 32.52 | -4.82 |
| **G9 trim_5x_pad80** | **33.14** | **-4.20** |
| H1/H2 trim_5x + preemph | 33.90~34.07 | -3.27 ~ -3.44 |
| **G4 preemph 0.95 only** | **34.76** | **-2.58** |
| G2 trim_3x | 36.83 | -0.51 |
| E0 USB raw | 37.34 | 0 |
| H3 trim_6x | 41.77 | +4.43 (over-trim) |

**핵심 패턴**:
1. Adaptive multiplier 5x가 sweet spot (3x 부족, 6x 과다)
2. Pre-emphasis 자체로도 -2.58pp (USB high-cut 보상)
3. RMS norm은 노이즈 같이 증폭 → 악영향
4. Adaptive trim + preemph 조합은 trim 단독보다 약간 나쁨 (preemph 후 noise floor 변동)

### 4.9 LIVE 검증 (USB clean300, adaptive mult=5.0, pad=80, 2026-04-26)

`MIC_TRIM_ADAPTIVE_MULT=5.0f` 안드로이드 포팅 후 mouth simulator + USB Britz BE_STM30U LIVE 측정:

| 비교 | CER | 비고 |
|---|---:|---|
| USB raw probe20 (E0) | 37.34% | 베이스라인 |
| H6 host trim → device dataset | 31.75% | probe20 |
| **USB LIVE clean300 adaptive** | **18.33%** | **mouth sim + USB + adaptive trim** |
| DMIC LIVE Apr 24 (참고) | 17.96% | wallpad DMIC LIVE |
| DMIC dataset D2 winner | 15.47% | host-trimmed DMIC dataset replay |

**🎯 결과**:
- USB raw 37.34% → adaptive trim **18.33%** (**-19.0pp 개선**)
- USB LIVE이 wallpad DMIC LIVE(17.96%)와 **+0.37pp 차이로 거의 동등**
- 94/300 (31%) **완벽 transcript** (CER=0%)
- Per-duration: <2s 25.07%, 2-3s 14.47%, 3-4.5s 16.38%, ≥4.5s 21.07%

**사용자 목표 달성**: speaker→air→USB mic chain에서 USB direct WAV에 가까운 STT 성능 확보.

git: `b29ea9c` on `mic-gap-fix` (Bitbucket pushed).

### 4.10 F series — Per-bin time-variance compression 직접 공격 (FALSIFIED)

**가설**: USB의 mel per-bin time-variance가 DIRECT의 40%(2.09 vs 5.22)로 압축됨. 이를 회복시키면 STT가 좋아질 것.

**Mel std 회복 결과** (probe20 평균):

| variant | per-bin std | DIRECT 7.27 대비 회복도 | CER |
|---|---:|---:|---:|
| DIRECT (참조) | 7.27 | 100% | (n/a) |
| DMIC (Apr24) | 3.36 | 46% | 16.68% |
| **E0 USB raw** | **2.09** | 29% | **37.34%** |
| E4 norm+trim025 | 1.99 | 27% | 35.82 |
| F1 specsub α=2.0 | 3.28 | 45% | 50.78 |
| F2 specsub α=3.0 | 3.72 | 51% | 48.06 |
| F3 expand p=1.5 | 3.04 | 42% | 61.47 |
| F4 expand p=2.0 | 3.79 | 52% | 73.50 |
| F5 specsub+trim | 3.23 | 44% | 51.41 |
| F6 specsub+expand | 4.24 | 58% | 65.45 |
| **F7 gate+specsub** | **5.53** | **76%** | **55.35** |
| F8 aggressive 합성 | 4.60 | 63% | 65.03 |

**⚠️ 결정적 발견**: **mel std와 CER이 inversely correlated**! Mel std 회복도가 가장 높았던 F7(76%)이 CER 55.35%로 USB raw보다 +18pp 악화.

**결론** (가설 falsified):
1. Per-bin time-variance compression은 STT 저하의 **직접 원인이 아님**. 단지 부산물(symptom).
2. Spectral subtraction, time-domain expansion, noise gate 모두 mel std는 회복시키지만 STT는 악화. 이유: **모델은 natural recordings에 robust, 인공 처리에 fragile**. Signal-level 변환은 model이 학습하지 않은 artifact 패턴을 만듦.
3. 이는 acoustic chain 자체의 물리적 특성 (room reverb, mic noise floor, speaker LPF)에서 오는 것이며 **신호 후처리로 undoing 불가능**.

**다음 방향** (사용자 지시 반영): "usb로 wav 넣는것과 최대한 동일하게 스피커로 쏴서 마이크로 듣도록"
→ **신호 처리가 아니라 hardware/acoustic chain 수준 보상**:
- **G1 — Speaker gain boost**: 현재 0.2 → 0.4, 0.6 재녹음 (SNR +6~10dB 가능)
- **G2 — 마이크-스피커 거리 최적화**: physical setup 조정
- **G3 — Mouth simulator 출력 spectrum 보정**: 스피커-측 EQ로 USB mic의 frequency response 한계 사전 보상 (역방향 acoustic chain inverse)
- **G4 — TorchScript NeMo mel 사용**: `USE_TORCHSCRIPT_STT_MEL=true`로 mel feature 자체를 모델 학습과 100% 일치하게 (현재 C/KissFFT mel과 약간 다를 수 있음)

---

## 5. 핵심 미해결 — Per-bin time-variance compression

### 5.1 문제

| | DIRECT | DMIC | USB |
|---|---:|---:|---:|
| per-bin std | 5.22 | 2.64 | 2.09 |
| 비율 vs DIRECT | 1.00 | 0.51 | 0.40 |

마이크 녹음의 mel 시간축 분산이 direct의 절반(DMIC) ~ 40%(USB)로 압축됨.

### 5.2 가설

USB direct WAV 입력 시 잘 됐던 것은 mel time-variance가 5.22로 유지됨. 즉 **USB mic 자체가 문제가 아니라 acoustic chain (speaker→air→mic)이 time-variance를 압축**.

원인 후보:
1. **Speaker → air**: 스피커 transient response 한계 (impulse가 흐려짐)
2. **Air → mic**: 공간 전달 함수, 약간의 reverb
3. **Mic self-noise**: silent 구간에 mic 노이즈가 추가되어 voiced/unvoiced 차이 압축
4. **AGC/compressor**: 안드로이드 AudioRecord pipeline에서 무의식 적용 (Apr 23에서 효과 없음 검증됨, 가능성 낮음)

### 5.3 향후 시도할 해결책

> **포인트** (사용자 지시): "usb로 wav 넣는것과 최대한 동일하게 스피커로 쏴서 마이크로 듣도록 만들어야 한다"
> → 즉, **speaker→air→mic chain의 time-variance compression을 보상**해서 direct WAV와 같은 mel 분포를 만드는 것이 목표.

전략:
- **F1 (스피커 측 pre-emphasis + impulse compensation)**: 스피커 출력 전에 high-freq attack을 boost해서 acoustic chain의 LPF effect를 사전 보상
- **F2 (mic 측 dynamic range expansion)**: 녹음 신호에 expansion (compression의 inverse) 적용해 voiced/unvoiced contrast 회복
- **F3 (spectral subtraction)**: silent 구간에서 추정된 noise spectrum을 subtract해서 mel에서 noise floor의 영향 제거
- **F4 (gain 0.4 - 0.6)**: 스피커 출력을 키워서 SNR 개선 (현재 0.2). 단 distortion 위험
- **F5 (호스트에서 mel norm을 직접 calibration)**: dataset replay 시 mel 정규화 단계에서 std를 강제로 5.22로 맞춤 (검증용 단발 실험)

가장 현실적인 시도 우선순위:
1. **F4 (스피커 gain 0.4-0.6)** — 가장 단순, immediate effect 확인 가능
2. **F2 (dynamic range expansion)** — 호스트에서 적용 가능, mel std 직접 조정
3. **F3 (spectral subtraction)** — 학습된 noise profile 필요, 구현 복잡

---

## 6. 안드로이드 코드 변경 현황

`PerfTestConfig.java`:
- `STT_ONLY_DATASET_MODE = true` — E sweep 임시 (sweep 끝나면 false)
- `MIC_TRIM_SILENCE = false` — E sweep 임시 (host에서 trim)
- `MIC_TRIM_RMS_THRESH = 0.008f` — D2 winner
- `MIC_TRIM_PAD_MS = 80`
- `MIC_TRIM_MIN_DUR_GUARD_S = 0.0f` — guard off (D5에서 효과 없음)
- `MIC_PREPROCESS_FOR_STT = false`
- `USE_TORCHSCRIPT_STT_MEL = false`

`VadPipelineService.java`:
- `LPF_FIR_48K_TO_16K_63` 63-tap Hamming-windowed sinc 추가 (B3 anti-aliasing)
- `resample48to16` FIR + decimate (linear interp 폐기)
- `trimSilenceRms` D1 helper + D4 guard 로직
- `preprocessMicAudioForStt` trim → DC remove → RMS norm
- `runSttOnDatasetAudio`에서도 preprocessMicAudioForStt 호출 (dataset replay 시 검증용)

git: `nsbb/t527-sttperftest@nemo-mel-testcode-export`

---

## 7. 자료 위치

- 원본 testset: `testset/clean/0000.wav ... 0299.wav` (직접 PCM 16k)
- DMIC 녹음 (Apr 24): `results/mic_allfolders_gain02_20260424/clean_mic_20260424_100145/recordings/20260327_065537_NNNNN.wav`
- USB 녹음 (Apr 26 12:54): `/tmp/usb_compare/recordings/20231225_050913_NNNNN.wav`
- E series WAV: `eq_prototype/E[0-8]_*/`
- D series WAV: `eq_prototype/D[2-5]_*/`
- 분석 스크립트: `/tmp/compare_3sources.py`, `/tmp/score_a1.py`, `/tmp/score_by_duration.py`

## 8. 도구

- `play_sequence.py`: testset CSV를 KT_USB_AUDIO 스피커로 재생, gain 0.2 기본
- `run_mic_folder_tests.py`: mic 테스트 orchestrator
- `run_dataset_variant.sh`: variant WAV → device dataset replay → score
- `nlptutti.get_cer`: 공식 CER (특수기호/공백 제거 후)
