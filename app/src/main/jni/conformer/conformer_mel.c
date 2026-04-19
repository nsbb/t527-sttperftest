#include "conformer_mel.h"
#include "../kissfft/kiss_fft.h"
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>

#define TAG "ConformerMel"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#define FFT_SIZE    CONF_MEL_N_FFT         // 512
#define FFT_BINS    (FFT_SIZE / 2 + 1)     // 257
#define WIN_LEN     CONF_MEL_WIN_LENGTH    // 400
#define HOP_LEN     CONF_MEL_HOP_LENGTH    // 160
#define N_MELS      CONF_MEL_N_MELS        // 80
#define TIME_FRAMES CONF_MEL_TIME_FRAMES   // 301
#define PAD_LEN     (FFT_SIZE / 2)         // 256 (center padding)

static float s_hann_window[WIN_LEN];
static float s_mel_filterbank[N_MELS][FFT_BINS];
static kiss_fft_cfg s_fft_cfg = NULL;
static int s_initialized = 0;

// Slaney mel scale
static float hz_to_mel(float hz) {
    if (hz < 1000.0f)
        return hz * 3.0f / 200.0f;  // linear below 1000
    else
        return 15.0f + 27.0f * logf(hz / 1000.0f) / logf(6.4f); // log above 1000 (Slaney)
}

static float mel_to_hz(float mel) {
    if (mel < 15.0f)
        return mel * 200.0f / 3.0f;
    else
        return 1000.0f * expf((mel - 15.0f) / 27.0f * logf(6.4f));
}

static void build_mel_filterbank(void) {
    float fmin = CONF_MEL_FMIN;
    float fmax = CONF_MEL_FMAX;
    float mel_min = hz_to_mel(fmin);
    float mel_max = hz_to_mel(fmax);

    // N_MELS + 2 mel points
    float mel_points[N_MELS + 2];
    for (int i = 0; i < N_MELS + 2; i++) {
        mel_points[i] = mel_min + (mel_max - mel_min) * i / (N_MELS + 1);
    }

    // Convert to Hz
    float hz_points[N_MELS + 2];
    for (int i = 0; i < N_MELS + 2; i++) {
        hz_points[i] = mel_to_hz(mel_points[i]);
    }

    // Convert to FFT bin indices (float)
    float bin_points[N_MELS + 2];
    float freq_per_bin = (float)CONF_MEL_SAMPLE_RATE / FFT_SIZE;
    for (int i = 0; i < N_MELS + 2; i++) {
        bin_points[i] = hz_points[i] / freq_per_bin;
    }

    // Build triangular filters with Slaney normalization
    memset(s_mel_filterbank, 0, sizeof(s_mel_filterbank));
    for (int m = 0; m < N_MELS; m++) {
        float left = bin_points[m];
        float center = bin_points[m + 1];
        float right = bin_points[m + 2];

        // Slaney normalization: 2 / (hz_right - hz_left)
        float slaney_norm = 2.0f / (hz_points[m + 2] - hz_points[m]);

        for (int k = 0; k < FFT_BINS; k++) {
            float fk = (float)k;
            if (fk >= left && fk <= center) {
                s_mel_filterbank[m][k] = slaney_norm * (fk - left) / (center - left);
            } else if (fk > center && fk <= right) {
                s_mel_filterbank[m][k] = slaney_norm * (right - fk) / (right - center);
            }
        }
    }
}

static void build_hann_window(void) {
    // Periodic Hann window (denominator = WIN_LEN, not WIN_LEN-1)
    for (int i = 0; i < WIN_LEN; i++) {
        s_hann_window[i] = 0.5f - 0.5f * cosf(2.0f * M_PI * i / WIN_LEN);
    }
}

void conformer_mel_init(void) {
    if (s_initialized) return;

    build_hann_window();
    build_mel_filterbank();
    s_fft_cfg = kiss_fft_alloc(FFT_SIZE, 0, NULL, NULL);

    s_initialized = 1;
    LOGD("conformer_mel_init done: win=%d, hop=%d, fft=%d, mels=%d", WIN_LEN, HOP_LEN, FFT_SIZE, N_MELS);
}

void conformer_mel_cleanup(void) {
    if (s_fft_cfg) {
        free(s_fft_cfg);
        s_fft_cfg = NULL;
    }
    s_initialized = 0;
}

