package com.t527.wav2vecdemo.citrinet;

/**
 * Java port of bundle_app's WavFrontend.kt (mode7 parameters hardcoded).
 * Produces identical results to Kotlin on the JVM (same float32/double arithmetic,
 * same Math.cos/sin, same FFT twiddle factors).
 *
 * mode7: Slaney mel+enorm, log10, preEmphasis=0.97, centerPad=REFLECT(256),
 *        periodicHann, preferEnergyWindow=true, normalizePerFeature, padTo16, crop300
 */
public class KoMelFrontend {
    private static final int TARGET_SR    = 16000;
    private static final int N_FFT        = 512;
    private static final int WIN          = 400;
    private static final int HOP          = 160;
    private static final int PAD_TO       = 16;
    private static final int MEL_BINS     = 80;
    private static final int TIME_FRAMES  = 300;
    private static final float PRE_EMPH   = 0.97f;
    private static final float LOG_EPS    = 5.9604645e-8f;
    private static final int N_FREQ       = N_FFT / 2 + 1;  // 257

    // Quantization (from nbg_meta.json)
    private static final float INPUT_SCALE = 0.02096451073884964f;
    private static final int   INPUT_ZP    = -37;

    /**
     * Compute mel spectrogram + quantize → returns int8 byte[] ready for NPU.
     * @param audio  float[] at 16kHz (already resampled if needed)
     * @return byte[80*300 = 24000] in channel-major (NCHW) layout
     */
    public static byte[] computeAndQuantize(float[] audio) {
        float[][] mel = computeMel(audio);
        return quantize(mel);
    }

    // -----------------------------------------------------------------------
    // Internal pipeline
    // -----------------------------------------------------------------------

    private static float[][] computeMel(float[] audio) {
        // 1. selectBestEnergyWindow
        float[] sel = selectBestEnergyWindow(audio, TIME_FRAMES);

        // 2. preEmphasis
        float[] emph = applyPreEmphasis(sel, PRE_EMPH);

        // 3. centerPad REFLECT (256 each side)
        float[] padded = centerPadReflect(emph, N_FFT / 2);

        // 4. STFT + power spectrum
        int frameCount = (padded.length <= WIN) ? 1 : 1 + (padded.length - WIN) / HOP;
        float[][] powerSpec = computePowerSpec(padded, frameCount);

        // 5. Mel filterbank
        float[][] filterBank = buildMelFilterBank();

        // 6. Apply mel + log10
        float[][] mel = applyMelFilterBank(powerSpec, filterBank);

        // 7. normalizePerFeature
        normalizePerFeature(mel);

        // 8. padTo16 + crop300
        return cropOrPad(mel, frameCount);
    }

    private static float[] selectBestEnergyWindow(float[] audio, int frameTarget) {
        int windowSamples = (frameTarget - 1) * HOP + WIN;
        if (windowSamples < WIN) windowSamples = WIN;
        if (audio.length <= windowSamples) return audio;

        int bestStart = 0;
        double bestEnergy = Double.NEGATIVE_INFINITY;

        int start = 0;
        while (start + windowSamples <= audio.length) {
            double e = 0.0;
            int end = start + windowSamples;
            for (int i = start; i < end; i++) {
                double v = audio[i];
                e += v * v;
            }
            if (e > bestEnergy) { bestEnergy = e; bestStart = start; }
            start += HOP;
        }

        int remainStart = Math.max(audio.length - windowSamples, 0);
        if (remainStart > bestStart) {
            double e = 0.0;
            for (int i = remainStart; i < audio.length; i++) {
                double v = audio[i];
                e += v * v;
            }
            if (e > bestEnergy) bestStart = remainStart;
        }

        float[] out = new float[windowSamples];
        System.arraycopy(audio, bestStart, out, 0, windowSamples);
        return out;
    }

    private static float[] applyPreEmphasis(float[] input, float coeff) {
        if (input.length == 0) return input;
        float[] out = new float[input.length];
        out[0] = input[0];
        for (int i = 1; i < input.length; i++)
            out[i] = input[i] - coeff * input[i - 1];
        return out;
    }

