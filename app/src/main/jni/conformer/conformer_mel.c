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

// N series: per-feature normalization mode
static int s_norm_mode = 0;       // 0=standard, 1=voiced-only, 2=floor-clamp, 3=noise-subtract
static float s_voiced_pct = 30.0f; // percentile threshold for voiced detection
static float s_floor_log = -8.0f;  // log-mel floor for mode 2

void conformer_mel_set_norm_mode(int mode, float voiced_pct, float floor_log) {
    s_norm_mode = mode;
    s_voiced_pct = voiced_pct;
    s_floor_log = floor_log;
    LOGD("MEL_NORM mode=%d voiced_pct=%.1f floor_log=%.2f", mode, voiced_pct, floor_log);
}

// Helper: compute kth-smallest value (approximate via partial sort).
static float percentile_inplace(float* buf, int n, float pct) {
    int k = (int)((pct / 100.0f) * n);
    if (k < 0) k = 0; if (k >= n) k = n - 1;
    // Simple selection: insertion sort the first k+1 elements, scan rest
    // For small arrays this is OK; n ~ a few hundred frames.
    for (int i = 1; i < n; i++) {
        float v = buf[i];
        int j = i - 1;
        while (j >= 0 && buf[j] > v) { buf[j+1] = buf[j]; j--; }
        buf[j+1] = v;
    }
    return buf[k];
}

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

int conformer_mel_frame_count(int audio_len) {
    if (audio_len <= 0) return 0;
    int padded_len = audio_len + 2 * PAD_LEN;
    if (padded_len < FFT_SIZE) return 0;
    return (padded_len - FFT_SIZE) / HOP_LEN + 1;
}

