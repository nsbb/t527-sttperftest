# sttperftest 변경 로그

원본: `t527_vad_service_with_server` (실전 월패드 배포본)
포크 목적: STT 성능(CER/RTF) 측정용. **파라미터·전체 구조 보존, 측정 인프라만 추가.**
상세 정책: `device_test/STT_PERF_TEST_APP_CHANGES.md`

---

## Baseline (commit bfe1af7)

`t527_vad_service_with_server` 무수정 복사. `find` diff = 0.
복사 제외: `.git/`, `.gradle/`, `.idea/`, `build/`, `app/build/`, `app/.cxx/`, `local.properties`.

---

## 변경 분류

| 분류 | 적용 가능 항목 |
|---|---|
| **유지(보존)** | 파이프라인 흐름, flush 길이(0.3s), beep, VAD threshold, NPU reinit, mel/STT shape, AudioRecord 경로, AudioFormat 등 모든 측정 영향 항목 |
| **변경 가능** | View 단(overlay/Toast), 패키지명, broadcast action, 단지서버 호출, 자산 파일(NB/meta), 신규 측정 인프라 추가 |

---

## 변경 내역

### 1. `app/build.gradle:10`
- 변경 전: `applicationId "com.t527.vad_service"`
- 변경 후: `applicationId "com.t527.sttperftest"`
- 분류: 변경 가능 (식별자 — 모델/측정 무관)
- 사유: 실전 앱과 디바이스에서 공존, `play_sequence.py --stop-app` 호환

### 2. `app/src/main/AndroidManifest.xml:60-67` (추가)
- 신규 receiver `com.t527.wav2vecdemo.perftest.NextFileReceiver` 등록
- intent-filter action `com.t527.sttperftest.NEXT`
- 분류: 변경 가능 (신규 측정 인프라)
- 사유: play_sequence.py 가 매 wav 재생 직전 broadcast 로 다음 파일명·gt 전달 → STT 결과 행 매핑

### 3. `app/src/main/java/com/t527/wav2vecdemo/perftest/PerfTestConfig.java` (신규)
- `PERF_TEST_MODE = true` 글로벌 토글
- 측정용 경로 상수 (`/sdcard/Android/data/com.t527.sttperftest/files/{recordings,results}` — scoped storage 권한 불필요)
- broadcast action 상수
- 분류: 변경 가능 (신규)

### 4. `app/src/main/java/com/t527/wav2vecdemo/perftest/WavWriter.java` (신규)
- mono 16-bit PCM WAV 저장 유틸 (16kHz 고정)
- 분류: 변경 가능 (신규)
- 사유: 테스트 문서 §중요 확인 사항 — "추론에 사용한 음성 파일 저장 필수"

### 5. `app/src/main/java/com/t527/wav2vecdemo/perftest/CsvLogger.java` (신규)
- `calculate_cer.py` 호환 컬럼 (`FileName,gt,ResultText,Duration(ms),saved_ms,finished_ms,speech_duration_sec,mel_ms,npu_ms,num_chunks`)
- 세션별 파일 분리, append 시 lock
- 분류: 변경 가능 (신규)

### 6. `app/src/main/java/com/t527/wav2vecdemo/perftest/NextFileReceiver.java` (신규)
- `ACTION_NEXT_FILE` 수신 → static 필드(`currentFile, currentGt, currentDomain, currentIndex`)
- 분류: 변경 가능 (신규)
- 사유: STT 결과 ↔ 재생 wav 매핑

### 7. `app/src/main/java/com/t527/wav2vecdemo/VadPipelineService.java`
- `:7` `import android.content.Context;` 추가 (RECEIVER_EXPORTED 상수용)
- `:25-29` perftest 4개 import 추가
- `:80` `private CsvLogger mCsv; private int mRecCounter; private String mSessionId` 필드 추가
- `:134-150` onCreate: `PERF_TEST_MODE` 가드 — true면 `DanjiServerSender.init()` skip + `PerfTestConfig.initPaths()` + `CsvLogger` 생성 + `NextFileReceiver` 동적 등록
- `:441-456` runStt: VAD 종료 직후 WAV 저장 + `savedMs` 기록 + 매핑 정보 캡처 (PERF 모드에서만)
- `:497-524` runStt 말미: PERF 모드면 `finished_ms` 기록 + CSV append, 아니면 단지서버 send (else 분기)
- `:540-545` showToast 진입부: PERF 모드면 logcat만 남기고 view 표시 skip
- 분류: 변경 가능 (view·서버·신규 측정 훅) — **모든 파라미터·파이프라인 흐름·shape·threshold·flush·beep·NPU reinit 등은 무수정**

### 8. 자산 교체: `app/src/main/assets/models/Conformer/network_binary.nb`
- 변경 전: calib_test/100k/nb_calib_aihub100 (sha256 e0d3f13c..., output scale 0.24464, 18k 샘플 CER 9.30%)
- 변경 후: qat_1m_1ep_output/wksp_nbg_unify_nbg_unify (sha256 cba745e2..., output scale 0.22189, **18k 샘플 CER 8.86%**)
- 동시 교체: `nbg_meta.json` (출력 scale/zp 만 차이, 입력은 동일)
- 분류: 자산 — Java 코드 무수정 (input scale 0.025881·zp 84 동일, output scale 은 NPU 측 argmax 결과만 사용하므로 무관)
- 사유: 사용자 지시. 최신 QAT 1M×1ep margin 0.3 평가가 목적

### 9. `app/src/main/local.properties` (재생성)
- 원본 사본 시 제외했으므로 sdk.dir만 채워서 복원

### 10. `device_test/play_sequence.py`
- 컬럼명 자동 감지 (`FileName` 우선, `file_path` 호환)
- AUTO_REMAPS 에 `/nas04/nlp_sk/STT/data/test/mic_test/audio` → 로컬 `device_test/testset` 추가 (Mac/WSL 양쪽)
- `send_next_broadcast()` — 매 재생 직전 device 측 broadcast (한국어 공백 escape: device-side single-quote)
- `--no-broadcast`, `--adb`, `--device` CLI 옵션 추가
- `--stop-app` 패키지를 `ANDROID_PACKAGE` 상수로 분리
- 분류: 측정 하네스
