package com.t527.wav2vecdemo.perftest;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// PERF-TEST-CHANGE: 신규 파일. mono 16-bit PCM WAV 저장 (테스트셋 음원 검증용).
public final class WavWriter {
    private static final String TAG = "WavWriter";
    private WavWriter() {}

    public static boolean writeMonoPcm16(String path, float[] samples, int sampleRate) {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        int dataSize = samples.length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + dataSize);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);              // PCM chunk size
        header.putShort((short) 1);     // PCM format
        header.putShort((short) 1);     // mono
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);  // byte rate
        header.putShort((short) 2);     // block align
        header.putShort((short) 16);    // bits per sample
        header.put("data".getBytes());
        header.putInt(dataSize);

        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(header.array());
            ByteBuffer body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (float s : samples) {
                int v = Math.round(s * Short.MAX_VALUE);
                if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                body.putShort((short) v);
            }
            fos.write(body.array());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "WAV write failed: " + path, e);
            return false;
        }
    }
}
