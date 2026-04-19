#ifndef CONFORMER_MEL_H
#define CONFORMER_MEL_H

#include <stdint.h>

#define CONF_MEL_SAMPLE_RATE 16000
#define CONF_MEL_N_FFT       512
#define CONF_MEL_WIN_LENGTH  400
#define CONF_MEL_HOP_LENGTH  160
#define CONF_MEL_N_MELS      80
#define CONF_MEL_FMIN        0.0f
#define CONF_MEL_FMAX        8000.0f
#define CONF_MEL_TIME_FRAMES 301
#define CONF_MEL_LOG_GUARD   1e-5f

// Initialize mel pipeline (pre-compute filterbank + window)
void conformer_mel_init(void);

// Cleanup
void conformer_mel_cleanup(void);

// Compute mel spectrogram from 16kHz float audio
// audio: float32 at 16kHz, range [-1, 1]
// audio_len: number of samples
// out_mel: output buffer, must be N_MELS * TIME_FRAMES = 80*301 = 24080 bytes
// scale, zero_point: uint8 quantization params from nbg_meta.json
// Returns actual number of mel frames (before padding to TIME_FRAMES)
int conformer_mel_compute(
    const float* audio, int audio_len,
    uint8_t* out_mel,
    float scale, int zero_point
);

#endif // CONFORMER_MEL_H
