package com.t527.wav2vecdemo.conformer;

import android.util.Log;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;

public class SttTorchscriptMel {
    private static final String TAG = "SttTorchscriptMel";
    public static final int N_MELS = 80;
    public static final int WINDOW_FRAMES = 301;
    public static final int STRIDE_FRAMES = 250;

    private Module module;

    public static class MelResult {
        public final float[] mel;
        public final int nFrames;

        MelResult(float[] mel, int nFrames) {
            this.mel = mel;
            this.nFrames = nFrames;
        }
    }

    public boolean init(String modelPath) {
        try {
            module = Module.load(modelPath);
            Log.d(TAG, "Loaded STT TorchScript mel: " + modelPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load STT TorchScript mel: " + modelPath, e);
            module = null;
            return false;
        }
    }

    public boolean isInit() {
        return module != null;
    }

    public MelResult compute(float[] audio16k) {
        if (module == null || audio16k == null || audio16k.length == 0) {
            return new MelResult(new float[0], 0);
        }

        Tensor audioTensor = Tensor.fromBlob(audio16k, new long[]{1, audio16k.length});
        Tensor out = module.forward(IValue.from(audioTensor)).toTensor();
        long[] shape = out.shape();
        float[] data = out.getDataAsFloatArray();

        int nFrames;
        if (shape.length == 3 && shape[1] == N_MELS) {
            nFrames = (int) shape[2];
        } else if (shape.length == 4 && shape[2] == N_MELS) {
            nFrames = (int) shape[3];
        } else if (shape.length == 2 && shape[0] == N_MELS) {
            nFrames = (int) shape[1];
        } else {
            throw new IllegalStateException("Unexpected STT mel shape: " + java.util.Arrays.toString(shape));
        }

        Log.d(TAG, "STT TorchScript mel frames=" + nFrames);
        return new MelResult(data, nFrames);
    }

    public byte[] quantizeChunk(MelResult full, int startFrame, float scale, int zeroPoint) {
        byte[] out = new byte[N_MELS * WINDOW_FRAMES];
        if (full == null || full.mel == null || full.nFrames <= 0) return out;

        for (int m = 0; m < N_MELS; m++) {
            for (int t = 0; t < WINDOW_FRAMES; t++) {
                int srcT = startFrame + t;
                float val = srcT < full.nFrames ? full.mel[m * full.nFrames + srcT] : 0.0f;
                int q = Math.round(val / scale + zeroPoint);
                if (q < 0) q = 0;
                if (q > 255) q = 255;
                out[m * WINDOW_FRAMES + t] = (byte) q;
            }
        }
        return out;
    }
}
