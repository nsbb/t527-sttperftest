package com.t527.wav2vecdemo.conformer;

import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Silero VAD — ONNX Runtime CPU
 * Input: audio [1, 512] float32 (32ms @ 16kHz) + state [2, 1, 128] + sr int64
 * Output: probability [1, 1] + stateN [2, 1, 128]
 */
public class SileroVad {
    private static final String TAG = "SileroVad";
    private static final int WINDOW = 512;  // 32ms @ 16kHz

    private OrtEnvironment env;
    private OrtSession session;
    private float[][][] state;  // [2, 1, 128]
    private float threshold = 0.5f;

    public boolean init(String onnxPath) {
        try {
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(onnxPath);
            resetState();
            Log.d(TAG, "Silero VAD init OK: " + onnxPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Silero VAD init failed", e);
            return false;
        }
    }

    public void setThreshold(float threshold) {
        this.threshold = threshold;
    }

    public void resetState() {
        state = new float[2][1][128];
    }

    /**
     * Process 512 samples (32ms @ 16kHz)
     * @return speech probability (0~1)
     */
    public float process(float[] audio512) {
        if (session == null) return 0f;

        try {
            // input [1, 512]
            float[][] audioInput = new float[1][WINDOW];
            int copyLen = Math.min(audio512.length, WINDOW);
            System.arraycopy(audio512, 0, audioInput[0], 0, copyLen);

            OnnxTensor inputTensor = OnnxTensor.createTensor(env, audioInput);
            OnnxTensor stateTensor = OnnxTensor.createTensor(env, state);
            OnnxTensor srTensor = OnnxTensor.createTensor(env, new long[]{16000});

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input", inputTensor);
            inputs.put("state", stateTensor);
            inputs.put("sr", srTensor);

            OrtSession.Result result = session.run(inputs);

            // output [1, 1]
            float[][] output = (float[][]) result.get(0).getValue();
            float prob = output[0][0];

            // update state
            state = (float[][][]) result.get(1).getValue();

            inputTensor.close();
            stateTensor.close();
            srTensor.close();
            result.close();

            return prob;

        } catch (Exception e) {
            Log.e(TAG, "VAD inference error", e);
            return 0f;
        }
    }

    /**
     * Check if speech detected
     */
    public boolean isSpeech(float[] audio512) {
        return process(audio512) >= threshold;
    }

    public void release() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (Exception e) {
            Log.e(TAG, "Release error", e);
        }
    }
}
