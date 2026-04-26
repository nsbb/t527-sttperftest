# mic_gap_analysis — DIRECT WAV vs DMIC vs USB mic STT 갭 분석 문서 모음

작성: 2026-04-26
대상: T527 STT PerfTest

## 문제 정의

같은 발화를 3가지 방식으로 STT에 입력했을 때 CER 차이:

| 입력 경로 | clean300 CER | 비고 |
|---|---:|---|
| DIRECT WAV (mic 안 거침, dataset 모드) | ~7.76% | gold standard |
| 월패드 DMIC (스피커→공기→mic) | 17.96% (D1) → **15.30% (K4)** | 제품 mic |
| Dev kit USB mic (스피커→공기→mic) | ~37% (raw) → **17.48% (K4)** | 검증용 mic |

## 문서 목록

| 파일 | 내용 |
|---|---|
| [ANALYSIS_USB_MIC_20260426.md](ANALYSIS_USB_MIC_20260426.md) | 종합 분석. WAV/spectrum/mel 비교, 모든 시도 series 결과. **메인 분석 문서**. |
| [HANDOFF_USB_MIC_GAP_20260426.md](HANDOFF_USB_MIC_GAP_20260426.md) | 다음 에이전트 인계서. baseline, 환경, 시도 완료/미시도, 우선순위. |
| [USB_MIC_ALL_MITIGATION_METHODS_20260426.md](USB_MIC_ALL_MITIGATION_METHODS_20260426.md) | 21개 mitigation 방법 종합 정리. 우선순위 + 실행 순서. |
| [USB_MIC_SOFTWARE_ONLY_OPTIONS_20260426.md](USB_MIC_SOFTWARE_ONLY_OPTIONS_20260426.md) | 소프트웨어 only 적용 가능 옵션. |
| [USB_MIC_TRAINING_PLAN_20260426.md](USB_MIC_TRAINING_PLAN_20260426.md) | 모델 재학습/finetune 장기 계획. |

## 핵심 발견 요약

### 1. mic 종류별 신호 품질 (matched 20 utterances)

| | DIRECT | DMIC | K4 LIVE USB | USB raw |
|---|---:|---:|---:|---:|
| peak | 0.81 | **0.89** | 0.47 | 0.32 |
| RMS | 0.132 | **0.126** | 0.065 | 0.037 |
| SNR (dB) | 113 | **38** | 28 | **14** |
| 200-800Hz dB | +25.84 | +21.05 | +14.56 | +5.51 |
| 3200-6400Hz dB | +0.32 | -4.87 | -18.07 | -26.24 |
| **mel per-bin std (시간축)** | **7.27** | **3.36** | 2.51 | 2.09 |

**DMIC는 DIRECT에 매우 가까움**. USB는 모든 면에서 훨씬 나쁨.

### 2. K4 production 설정 — 두 mic 모두 효과

K4 = `MIC_HPF_CUTOFF_HZ=150` + `MIC_TRIM_ADAPTIVE_MULT=4.0` + `MIC_TRIM_PAD_MS=200`.

| mic | baseline (no K4) | K4 적용 | Δ |
|---|---:|---:|---:|
| DMIC clean300 | 17.96% (D1만) | **15.30%** | **-2.66pp** (-14.8% 상대) |
| USB probe20 dataset | 37.34% (E0 raw) | 25.17% | -12.17pp (-32.6% 상대) |
| USB clean300 LIVE | TBD | 17.48% | TBD |

K4는 **mic-agnostic 개선**. USB에서 발견한 처리가 DMIC에도 일반화됨.

### 3. 시도 결과 — 무엇이 효과 있고 무엇이 없었나

✅ **Confirmed**:
- HPF 150Hz biquad — 저주파 노이즈 컷
- Adaptive RMS trim (frame RMS p5 × 4.0)
- Pad 200ms

❌ **Falsified** (악화 또는 무효):
- Spectral subtraction, expansion, gate, compander
- Channel inverse EQ
- Mel-domain normalization 변경 (voiced-only, floor clamp, noise subtract)
- WPE dereverberation
- Spectral gating (noisereduce)
- Iterative K4
- Strong pre-emphasis variants

### 4. 다음 단계

신호 도메인 inference-time 처리는 saturation. 추가 진척:
1. **CTC beam search** (현재 greedy argmax) — JNI 변경 필요
2. **Domain phrase correction** (월패드 명령어 사전 매칭)
3. **TTA / multi-preprocess voting**
4. **모델 fine-tune with mic data** (장기, training pipeline 필요)

자세한 우선순위: [HANDOFF_USB_MIC_GAP_20260426.md](HANDOFF_USB_MIC_GAP_20260426.md) 9번 섹션 참고.
