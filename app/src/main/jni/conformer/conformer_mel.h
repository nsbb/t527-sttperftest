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
#define CONF_MEL_STRIDE_FRAMES 250
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

// Return NeMo-compatible full-mel frame count for an audio buffer.
int conformer_mel_frame_count(int audio_len);

// Return number of 301-frame chunks for a full mel using 250-frame stride.
int conformer_mel_chunk_count(int n_frames);

// Compute full normalized mel once, then emit quantized 301-frame chunks.
// out_chunks must hold chunk_count * N_MELS * TIME_FRAMES bytes.
int conformer_mel_compute_chunks(
    const float* audio, int audio_len,
    uint8_t* out_chunks,
    float scale, int zero_point
);

// N series (USB mic): per-feature normalization mode.
// 0 = standard per-bin mean/std over all frames (default)
// 1 = voiced-only: identify voiced frames by total energy percentile threshold,
//     compute mean/std using only voiced frames (silence noise excluded)
// 2 = mel floor clamp before normalization (clamp log-mel below floor_log)
// 3 = mel-domain noise subtract: subtract per-bin 5th percentile before normalization
void conformer_mel_set_norm_mode(int mode, float voiced_pct, float floor_log);

#endif // CONFORMER_MEL_H
