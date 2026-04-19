# t527_vad_service (백업 2026-04-03)

**패키지:** `com.t527.vad_service`
**상태:** 월패드에서 동작 확인된 안정 버전 (Java)

## 기능
- VT(BCResNet) → VAD(Silero) → STT(Conformer) → NLU(KoELECTRA) → overlay
- Foreground Service + BR 부팅 연동
- 월패드(IHN-1300D, Android 13) 실기기 테스트 완료

## 확인된 동작
- FM1388 마이크 → AudioRecord 연동 OK
- wakeword "하이 원더" 감지 (prob 0.40~0.51)
- STT "엘리베이터 불러줘" 인식 OK (mel 36ms, npu 257ms)
- NLU 48 intents 분류 OK
- overlay 보라색 반투명 표시 OK

## 알려진 문제
- wallpadcall 마이크 점유 충돌 (간헐적 silenced)
- FM1388에서 wakeword prob 낮음 (데브킷 대비)

## 설치
```bash
ADB="/mnt/c/Users/nsbb/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="00f75c0572408721ed9"

$ADB -s $DEV install -r 'C:\Users\nsbb\AndroidStudioProjects\t527_vad_service_backup_20260403\app\build\outputs\apk\debug\app-debug.apk'
$ADB -s $DEV shell pm grant com.t527.vad_service android.permission.RECORD_AUDIO
$ADB -s $DEV shell appops set com.t527.vad_service SYSTEM_ALERT_WINDOW allow
$ADB -s $DEV shell am start -n com.t527.vad_service/com.t527.wav2vecdemo.VadPipelineActivity

# 로그
$ADB -s $DEV logcat -s VadPipelineService
```
