# USB mic 성능 개선 학습 접근 정리

작성일: 2026-04-26  
대상: T527 STT Conformer NB 모델  
전제: 현재 요청에서는 모델 변경이 불가하지만, 장기 해결책으로 학습 방법을 별도 정리한다.

## 1. 왜 학습이 필요한가

현재 direct WAV와 USB mic 녹음은 입력 분포가 다르다.

direct WAV:

- RMS 높음
- noise floor 낮음
- 고역/자음 대역 보존
- mel time-variance 큼

USB mic live:

- RMS 낮음
- noise floor 높음
- 200-3200Hz 음성 핵심 대역 약함
- 3.2kHz 이상 고역이 크게 감쇄
- mel time-variance가 direct 대비 절반 이하

후처리 실험 결과:

- HPF + adaptive trim은 효과 있음
- RMS norm, EQ, FIR tilt, spectral subtraction, expansion, WPE는 K4보다 나쁨
- direct-like feature 수치를 억지로 맞추면 artifact가 생겨 STT가 악화됨

따라서 최종적으로는 모델이 USB mic channel 분포를 직접 보도록 학습하는 것이 가장 강한 해결책이다.

## 2. 목표

기존 모델 구조와 출력 포맷은 유지한다.

목표:

- direct WAV clean300 CER 7~9% 수준에 최대한 접근
- USB mic live clean300 CER 17%대 → 10%대 초반 이하 목표
- NPU NB export 가능 상태 유지
- Android 전처리 K4/P5와 같이 사용할 수 있게 학습/평가 조건 고정

## 3. 데이터 구성

필요 데이터:

1. 원본 direct WAV
   - `/Users/nsbb/hdc_labs/work/stt/device_test/testset/<folder>/*.wav`

2. USB mic 녹음 WAV
   - 예: `/tmp/k4_live_recordings/recordings/`
   - 또는 최신 live test recordings

3. GT CSV
   - `testset_<folder>.csv`
   - `FileName`, `gt`, `domain`

4. 매핑 정보
   - direct `clean/0000.wav`
   - USB recording `..._00001.wav`
   - GT text

핵심은 direct와 mic 녹음이 같은 문장 기준으로 정확히 매핑되어야 한다는 점이다.

## 4. 학습 데이터 전략

### 4.1 mic-only fine-tune

USB mic 녹음만 추가해서 fine-tune한다.

장점:

- mic live 성능 개선 가능성이 큼
- 구현 단순

단점:

- direct WAV 성능이 떨어질 수 있음
- 특정 mic/스피커 환경에 overfit 가능

### 4.2 mixed fine-tune

direct WAV와 USB mic 녹음을 섞어서 학습한다.

권장 비율:

- direct 50%
- USB mic 30%
- noise/reverb augmentation 20%

장점:

- direct 성능 유지 가능
- mic robustness 개선

단점:

- 데이터/스케줄 튜닝 필요

### 4.3 channel augmentation

direct WAV에 software augmentation을 적용해 mic-like 데이터를 만든다.

augmentation 후보:

- high-frequency roll-off
- room impulse response
- additive noise floor
- low RMS / SNR variation
- speaker/mic bandpass simulation
- leading/trailing silence variation

장점:

- 실제 mic 녹음 데이터가 적어도 확장 가능

단점:

- 실제 USB mic channel과 다르면 효과 제한
- 과한 augmentation은 모델 성능 악화

## 5. 권장 학습 방식

권장안:

1. 기존 QAT 1m_ep01 모델을 baseline으로 둔다.
2. FP32 또는 QAT fine-tune이 가능한 checkpoint에서 시작한다.
3. direct + USB mic mixed dataset으로 짧게 fine-tune한다.
4. 평가셋은 반드시 direct와 USB mic를 분리해서 본다.
5. 기준 통과 후 NPU NB로 export한다.

평가 기준:

- direct clean300 CER가 기존 대비 크게 나빠지면 reject
- USB mic clean300 CER가 K4/P5 17%대보다 충분히 낮아져야 accept
- folder별 CER도 같이 확인

## 6. 평가 프로토콜

필수 평가:

1. direct WAV eval
   - 앱 dataset mode 또는 기존 서버/T527 vpm_run 방식

2. USB mic live eval
   - mouth simulator → USB mic → Android app
   - `gain=0.2`
   - K4/P5 전처리 고정

3. wallpad DMIC live eval
   - 가능하면 별도 측정

CSV 컬럼:

- `FileName`
- `gt`
- `ResultText`
- `saved_ms`
- `finished_ms`
- `Duration(ms)`
- `speech_duration_sec`
- `mel_ms`
- `npu_ms`
- `num_chunks`
- `cer_nlptutti`
- `rtf`

CER:

- `nlptutti.get_cer`
- 특수기호/공백 제거 후 계산

## 7. 주의사항

- clean300만 보고 결정하면 안 된다. 전체 folder별 결과도 봐야 한다.
- mic 녹음 파일과 GT 매핑이 틀리면 학습/평가 모두 무의미하다.
- Android 전처리(K4/P5)를 학습 시점과 평가 시점에 맞춰야 한다.
- NeMo mel / C mel 차이가 있으면 학습 분포와 앱 분포가 다시 어긋난다.
- export 후 device 내부 NB가 stale copy인지 sha256으로 확인해야 한다.

## 8. 산출물

학습 실험마다 남겨야 할 것:

- 학습 config
- 사용한 dataset manifest
- direct eval CSV
- USB mic live eval CSV
- wallpad DMIC eval CSV
- NB 파일 sha256
- 앱 asset copy 후 device 내부 NB sha256
- folder별 CER summary

## 9. 결론

소프트웨어-only 후처리로는 K4/P5에서 포화가 관찰됐다.  
direct 수준에 가까운 USB mic 성능이 필요하면, 가장 가능성 높은 장기 해법은 mic channel을 학습 데이터에 포함한 fine-tune이다.

단, 현재 제품 조건에서 모델 변경이 불가능하다면 이 문서는 장기 개선안으로 보관하고, 단기적으로는 `USB_MIC_SOFTWARE_ONLY_OPTIONS_20260426.md`의 TTA/beam/domain correction을 우선 검토한다.
