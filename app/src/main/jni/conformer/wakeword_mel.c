#include "wakeword_mel.h"
#include "../kissfft/kiss_fft.h"
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>

#define TAG "WakewordMel"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static float s_hann[WK_MEL_WIN_LEN];
static float s_fb[WK_MEL_N_MELS][WK_MEL_N_FFT / 2 + 1];
static kiss_fft_cfg s_fft = NULL;
static int s_init = 0;

void wakeword_mel_init(void) {
    if (s_init) return;

    // Symmetric Hann window
    for (int i = 0; i < WK_MEL_WIN_LEN; i++)
        s_hann[i] = 0.5f - 0.5f * cosf(2.0f * M_PI * i / (WK_MEL_WIN_LEN - 1));

    // HTK mel filterbank
    int fft_bins = WK_MEL_N_FFT / 2 + 1;
    float freq_per_bin = (float)WK_MEL_SR / WK_MEL_N_FFT;

    float mel_min = 2595.0f * log10f(1.0f + 0.0f / 700.0f);
    float mel_max = 2595.0f * log10f(1.0f + (float)WK_MEL_SR / 2.0f / 700.0f);

    float mel_pts[WK_MEL_N_MELS + 2];
    float hz_pts[WK_MEL_N_MELS + 2];
    for (int i = 0; i < WK_MEL_N_MELS + 2; i++) {
        mel_pts[i] = mel_min + (mel_max - mel_min) * i / (WK_MEL_N_MELS + 1);
        hz_pts[i] = 700.0f * (powf(10.0f, mel_pts[i] / 2595.0f) - 1.0f);
    }

    memset(s_fb, 0, sizeof(s_fb));
    for (int m = 0; m < WK_MEL_N_MELS; m++) {
        float left = hz_pts[m], center = hz_pts[m + 1], right = hz_pts[m + 2];
        for (int k = 0; k < fft_bins; k++) {
            float f = k * freq_per_bin;
            if (f > left && f < center)
                s_fb[m][k] = (f - left) / (center - left);
            else if (f >= center && f < right)
                s_fb[m][k] = (right - f) / (right - center);
        }
    }

    s_fft = kiss_fft_alloc(WK_MEL_N_FFT, 0, NULL, NULL);
    s_init = 1;
    LOGD("wakeword_mel_init done");
}

void wakeword_mel_cleanup(void) {
    if (s_fft) { free(s_fft); s_fft = NULL; }
    s_init = 0;
}

int wakeword_mel_compute(const float* audio, int audio_len,
                         uint8_t* out_mel, float scale, int zero_point) {
    if (!s_init) wakeword_mel_init();

    int fft_bins = WK_MEL_N_FFT / 2 + 1;
    int target = WK_MEL_TARGET_LEN;
    int pad = WK_MEL_N_FFT / 2;

    // Trim/pad to 1.5s
    float wav[WK_MEL_TARGET_LEN];
    memset(wav, 0, sizeof(wav));
    int copy_len = audio_len < target ? audio_len : target;
    memcpy(wav, audio, copy_len * sizeof(float));

    // Reflect pad
    int padded_len = target + 2 * pad;
    float* padded = (float*)calloc(padded_len, sizeof(float));
    for (int i = 0; i < pad; i++) {
        int idx = pad - i;
        if (idx >= target) idx = target - 1;
        padded[i] = wav[idx];
    }
    memcpy(padded + pad, wav, target * sizeof(float));
    for (int i = 0; i < pad; i++) {
        int idx = target - 2 - i;
        if (idx < 0) idx = 0;
        padded[pad + target + i] = wav[idx];
    }

    // STFT + mel
    int n_frames = (padded_len - WK_MEL_N_FFT) / WK_MEL_HOP_LEN + 1;
    if (n_frames > WK_MEL_FRAMES) n_frames = WK_MEL_FRAMES;

    kiss_fft_cpx fft_in[WK_MEL_N_FFT];
    kiss_fft_cpx fft_out[WK_MEL_N_FFT];

    memset(out_mel, 0, WK_MEL_N_MELS * WK_MEL_FRAMES);

    for (int t = 0; t < n_frames; t++) {
        int start = t * WK_MEL_HOP_LEN;
        memset(fft_in, 0, sizeof(fft_in));
        for (int i = 0; i < WK_MEL_WIN_LEN && (start + i) < padded_len; i++) {
            fft_in[i].r = padded[start + i] * s_hann[i];
            fft_in[i].i = 0;
        }

        kiss_fft(s_fft, fft_in, fft_out);

        float power[WK_MEL_N_FFT / 2 + 1];
        for (int k = 0; k < fft_bins; k++)
            power[k] = fft_out[k].r * fft_out[k].r + fft_out[k].i * fft_out[k].i;

        for (int m = 0; m < WK_MEL_N_MELS; m++) {
            float sum = 0;
            for (int k = 0; k < fft_bins; k++)
                sum += s_fb[m][k] * power[k];
            float log_val = logf(sum + 1e-6f);
            float q = log_val / scale + (float)zero_point;
            int ival = (int)roundf(q);
            if (ival < 0) ival = 0;
            if (ival > 255) ival = 255;
            out_mel[m * WK_MEL_FRAMES + t] = (uint8_t)ival;
        }
    }

    free(padded);
    return n_frames;
}
