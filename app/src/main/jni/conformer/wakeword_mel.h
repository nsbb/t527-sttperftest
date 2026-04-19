#ifndef WAKEWORD_MEL_H
#define WAKEWORD_MEL_H

#include <stdint.h>

// BCResNet wakeword mel parameters (HTK scale)
#define WK_MEL_SR        16000
#define WK_MEL_N_FFT     512
#define WK_MEL_WIN_LEN   480
#define WK_MEL_HOP_LEN   160
#define WK_MEL_N_MELS    40
#define WK_MEL_FRAMES    151
#define WK_MEL_TARGET_LEN 24000  // 1.5s @ 16kHz

void wakeword_mel_init(void);
void wakeword_mel_cleanup(void);

// audio: float32 at 16kHz, len should be ~24000 (1.5s)
// out_mel: uint8[40*151] = 6040 bytes
int wakeword_mel_compute(const float* audio, int audio_len,
                         uint8_t* out_mel, float scale, int zero_point);

#endif