    private static float[] centerPadReflect(float[] input, int pad) {
        float[] out = new float[input.length + pad * 2];
        // left reflect: out[pad-1-i] = reflectAt(input, i+1)
        for (int i = 0; i < pad; i++)
            out[pad - 1 - i] = reflectAt(input, i + 1);
        System.arraycopy(input, 0, out, pad, input.length);
        // right reflect: out[pad+len+i] = reflectAt(input, len-2-i)
        for (int i = 0; i < pad; i++)
            out[pad + input.length + i] = reflectAt(input, input.length - 2 - i);
        return out;
    }

    private static float reflectAt(float[] input, int index) {
        if (input.length == 0) return 0f;
        if (input.length == 1) return input[0];
        int i = index;
        int max = input.length - 1;
        while (i < 0 || i > max) {
            if (i < 0) i = -i;
            else i = 2 * max - i;
        }
        return input[i];
    }

    private static float[][] computePowerSpec(float[] signal, int frameCount) {
        // Periodic Hann window (float32) — matches Kotlin FloatArray
        float[] hann = new float[WIN];
        for (int i = 0; i < WIN; i++)
            hann[i] = (float)(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / WIN));

        double[] real = new double[N_FFT];
        double[] imag = new double[N_FFT];
        float[][] output = new float[frameCount][N_FREQ];