int conformer_mel_chunk_count(int n_frames) {
    if (n_frames <= 0) return 0;
    int chunks = 0;
    int start = 0;
    while (start < n_frames) {
        chunks++;
        if (start + TIME_FRAMES >= n_frames) break;
        start += CONF_MEL_STRIDE_FRAMES;
    }
    return chunks;
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
                // NeMo pads fixed windows after per-feature normalization.
                mel_float[m][t] = 0.0f;
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

int conformer_mel_compute_chunks(
    const float* audio, int audio_len,
    uint8_t* out_chunks,
    float scale, int zero_point
) {
    if (!s_initialized) conformer_mel_init();
    if (!audio || audio_len <= 0 || !out_chunks) return 0;

    // Step 1: Center padding (reflect mode), matching torch.stft center=True.
    int padded_len = audio_len + 2 * PAD_LEN;
    float* padded = (float*)calloc(padded_len, sizeof(float));
    if (!padded) return 0;

    for (int i = 0; i < PAD_LEN; i++) {
        int idx = PAD_LEN - i;
        if (idx >= audio_len) idx = audio_len - 1;
        padded[i] = audio[idx];
    }
    memcpy(padded + PAD_LEN, audio, audio_len * sizeof(float));
    for (int i = 0; i < PAD_LEN; i++) {
        int idx = audio_len - 2 - i;
        if (idx < 0) idx = 0;
        padded[PAD_LEN + audio_len + i] = audio[idx];
    }

    int n_frames = conformer_mel_frame_count(audio_len);
    int num_chunks = conformer_mel_chunk_count(n_frames);
    if (n_frames <= 0 || num_chunks <= 0) {
        free(padded);
        return 0;
    }

    float* mel_float = (float*)calloc((size_t)N_MELS * n_frames, sizeof(float));
    if (!mel_float) {
        free(padded);
        return 0;
    }

    kiss_fft_cpx fft_in[FFT_SIZE];
    kiss_fft_cpx fft_out[FFT_SIZE];
    float power[FFT_BINS];

    for (int t = 0; t < n_frames; t++) {
        int start = t * HOP_LEN;
        memset(fft_in, 0, sizeof(fft_in));
        for (int i = 0; i < WIN_LEN && (start + i) < padded_len; i++) {
            fft_in[i].r = padded[start + i] * s_hann_window[i];
            fft_in[i].i = 0.0f;
        }

        kiss_fft(s_fft_cfg, fft_in, fft_out);

        for (int k = 0; k < FFT_BINS; k++) {
            power[k] = fft_out[k].r * fft_out[k].r + fft_out[k].i * fft_out[k].i;
        }

        for (int m = 0; m < N_MELS; m++) {
            float sum = 0.0f;
            for (int k = 0; k < FFT_BINS; k++) {
                sum += s_mel_filterbank[m][k] * power[k];
            }
            mel_float[m * n_frames + t] = logf(sum + CONF_MEL_LOG_GUARD);
        }
    }

    // N series mel-domain processing before per-feature normalization
    if (s_norm_mode == 2) {
        // Mode 2: floor clamp log-mel
        for (int t = 0; t < n_frames; t++) {
            for (int m = 0; m < N_MELS; m++) {
                float v = mel_float[m * n_frames + t];
                if (v < s_floor_log) v = s_floor_log;
                mel_float[m * n_frames + t] = v;
            }
        }
    } else if (s_norm_mode == 3) {
        // Mode 3: subtract per-bin 5th percentile
        float* tmp = (float*)malloc(n_frames * sizeof(float));
        if (tmp) {
            for (int m = 0; m < N_MELS; m++) {
                for (int t = 0; t < n_frames; t++) tmp[t] = mel_float[m * n_frames + t];
                float p5 = percentile_inplace(tmp, n_frames, 5.0f);
                for (int t = 0; t < n_frames; t++) {
                    float v = mel_float[m * n_frames + t] - p5;
                    if (v < s_floor_log) v = s_floor_log;
                    mel_float[m * n_frames + t] = v;
                }
            }
            free(tmp);
        }
    }

    // Identify voiced frames if mode 1 enabled
    int* voiced_frame = NULL;
    int n_voiced = n_frames;
    if (s_norm_mode == 1 && n_frames > 10) {
        float* total_e = (float*)malloc(n_frames * sizeof(float));
        float* sorted_e = (float*)malloc(n_frames * sizeof(float));
        voiced_frame = (int*)malloc(n_frames * sizeof(int));
        if (total_e && sorted_e && voiced_frame) {
            for (int t = 0; t < n_frames; t++) {
                float s = 0.0f;
                for (int m = 0; m < N_MELS; m++) s += mel_float[m * n_frames + t];
                total_e[t] = s;
                sorted_e[t] = s;
            }
            float thresh = percentile_inplace(sorted_e, n_frames, s_voiced_pct);
            n_voiced = 0;
            for (int t = 0; t < n_frames; t++) {
                voiced_frame[t] = (total_e[t] >= thresh) ? 1 : 0;
                if (voiced_frame[t]) n_voiced++;
            }
            if (n_voiced < 5) {
                free(voiced_frame); voiced_frame = NULL; n_voiced = n_frames;
            }
        }
        if (total_e) free(total_e);
        if (sorted_e) free(sorted_e);
    }

    // Per-feature normalization
    for (int m = 0; m < N_MELS; m++) {
        float mean = 0.0f;
        if (voiced_frame) {
            for (int t = 0; t < n_frames; t++) if (voiced_frame[t]) mean += mel_float[m * n_frames + t];
            mean /= (float)n_voiced;
        } else {
            for (int t = 0; t < n_frames; t++) mean += mel_float[m * n_frames + t];
            mean /= (float)n_frames;
        }

        float var = 0.0f;
        if (voiced_frame) {
            for (int t = 0; t < n_frames; t++) {
                if (voiced_frame[t]) {
                    float d = mel_float[m * n_frames + t] - mean;
                    var += d * d;
                }
            }
            var /= (float)n_voiced;
        } else {
            for (int t = 0; t < n_frames; t++) {
                float d = mel_float[m * n_frames + t] - mean;
                var += d * d;
            }
            var /= (float)n_frames;
        }
        float inv_std = 1.0f / (sqrtf(var) + 1e-5f);
        for (int t = 0; t < n_frames; t++) {
            mel_float[m * n_frames + t] = (mel_float[m * n_frames + t] - mean) * inv_std;
        }
    }
    if (voiced_frame) free(voiced_frame);

    const int chunk_size = N_MELS * TIME_FRAMES;
    int chunk_idx = 0;
    int start_frame = 0;
    while (start_frame < n_frames && chunk_idx < num_chunks) {
        uint8_t* out = out_chunks + chunk_idx * chunk_size;
        for (int m = 0; m < N_MELS; m++) {
            for (int t = 0; t < TIME_FRAMES; t++) {
                int src_t = start_frame + t;
                float src = (src_t < n_frames) ? mel_float[m * n_frames + src_t] : 0.0f;
                int ival = (int)roundf(src / scale + (float)zero_point);
                if (ival < 0) ival = 0;
                if (ival > 255) ival = 255;
                out[m * TIME_FRAMES + t] = (uint8_t)ival;
            }
        }

        chunk_idx++;
        if (start_frame + TIME_FRAMES >= n_frames) break;
        start_frame += CONF_MEL_STRIDE_FRAMES;
    }

    LOGD("mel chunks computed: audio_len=%d, frames=%d, chunks=%d", audio_len, n_frames, chunk_idx);

    free(mel_float);
    free(padded);
    return chunk_idx;
}
