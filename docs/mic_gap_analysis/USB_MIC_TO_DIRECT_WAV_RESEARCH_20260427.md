# USB MIC → DIRECT WAV 변환 연구 정리

작성일: 2026-04-27
대상: T527 STT 마이크 캡처 vs 원본 WAV 음향 차이 보정

## 1. 배경 및 문제

- **DIRECT WAV (원본)** 입력 시 CER: 7~9%
- **스피커→USB mic** 녹음 입력 시 CER: 17~22%
- **목표**: USB mic 녹음 파형/멜을 원본 WAV에 가깝게 만드는 software-only 변환
- **제약**: 디바이스/APK 사용 금지, 보유 음성 파일들로만 작업

## 2. 원인 (예상)

| 원인 | 설명 |
|---|---|
| 마이크 채널 응답 | USB mic 주파수 응답이 평탄하지 않음 (저역 부족, 중·고역 강조) |
| 공간 음향 | 스피커→마이크 공기 전파 잔향/반사 |
| 노이즈 floor | DIRECT WAV는 거의 -inf, USB는 -40~-50dB floor |
| 시간축 어긋남 | 재생 timing 차이로 silence padding 위치 불일치 |
| 레벨/DC 차이 | 게인 차, low-frequency rumble |
| 비선형 효과 | 스피커 왜곡, 마이크 AGC/limiter 가능성 |

## 3. 평가 메트릭

`framework_v2.py`의 **per-feature normalized mel L2 distance**가 모델 입력 표현에 가장 가까움.

베이스라인 (USB raw probe20 기준):
- **Identity (transform 없음)**: 0.896
- **DMIC raw (참고)**: 0.699
- **USB K4 LIVE (참고)**: 0.654

목표: Identity 0.896에서 DMIC 수준(0.7) 이하로 끌어내리기.

## 4. 시도한 방법 전체 목록

### 4.1 신호 도메인 필터
| 방법 | 파라미터 | 결과 |
|---|---|---|
| Biquad HPF | 100/150/200/300/400 Hz | 200/300Hz가 최적 |
| Biquad LPF | 6/7/8 kHz | 효과 미미 |
| Pre-emphasis | 0.95~0.97 | 모델이 학습한 도메인과 어긋나 악화 |
| De-emphasis | — | 효과 미미 |
| RASTA filter | — | 효과 미미 |

### 4.2 노이즈 제거
| 방법 | 파라미터 | 결과 |
|---|---|---|
| Spectral subtraction | α=1.0~3.0 | α=2.0 최적 |
| Soft spectral subtraction | β=0.005~0.02 | 효과 약간 |
| MMSE-STSA (Ehraim-Malah) | α=0.92, 0.95, 0.98 | α=0.98 최적 |
| Wiener filter (gentle) | — | 미미 |
| Multi-band specsub | 4-band | 약간 효과 |
| Non-stationary specsub | minimum statistics | 미미 |

### 4.3 잔향 제거
| 방법 | 결과 |
|---|---|
| WPE dereverberation | shape 수정 후 재시도, 큰 효과 없음 |

### 4.4 채널 매칭
| 방법 | 결과 |
|---|---|
| LTAS matching FIR | 평균 스펙트럼 맞춤 — 약간 도움 |
| LMS FIR fit (matched pair LSQ) | overfit (max gain 7.78x), 0.914로 오히려 악화 |
| Per-band gain (LTAS-based) | 채널 일부 보정 |
| Adaptive per-band gain | 변동성 인해 미미 |

### 4.5 Trim / VAD
| 방법 | 결과 |
|---|---|
| Fixed threshold trim | 단순, 부정확 |
| Adaptive trim (RMS p5 × 2/3/4) | 4x가 최적 (K4 chain) |
| Energy VAD mask + soft gate -15/-20/-25dB | -20dB 최적 |
| Silence compression | 시간축 어긋남 발생 |