        for (int t = 0; t < frameCount; t++) {
            // Fill FFT input with float32*float32 -> toDouble(), rest zero
            java.util.Arrays.fill(real, 0.0);
            java.util.Arrays.fill(imag, 0.0);
            int startSample = t * HOP;
            for (int n = 0; n < WIN; n++) {
                int idx = startSample + n;
                float s = (idx < signal.length) ? signal[idx] : 0f;
                real[n] = (double)(s * hann[n]);  // float32*float32 -> float32 -> double
            }

            fftRadix2(real, imag);

            for (int f = 0; f < N_FREQ; f++) {
                // Kotlin: (real[f]*real[f] + imag[f]*imag[f]).toFloat()
                output[t][f] = (float)(real[f] * real[f] + imag[f] * imag[f]);
            }
        }
        return output;
    }

    /** Iterative Cooley-Tukey radix-2 FFT — mirrors Kotlin fftRadix2 exactly */
    private static void fftRadix2(double[] real, double[] imag) {
        int n = real.length;
        // bit-reversal
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        // butterfly
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wLenCos = Math.cos(angle);
            double wLenSin = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wCos = 1.0, wSin = 0.0;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    double uReal = real[i + k];
                    double uImag = imag[i + k];
                    double vReal = real[i + k + half] * wCos - imag[i + k + half] * wSin;
                    double vImag = real[i + k + half] * wSin + imag[i + k + half] * wCos;
                    real[i + k]        = uReal + vReal;
                    imag[i + k]        = uImag + vImag;
                    real[i + k + half] = uReal - vReal;
                    imag[i + k + half] = uImag - vImag;
                    double nextCos = wCos * wLenCos - wSin * wLenSin;
                    double nextSin = wCos * wLenSin + wSin * wLenCos;
                    wCos = nextCos;
                    wSin = nextSin;
                }
            }
        }
    }

    private static float[][] buildMelFilterBank() {
        float[][] filters = new float[MEL_BINS][N_FREQ];

        double melMin = hzToMelSlaney(0.0);
        double melMax = hzToMelSlaney(TARGET_SR / 2.0);

        double[] melPts = new double[MEL_BINS + 2];
        for (int i = 0; i < MEL_BINS + 2; i++)
            melPts[i] = melMin + (melMax - melMin) * i / (MEL_BINS + 1);

        double[] hzPts = new double[MEL_BINS + 2];
        for (int i = 0; i < MEL_BINS + 2; i++)
            hzPts[i] = melToHzSlaney(melPts[i]);

        int[] bins = new int[MEL_BINS + 2];
        for (int i = 0; i < MEL_BINS + 2; i++) {
            int b = (int) Math.floor((N_FFT + 1) * hzPts[i] / TARGET_SR);
            bins[i] = Math.max(0, Math.min(b, N_FREQ - 1));
        }

        for (int m = 0; m < MEL_BINS; m++) {
            int left = bins[m], center = bins[m + 1], right = bins[m + 2];

            // Step 1: fill raw triangular weights (no enorm) — matches Kotlin order
            if (center > left) {
                for (int k = left; k < center; k++)
                    filters[m][k] = (float)(k - left) / (float)(center - left);
            }
            if (right > center) {
                for (int k = center; k < right; k++)
                    filters[m][k] = (float)(right - k) / (float)(right - center);
            }

            // Step 2: apply Slaney enorm in a separate pass — matches Kotlin exactly:
            //   val denom = (hzPoints[m+1] - hzPoints[m-1]).toFloat()
            //   val enorm = 2.0f / denom
            //   for (k in 0 until fftBins) { filters[m-1][k] *= enorm }
            float denom = (float)(hzPts[m + 2] - hzPts[m]);
            if (denom > 0f) {
                float enorm = 2.0f / denom;
                for (int k = 0; k < N_FREQ; k++)
                    filters[m][k] *= enorm;
            }
        }
        return filters;
    }

    private static float[][] applyMelFilterBank(float[][] powerSpec, float[][] filterBank) {
        int frames = powerSpec.length;
        float[][] mel = new float[MEL_BINS][frames];
        for (int t = 0; t < frames; t++) {
            for (int m = 0; m < MEL_BINS; m++) {
                float sum = 0.0f;  // float32 accumulation — matches Kotlin
                float[] filter = filterBank[m];
                float[] spec   = powerSpec[t];
                for (int k = 0; k < N_FREQ; k++) sum += spec[k] * filter[k];
                // log10: coerceAtLeast(LOG_EPS).toDouble() then log10, then toFloat
                double safe = (sum > LOG_EPS) ? (double) sum : (double) LOG_EPS;
                mel[m][t] = (float) Math.log10(safe);
            }
        }
        return mel;
    }

    private static void normalizePerFeature(float[][] feature) {
        for (int ch = 0; ch < feature.length; ch++) {
            float[] row = feature[ch];
            if (row.length == 0) continue;
            double mean = 0.0;
            for (float v : row) mean += v;
            mean /= row.length;

            double variance = 0.0;
            for (float v : row) { double d = v - mean; variance += d * d; }
            variance /= row.length;

            // Kotlin: sqrt(variance).toFloat().coerceAtLeast(1e-5f)
            float std = (float) Math.sqrt(variance);
            if (std < 1e-5f) std = 1e-5f;

            for (int i = 0; i < row.length; i++)
                row[i] = (float)((row[i] - mean) / (double) std);
        }
    }

    private static float[][] cropOrPad(float[][] mel, int n_frames) {
        // padTo16
        int extra = (PAD_TO - (n_frames % PAD_TO)) % PAD_TO;
        int padded = n_frames + extra;
        // cropTo TIME_FRAMES
        int copyFrames = Math.min(padded, TIME_FRAMES);
        int srcCopy    = Math.min(n_frames, copyFrames);

        float[][] out = new float[MEL_BINS][TIME_FRAMES];
        for (int ch = 0; ch < MEL_BINS; ch++)
            System.arraycopy(mel[ch], 0, out[ch], 0, srcCopy);
        return out;
    }

    private static byte[] quantize(float[][] feature) {
        // i8 qtype: clamp to [-128, 127]
        byte[] out = new byte[MEL_BINS * TIME_FRAMES];
        for (int ch = 0; ch < MEL_BINS; ch++) {
            for (int ti = 0; ti < TIME_FRAMES; ti++) {
                // Kotlin: (feature[ch][ti] / scale + zeroPoint).roundToInt()
                int q = Math.round(feature[ch][ti] / INPUT_SCALE) + INPUT_ZP;
                if (q < -128) q = -128;
                if (q >  127) q =  127;
                out[ch * TIME_FRAMES + ti] = (byte) q;
            }
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Slaney mel scale helpers (matches Kotlin/C double precision)
    // -----------------------------------------------------------------------
    private static double hzToMelSlaney(double hz) {
        double fSp = 200.0 / 3.0;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logStep = Math.log(6.4) / 27.0;
        return (hz < minLogHz) ? hz / fSp : minLogMel + Math.log(hz / minLogHz) / logStep;
    }

    private static double melToHzSlaney(double mel) {
        double fSp = 200.0 / 3.0;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logStep = Math.log(6.4) / 27.0;
        return (mel < minLogMel) ? fSp * mel : minLogHz * Math.exp(logStep * (mel - minLogMel));
    }
}
