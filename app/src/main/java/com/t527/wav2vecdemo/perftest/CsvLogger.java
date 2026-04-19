package com.t527.wav2vecdemo.perftest;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// PERF-TEST-CHANGE: 신규 파일. STT 결과 CSV append (calculate_cer.py 호환 컬럼).
public final class CsvLogger {
    private static final String TAG = "CsvLogger";
    private static final String HEADER =
            "FileName,gt,ResultText,Duration(ms),saved_ms,finished_ms,speech_duration_sec,mel_ms,npu_ms,num_chunks";

    private final File file;
    private final Object lock = new Object();

    public CsvLogger(String dir, String sessionId) {
        File d = new File(dir);
        if (!d.exists()) d.mkdirs();
        this.file = new File(d, "STT_Result_" + sessionId + ".csv");
        if (!file.exists()) writeHeader();
    }

    public static String newSessionId() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void writeHeader() {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, false))) {
            bw.write(HEADER);
            bw.newLine();
        } catch (IOException e) {
            Log.e(TAG, "header write failed", e);
        }
    }

    public void append(String fileName, String gt, String resultText,
                       long durationMs, long savedMs, long finishedMs,
                       float speechSec, long melMs, long npuMs, int numChunks) {
        synchronized (lock) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true))) {
                bw.write(String.format(Locale.US,
                        "%s,%s,%s,%d,%d,%d,%.3f,%d,%d,%d",
                        csv(fileName), csv(gt), csv(resultText),
                        durationMs, savedMs, finishedMs, speechSec, melMs, npuMs, numChunks));
                bw.newLine();
            } catch (IOException e) {
                Log.e(TAG, "append failed", e);
            }
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public String getPath() { return file.getAbsolutePath(); }
}