### 4.6 정규화
| 방법 | 결과 |
|---|---|
| RMS norm | 기본 |
| Peak norm | 기본 |
| CMS (cepstral mean subtraction) | 미미 |
| K4 chain (HPF150 + adaptive 4x trim + 200ms pad) | LIVE 캡처 정렬에 사용 |

### 4.7 Mel 도메인 (대부분 악화)
| 방법 | 결과 |
|---|---|
| Per-feature normalization | distance 줄지만 모델 도메인 어긋남 |
| Median/Gaussian smooth on spec | 디테일 손상 |
| Modulation spectrum filter | 모델이 본 적 없는 표현 |
| Dynamic range expand on log-mel | 강하게 누르면 악화 |
| Per-utterance log-mel clip | 약간 효과 |
| HEQ (histogram equalization) | CER 66.65%로 대폭 악화 |

### 4.8 고급 / 실험적
| 방법 | 결과 |
|---|---|
| KLT subspace enhancement | 약간 효과 |
| **Harmonic enhancement** | **효과 있음 (best chain 채택)** |
| Energy envelope matching | 미미 |
| DTW audio warping | 모델 도메인 어긋남 |
| Griffin-Lim 재합성 | 위상 손상 |

### 4.9 그리드 서치 (`optimize_chain.py`)
1296조합:
- HPF cutoff × 6
- specsub α × 4
- MMSE α × 3
- VAD threshold × 3
- VAD dB × 3
- log-mel clip on/off × 2

## 5. 최종 결과

### 5.1 Best chain
```python
def best_chain(x):
    y = biquad_hpf(x, 300)
    y = spectral_subtract(y, alpha=2.0)
    y = mmse_stsa(y, alpha=0.98)
    y = harmonic_enhance(y)
    y = apply_vad_gate(y, vad_mask(y, 2.0), -20)
    y = per_utterance_log_mel_clip(y)
    return y
```

### 5.2 Metric (per-feature normalized mel distance, n=20)
| 조건 | mel_dist_norm | mel_corr_norm |
|---|---|---|
| Identity USB raw | 0.896 | — |
| Best chain | **0.7596** | 0.0941 |
| 상대 개선 | **-15.2%** | — |

여러 sweep / DTW 메트릭 / K4 LIVE 100쌍 모두에서 **0.76 부근 plateau** 확인.

## 6. 한계 및 결론

1. **신호 도메인 transform만으로는 한계**
   - 채널·잔향·레벨 일부 보정 가능
   - 마이크 비선형 특성·룸 임펄스 응답 정확한 역변환 불가
2. **Mel 도메인 강제 변환은 모델에 독**
   - distance는 줄어도 모델이 본 적 없는 표현 → CER 악화
   - HEQ가 대표적 사례 (66.65%)
3. **LMS 회귀 학습은 overfit 위험**
   - matched pair LSQ로 7.78x 게인 발생 → 1.0보다 악화
4. **신호 보정 + 모델 retrain 병행 필요**
   - Software-only로는 ~15% 상대 개선이 plateau
   - 진짜 해결: USB mic 녹음 augmentation으로 모델 fine-tuning

## 7. 산출물

- 코드: `framework.py`, `framework_v2.py`, `transforms.py`, `transforms_v2~v6.py`
- 스윕: `sweep_v1.py ~ sweep_v12.py`
- 그리드 서치: `optimize_chain.py`
- 베스트 변환 적용본: `variants/best_chain_USB_probe20/`, `variants/best_chain_USB_K4_clean300/`
- 메트릭: `metrics/sweep_v*.json`, `metrics/optimize_chain.json`

## 8. 다음 후보 (plateau 돌파 시도용)

- per-utterance noise 재추정 + spectral floor 동적 조정
- matched-pair 기반 per-frame regression + L2 정규화 (overfit 억제)
- 협대역 bandpass shaping 정밀화 (1k–4k 강조)
- WPE 파라미터 sweep (filter taps / iterations)
- 모델 retrain (USB mic augmentation 사용) ← 근본 해법