int conformer_mel_compute(
    const float* audio, int audio_len,
    uint8_t* out_mel,
    float scale, int zero_point
) {
    if (!s_initialized) conformer_mel_init();

    // Step 1: Center padding (reflect mode)
    int padded_len = audio_len + 2 * PAD_LEN;
    float* padded = (float*)calloc(padded_len, sizeof(float));

    // Reflect pad left
    for (int i = 0; i < PAD_LEN; i++) {
        int idx = PAD_LEN - i;
        if (idx >= audio_len) idx = audio_len - 1;
        padded[i] = audio[idx];
    }
    // Copy audio
    memcpy(padded + PAD_LEN, audio, audio_len * sizeof(float));
    // Reflect pad right
    for (int i = 0; i < PAD_LEN; i++) {
        int idx = audio_len - 2 - i;
        if (idx < 0) idx = 0;
        padded[PAD_LEN + audio_len + i] = audio[idx];
    }

    // Step 2: Compute number of frames
    int n_frames = (padded_len - FFT_SIZE) / HOP_LEN + 1;
    if (n_frames > TIME_FRAMES) n_frames = TIME_FRAMES;

    // Step 3: Allocate mel buffer (float)
    float mel_float[N_MELS][TIME_FRAMES];
    memset(mel_float, 0, sizeof(mel_float));

    // Step 4: STFT + mel filterbank for each frame
    kiss_fft_cpx fft_in[FFT_SIZE];
    kiss_fft_cpx fft_out[FFT_SIZE];
    float power[FFT_BINS];

    for (int t = 0; t < n_frames; t++) {
        int start = t * HOP_LEN;

        // Window + zero-pad to FFT_SIZE
        memset(fft_in, 0, sizeof(fft_in));
        for (int i = 0; i < WIN_LEN && (start + i) < padded_len; i++) {
            fft_in[i].r = padded[start + i] * s_hann_window[i];
            fft_in[i].i = 0.0f;
        }

        // FFT
        kiss_fft(s_fft_cfg, fft_in, fft_out);

        // Power spectrum
        for (int k = 0; k < FFT_BINS; k++) {
            power[k] = fft_out[k].r * fft_out[k].r + fft_out[k].i * fft_out[k].i;
        }

        // Mel filterbank
        for (int m = 0; m < N_MELS; m++) {
            float sum = 0.0f;
            for (int k = 0; k < FFT_BINS; k++) {
                sum += s_mel_filterbank[m][k] * power[k];
            }
            // Log mel (natural log + guard)
            mel_float[m][t] = logf(sum + CONF_MEL_LOG_GUARD);
        }
    }

    // Step 5: Per-feature normalization
    for (int m = 0; m < N_MELS; m++) {
        // Compute mean
        float mean = 0.0f;
        for (int t = 0; t < n_frames; t++) {
            mean += mel_float[m][t];
        }
        mean /= (float)n_frames;

        // Compute std
        float var = 0.0f;
        for (int t = 0; t < n_frames; t++) {
            float diff = mel_float[m][t] - mean;
            var += diff * diff;
        }
        float std = sqrtf(var / (float)n_frames);

        // Normalize: (val - mean) / (std + 1e-5)
        float inv_std = 1.0f / (std + 1e-5f);
        for (int t = 0; t < TIME_FRAMES; t++) {
            if (t < n_frames) {
                mel_float[m][t] = (mel_float[m][t] - mean) * inv_std;
            } else {
                // Zero-pad frames: normalize the zero value too
                mel_float[m][t] = (0.0f - mean) * inv_std;
            }
        }
    }

    // Step 6: Quantize to uint8
    for (int m = 0; m < N_MELS; m++) {
        for (int t = 0; t < TIME_FRAMES; t++) {
            float val = mel_float[m][t] / scale + (float)zero_point;
            int ival = (int)roundf(val);
            if (ival < 0) ival = 0;
            if (ival > 255) ival = 255;
            out_mel[m * TIME_FRAMES + t] = (uint8_t)ival;
        }
    }

    LOGD("mel computed: audio_len=%d, frames=%d/%d", audio_len, n_frames, TIME_FRAMES);

    free(padded);
    return n_frames;
}
