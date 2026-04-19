#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <awnn_lib.h>
#undef LOG_TAG
#include "../common/Utils.h"
#include "ko_citrinet_postprocess.h"
#include "awnn_internal.h"  // full Awnn_Context_t + vip_lite.h → vip_get_buffer_size
#include <jni.h>

// Korean Citrinet model config — matches bundle_app mode7 exactly
#define AUDIO_SAMPLE_RATE  16000
#define MEL_BINS           80
#define TIME_FRAMES        300   // 300f model (same as android_stt_bundle_app)
#define N_FFT              512
#define WIN_LENGTH         400   // 0.025s * 16000
#define HOP_LENGTH         160   // 0.010s * 16000
#define N_FREQ             (N_FFT / 2 + 1)  // 257
#define PAD_TO             16

// mode7 parameters (matches WavFrontend.kt mode7)
#define PRE_EMPH           0.97f
#define CENTER_PAD         (N_FFT / 2)   // 256 samples reflect pad each side
#define LOG_EPS            5.9604645e-8f // 2^-24
#define LOG_EPS_D          5.9604645e-8  // double version

// Quantization (from nbg_meta.json)
#define INPUT_SCALE       0.02096451073884964f
#define INPUT_ZERO_POINT  (-37)

typedef struct {
    Awnn_Context_t *context;
    char vocab_path[512];
} AwKoCitrinet;

// ---------------------------------------------------------------------------
// Slaney mel scale helpers — double precision, matches Kotlin DoubleArray
// ---------------------------------------------------------------------------
static double hz_to_mel_slaney_d(double hz) {
    const double f_sp       = 200.0 / 3.0;
    const double min_log_hz = 1000.0;
    const double min_log_mel = min_log_hz / f_sp;
    const double logstep    = log(6.4) / 27.0;
    if (hz < min_log_hz)
        return hz / f_sp;
    else
        return min_log_mel + log(hz / min_log_hz) / logstep;
}

static double mel_to_hz_slaney_d(double mel) {
    const double f_sp       = 200.0 / 3.0;
    const double min_log_hz = 1000.0;
    const double min_log_mel = min_log_hz / f_sp;
    const double logstep    = log(6.4) / 27.0;
    if (mel < min_log_mel)
        return f_sp * mel;
    else
        return min_log_hz * exp(logstep * (mel - min_log_mel));
}

// ---------------------------------------------------------------------------
// Mel filterbank [MEL_BINS, N_FREQ]  — double precision, matches Kotlin exactly:
//   melPoints/hzPoints in Double, bins from floor(double), enorm from double diff
//   filter weights: (int-diff).toFloat() / (int-diff).toFloat() = Float32
// ---------------------------------------------------------------------------
static void build_mel_filterbank(float *filterbank) {
    double mel_min = hz_to_mel_slaney_d(0.0);
    double mel_max = hz_to_mel_slaney_d((double)(AUDIO_SAMPLE_RATE / 2));

    double mel_points[MEL_BINS + 2];
    for (int i = 0; i < MEL_BINS + 2; i++) {
        mel_points[i] = mel_min + (mel_max - mel_min) * i / (MEL_BINS + 1);
    }

    double hz_points[MEL_BINS + 2];
    for (int i = 0; i < MEL_BINS + 2; i++) {
        hz_points[i] = mel_to_hz_slaney_d(mel_points[i]);
    }

    // bins: floor((N_FFT+1) * hz / sr).coerceIn(0, N_FREQ-1) — same as Kotlin
    int bin_points[MEL_BINS + 2];
    for (int i = 0; i < MEL_BINS + 2; i++) {
        int b = (int)floor((double)(N_FFT + 1) * hz_points[i] / AUDIO_SAMPLE_RATE);
        if (b < 0) b = 0;
        if (b >= N_FREQ) b = N_FREQ - 1;
        bin_points[i] = b;
    }

    memset(filterbank, 0, sizeof(float) * MEL_BINS * N_FREQ);
    for (int m = 0; m < MEL_BINS; m++) {
        int fl = bin_points[m], fc = bin_points[m+1], fr = bin_points[m+2];
        // Slaney enorm: 2 / (hzPoints[m+2] - hzPoints[m]).toFloat() — Kotlin
        float enorm = 2.0f / (float)(hz_points[m+2] - hz_points[m]);
        // rising slope: (k - left).toFloat() / (center - left).toFloat()
        if (fc > fl) {
            for (int f = fl; f < fc; f++) {
                filterbank[m * N_FREQ + f] = enorm * (float)(f - fl) / (float)(fc - fl);
            }
        }
        // falling slope: (right - k).toFloat() / (right - center).toFloat()
        if (fr > fc) {
            for (int f = fc; f < fr; f++) {
                filterbank[m * N_FREQ + f] = enorm * (float)(fr - f) / (float)(fr - fc);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Double-precision in-place radix-2 FFT — matches Kotlin fftRadix2 (DoubleArray)
// Iterative Cooley-Tukey, N must be power-of-2
// ---------------------------------------------------------------------------
static void fft_double(double *re, double *im, int n) {
    // bit-reversal permutation
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            double t;
            t = re[i]; re[i] = re[j]; re[j] = t;
            t = im[i]; im[i] = im[j]; im[j] = t;
        }
    }
    // butterfly stages
    for (int len = 2; len <= n; len <<= 1) {
        double ang = -2.0 * M_PI / len;
        double wr0 = cos(ang), wi0 = sin(ang);
        for (int i = 0; i < n; i += len) {
            double wr = 1.0, wi = 0.0;
            int half = len >> 1;
            for (int j = 0; j < half; j++) {
                double ur = re[i+j],      ui = im[i+j];
                double vr = re[i+j+half] * wr - im[i+j+half] * wi;
                double vi = re[i+j+half] * wi + im[i+j+half] * wr;
                re[i+j]      = ur + vr;  im[i+j]      = ui + vi;
                re[i+j+half] = ur - vr;  im[i+j+half] = ui - vi;
                double new_wr = wr * wr0 - wi * wi0;
                wi = wr * wi0 + wi * wr0;
                wr = new_wr;
            }
        }
    }
}

// ---------------------------------------------------------------------------
// selectBestEnergyWindow — matches WavFrontend.kt selectBestEnergyWindow()
// ---------------------------------------------------------------------------
static float *select_best_energy_window(const float *audio, int length,
                                         int frame_count_target, int *out_len) {
    int window_samples = (frame_count_target - 1) * HOP_LENGTH + WIN_LENGTH;
    if (window_samples < WIN_LENGTH) window_samples = WIN_LENGTH;

    if (length <= window_samples) {
        float *out = (float *)malloc(length * sizeof(float));
        memcpy(out, audio, length * sizeof(float));
        *out_len = length;
        return out;
    }

    int best_start = 0;
    double best_energy = -1e30;
    int start = 0;
    while (start + window_samples <= length) {
        double e = 0.0;
        for (int i = start; i < start + window_samples; i++) {
            double v = audio[i];
            e += v * v;
        }
        if (e > best_energy) { best_energy = e; best_start = start; }
        start += HOP_LENGTH;
    }
    // check last possible window — mirrors Kotlin remainStart logic exactly
    int remain_start = length - window_samples;
    if (remain_start < 0) remain_start = 0;
    if (remain_start > best_start) {
        double e = 0.0;
        for (int i = remain_start; i < length; i++) {
            double v = audio[i];
            e += v * v;
        }
        if (e > best_energy) { best_start = remain_start; }
    }

    float *out = (float *)malloc(window_samples * sizeof(float));
    memcpy(out, audio + best_start, window_samples * sizeof(float));
    *out_len = window_samples;
    return out;
}

// ---------------------------------------------------------------------------
// Mel spectrogram — matches WavFrontend.kt mode7 exactly:
//   preEmphasis=0.97, centerPad=REFLECT(256), periodicHann,
//   Slaney mel+enorm, log10, normalizePerFeature, padTo16, crop300
// Returns [TIME_FRAMES * MEL_BINS] in [t*MEL_BINS+m] layout. Caller must free.
// ---------------------------------------------------------------------------
static float *compute_mel_spectrogram(const float *audio, int length) {
    // 1. selectBestEnergyWindow
    int sel_len;
    float *selected = select_best_energy_window(audio, length, TIME_FRAMES, &sel_len);

    // 2. preEmphasis(0.97)
    float *emph = (float *)malloc(sel_len * sizeof(float));
    emph[0] = selected[0];
    for (int i = 1; i < sel_len; i++)
        emph[i] = selected[i] - PRE_EMPH * selected[i - 1];
    free(selected);

    // 3. centerPad reflect (N_FFT/2 = 256 each side)
    int pad = CENTER_PAD;
    int pa_len = sel_len + 2 * pad;
    float *pa = (float *)malloc(pa_len * sizeof(float));
    // left: pa[pad-1-i] = emph[i+1]
    for (int i = 0; i < pad; i++) {
        int src = i + 1;
        if (src >= sel_len) src = sel_len - 1;
        pa[pad - 1 - i] = emph[src];
    }
    memcpy(pa + pad, emph, sel_len * sizeof(float));
    // right: pa[pad+sel_len+i] = emph[sel_len-2-i]
    for (int i = 0; i < pad; i++) {
        int src = sel_len - 2 - i;
        if (src < 0) src = 0;
        pa[pad + sel_len + i] = emph[src];
    }
    free(emph);

    // 4. STFT
    int n_frames = (pa_len > WIN_LENGTH) ? (pa_len - WIN_LENGTH) / HOP_LENGTH + 1 : 0;
    LOGD("KoCitrinet STFT: orig_len=%d sel_len=%d pa_len=%d n_frames=%d",
         length, sel_len, pa_len, n_frames);

    float *fb = (float *)malloc(sizeof(float) * MEL_BINS * N_FREQ);
    build_mel_filterbank(fb);

    // periodic Hann: float32 precision, stored as float — matches Kotlin FloatArray hann
    // Kotlin: val hann = FloatArray(WIN) { i -> (0.5 - 0.5*cos(...)).toFloat() }
    float *hann = (float *)malloc(WIN_LENGTH * sizeof(float));
    for (int i = 0; i < WIN_LENGTH; i++)
        hann[i] = (float)(0.5 - 0.5 * cos(2.0 * M_PI * i / WIN_LENGTH));

    // double-precision FFT buffers (matches Kotlin fftRadix2 DoubleArray)
    double *fft_re = (double *)malloc(N_FFT * sizeof(double));
    double *fft_im = (double *)malloc(N_FFT * sizeof(double));
    double *ps     = (double *)malloc(N_FREQ * sizeof(double));

    // mel_raw[m * n_frames + t] = [MEL_BINS][n_frames] channel-major
    float *mel_raw = (float *)calloc(MEL_BINS * n_frames, sizeof(float));

    for (int t = 0; t < n_frames; t++) {
        int s = t * HOP_LENGTH;
        for (int i = 0; i < N_FFT; i++) {
            // Kotlin: real[n] = (s * hann[n]).toDouble()
            // = float32 * float32, then cast to double
            float samp = (i < WIN_LENGTH && (s + i) < pa_len) ? pa[s + i] : 0.0f;
            fft_re[i] = (i < WIN_LENGTH) ? (double)(samp * hann[i]) : 0.0;
            fft_im[i] = 0.0;
        }
        fft_double(fft_re, fft_im, N_FFT);
        // Kotlin: output[t][f] = (real[f]*real[f] + imag[f]*imag[f]).toFloat()
        // power spectrum stored as float32 (toFloat() matches)
        for (int f = 0; f < N_FREQ; f++) {
            double re = fft_re[f], im = fft_im[f];
            ps[f] = (float)(re * re + im * im);  // truncate to float32 like .toFloat()
        }
        // 5. mel filterbank + log10 (mode7: logBase10=true, coerceAtLeast=max)
        // Bundle_app: sum=0.0f (Float), sum+=spec[k]*filter[k] (Float*Float=Float)
        // val safe = sum.coerceAtLeast(logEps).toDouble(); log10(safe).toFloat()
        for (int m = 0; m < MEL_BINS; m++) {
            float energy = 0.0f;  // float32 accumulation, matches Kotlin
            for (int f = 0; f < N_FREQ; f++) energy += fb[m * N_FREQ + f] * (float)ps[f];
            double safe = ((double)energy > LOG_EPS_D) ? (double)energy : LOG_EPS_D;
            mel_raw[m * n_frames + t] = (float)log10(safe);
        }
    }
    free(pa); free(fb); free(hann); free(ps); free(fft_re); free(fft_im);

    // 6. normalizePerFeature over all n_frames (stable two-pass, matches Kotlin)
    int norm_frames = (n_frames > 0) ? n_frames : 1;
    for (int m = 0; m < MEL_BINS; m++) {
        double sum = 0.0;
        for (int t = 0; t < norm_frames; t++)
            sum += (double)mel_raw[m * n_frames + t];
        double mean = sum / norm_frames;
        double var_sum = 0.0;
        for (int t = 0; t < norm_frames; t++) {
            double d = (double)mel_raw[m * n_frames + t] - mean;
            var_sum += d * d;
        }
        double std_d = sqrt(var_sum / norm_frames);
        if (std_d < 1e-5) std_d = 1e-5;
        // Kotlin: std = sqrt(variance).toFloat().coerceAtLeast(1e-5f)
        // row[i] = ((row[i] - mean) / std.toDouble()).toFloat()
        float fstd = (float)std_d;  // .toFloat() → float32 std
        for (int t = 0; t < norm_frames; t++) {
            double d = (double)mel_raw[m * n_frames + t] - mean;  // float→double minus double
            mel_raw[m * n_frames + t] = (float)(d / (double)fstd);  // double/double→float
        }
    }

    // 7. padFramesToMultiple(PAD_TO=16) → cropOrPad to TIME_FRAMES
    int padded_frames = ((n_frames + PAD_TO - 1) / PAD_TO) * PAD_TO;
    int copy_frames = (padded_frames < TIME_FRAMES) ? padded_frames : TIME_FRAMES;
    int src_copy    = (n_frames < copy_frames) ? n_frames : copy_frames;

    // Output: [TIME_FRAMES * MEL_BINS] in time-major [t*MEL_BINS+m] order
    float *spec = (float *)calloc(TIME_FRAMES * MEL_BINS, sizeof(float));
    for (int m = 0; m < MEL_BINS; m++)
        for (int t = 0; t < src_copy; t++)
            spec[t * MEL_BINS + m] = mel_raw[m * n_frames + t];
    free(mel_raw);

    LOGD("KoCitrinet mel done: n_frames=%d padded=%d copy=%d target=%d",
         n_frames, padded_frames, src_copy, TIME_FRAMES);
    return spec;
}

// ---------------------------------------------------------------------------
// JNI — class: com.t527.wav2vecdemo.citrinet.AwKoCitrinetJni
// ---------------------------------------------------------------------------

// nativeProcessRawInput: bypass mel, use raw int8 dat file directly
// nativeRunInt8: takes pre-computed int8 byte[] from Java (KoMelFrontend) and runs NPU
JNIEXPORT JNICALL jobject
Java_com_t527_wav2vecdemo_citrinet_AwKoCitrinetJni_nativeRunInt8(
    JNIEnv *env, jobject thiz, jlong ptr, jbyteArray int8Array) {
    AwKoCitrinet *ko = (AwKoCitrinet *)(long)ptr;
    Awnn_Context_t *context = ko->context;

    jsize len = (*env)->GetArrayLength(env, int8Array);

    // Query actual VIP input buffer size — may be page-aligned (e.g. 24576 for 24000 elements)
    // awnn_set_input_buffers copies vip_get_buffer_size() bytes from our pointer,
    // so we must provide at least that many bytes (zeroed beyond actual data).
    vip_uint32_t vip_buff_size = (context->input_count > 0 && context->input_buffers)
        ? vip_get_buffer_size(context->input_buffers[0]) : (vip_uint32_t)len;
    int alloc_size = (vip_buff_size > (vip_uint32_t)len) ? (int)vip_buff_size : (int)len;

    LOGD("nativeRunInt8: jni_len=%d vip_buff_size=%u alloc=%d", (int)len, vip_buff_size, alloc_size);

    int8_t *input_int8 = (int8_t *)calloc(alloc_size, 1);  // zeroed — safe for VIP DMA
    (*env)->GetByteArrayRegion(env, int8Array, 0, len, (jbyte *)input_int8);

    LOGD("nativeRunInt8: %d bytes, first5=[%d,%d,%d,%d,%d]",
         (int)len,
         (int)input_int8[0], (int)input_int8[1], (int)input_int8[2],
         (int)input_int8[3], (int)input_int8[4]);

    unsigned char *input_buffers[1] = { (unsigned char *)input_int8 };
    awnn_set_input_buffers(context, input_buffers);
    awnn_run(context);

    float **results = awnn_get_output_buffers(context);
    const float *output_float = results[0];

    // Dump all 38 argmax tokens to compare with bundle_app
    {
        char argmax_line[800];
        argmax_line[0] = '\0';
        int pos2 = 0;
        for (int t = 0; t < 38; t++) {
            int bc = 0; float bv = output_float[0 * 38 + t];
            for (int c = 1; c < 2049; c++) {
                float v = output_float[c * 38 + t];
                if (v > bv) { bv = v; bc = c; }
            }
            pos2 += snprintf(argmax_line + pos2, sizeof(argmax_line) - pos2,
                             "t%d:%d(%.3f) ", t, bc, bv);
        }
        LOGD("nativeRunInt8 argmax: %s", argmax_line);
        // Also log output[81*38+t] vs output[82*38+t] for t=0..4
        LOGD("nativeRunInt8 tok81 t0..4: %.4f %.4f %.4f %.4f %.4f",
             output_float[81*38+0], output_float[81*38+1], output_float[81*38+2],
             output_float[81*38+3], output_float[81*38+4]);
        LOGD("nativeRunInt8 tok82 t0..4: %.4f %.4f %.4f %.4f %.4f",
             output_float[82*38+0], output_float[82*38+1], output_float[82*38+2],
             output_float[82*38+3], output_float[82*38+4]);
    }

    char transcription[4096];
    transcription[0] = '\0';
    ko_citrinet_postprocess(output_float, ko->vocab_path, transcription);
    LOGD("nativeRunInt8 result: '%s'", transcription);

    free(input_int8);

    jclass cls = (*env)->FindClass(env, "com/t527/wav2vecdemo/wav2vec/Wav2VecResult");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;F)V");
    jstring jtrans = (*env)->NewStringUTF(env, transcription);
    jobject result = (*env)->NewObject(env, cls, ctor, jtrans, 1.0f);
    (*env)->DeleteLocalRef(env, jtrans);
    return result;
}

JNIEXPORT JNICALL jobject
Java_com_t527_wav2vecdemo_citrinet_AwKoCitrinetJni_nativeProcessRawInput(
    JNIEnv *env, jobject thiz, jlong ptr, jstring datPath) {
    AwKoCitrinet *ko = (AwKoCitrinet *)(long)ptr;
    Awnn_Context_t *context = ko->context;

    jboolean copy = 1;
    const char *path = (*env)->GetStringUTFChars(env, datPath, &copy);

    FILE *fp = fopen(path, "rb");
    if (!fp) {
        LOGE("nativeProcessRawInput: cannot open %s", path);
        (*env)->ReleaseStringUTFChars(env, datPath, path);
        return NULL;
    }

    int num_elements = MEL_BINS * TIME_FRAMES;  // 80 * 300 = 24000

    // Same VIP buffer size alignment fix as nativeRunInt8
    vip_uint32_t vip_buff_size = (context->input_count > 0 && context->input_buffers)
        ? vip_get_buffer_size(context->input_buffers[0]) : (vip_uint32_t)num_elements;
    int alloc_size = (vip_buff_size > (vip_uint32_t)num_elements) ? (int)vip_buff_size : num_elements;
    LOGD("nativeProcessRawInput: vip_buff_size=%u alloc=%d", vip_buff_size, alloc_size);

    int8_t *input_int8 = (int8_t *)calloc(alloc_size, 1);  // zeroed beyond data
    size_t read = fread(input_int8, 1, num_elements, fp);
    fclose(fp);
    LOGD("nativeProcessRawInput: read %zu bytes from %s", read, path);
    (*env)->ReleaseStringUTFChars(env, datPath, path);

    unsigned char *input_buffers[1] = { (unsigned char *)input_int8 };
    awnn_set_input_buffers(context, input_buffers);
    awnn_run(context);

    float **results = awnn_get_output_buffers(context);
    const float *output_float = results[0];


    char transcription[4096];
    transcription[0] = '\0';
    ko_citrinet_postprocess(output_float, ko->vocab_path, transcription);
    LOGD("nativeProcessRawInput result: '%s'", transcription);

    free(input_int8);

    jclass cls = (*env)->FindClass(env, "com/t527/wav2vecdemo/wav2vec/Wav2VecResult");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;F)V");
    jstring jtrans = (*env)->NewStringUTF(env, transcription);
    jobject result = (*env)->NewObject(env, cls, ctor, jtrans, 1.0f);
    (*env)->DeleteLocalRef(env, jtrans);
    return result;
}

JNIEXPORT JNICALL void
Java_com_t527_wav2vecdemo_citrinet_AwKoCitrinetJni_nativeInitNpu(
    JNIEnv *env, jclass thiz) {
    awnn_init();
    LOGD("KoCitrinet NPU init OK");
}

JNIEXPORT JNICALL void
Java_com_t527_wav2vecdemo_citrinet_AwKoCitrinetJni_nativeReleaseNpu(
    JNIEnv *env, jclass thiz) {
    awnn_uninit();
    LOGD("KoCitrinet NPU release OK");
}

// nativeNew(modelPath, vocabPath)
JNIEXPORT JNICALL jlong
Java_com_t527_wav2vecdemo_citrinet_AwKoCitrinetJni_nativeNew(
    JNIEnv *env, jobject thiz, jstring modelPath, jstring vocabPath) {
    jboolean copy = 1;
    const char *mpath = (*env)->GetStringUTFChars(env, modelPath, &copy);
    const char *vpath = (*env)->GetStringUTFChars(env, vocabPath, &copy);

    Awnn_Context_t *context = awnn_create(mpath);
    LOGD("KoCitrinet create context=%p model=%s", context, mpath);

    AwKoCitrinet *ptr = NULL;
    if (context) {
        ptr = (AwKoCitrinet *)malloc(sizeof(AwKoCitrinet));
        ptr->context = context;
        strncpy(ptr->vocab_path, vpath, sizeof(ptr->vocab_path) - 1);
        ptr->vocab_path[sizeof(ptr->vocab_path) - 1] = '\0';
    }

    (*env)->ReleaseStringUTFChars(env, modelPath, mpath);
    (*env)->ReleaseStringUTFChars(env, vocabPath, vpath);

    return (jlong)(long)ptr;
}

JNIEXPORT JNICALL void
Java_com_t527_wav2vecdemo_citrinet_AwKoCitrinetJni_nativeDelete(
    JNIEnv *env, jobject thiz, jlong ptr) {
    if (!ptr) return;
    AwKoCitrinet *ko = (AwKoCitrinet *)(long)ptr;
    if (ko->context) awnn_destroy(ko->context);
    free(ko);
}

JNIEXPORT JNICALL jobject
Java_com_t527_wav2vecdemo_citrinet_AwKoCitrinetJni_nativeProcess(
    JNIEnv *env, jobject thiz, jlong ptr, jfloatArray audioData, jint length,
    jint sampleRate) {
    AwKoCitrinet *ko = (AwKoCitrinet *)(long)ptr;
    Awnn_Context_t *context = ko->context;

    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    LOGD("=== KoCitrinet NPU Process start len=%d sr=%d ===", length, sampleRate);

    jfloat *audioPtr = (*env)->GetFloatArrayElements(env, audioData, NULL);
    float *mel_spec = compute_mel_spectrogram(audioPtr, (int)length);
    (*env)->ReleaseFloatArrayElements(env, audioData, audioPtr, 0);

    if (!mel_spec) {
        LOGE("KoCitrinet: mel failed");
        return NULL;
    }

    // Transpose [T, C] -> [C, T] and quantize int8
    int num_elements = MEL_BINS * TIME_FRAMES;  // 80 * 300 = 24000
    int8_t *input_int8 = (int8_t *)malloc(num_elements);
    if (!input_int8) { free(mel_spec); return NULL; }

    for (int c = 0; c < MEL_BINS; c++) {
        for (int t = 0; t < TIME_FRAMES; t++) {
            float val = mel_spec[t * MEL_BINS + c];
            int32_t q = (int32_t)(roundf(val / INPUT_SCALE)) + INPUT_ZERO_POINT;
            if (q < -128) q = -128;
            if (q >  127) q =  127;
            input_int8[c * TIME_FRAMES + t] = (int8_t)q;
        }
    }
    free(mel_spec);

    // Debug: log input statistics + cross-channel samples
    {
        int8_t mn = input_int8[0], mx = input_int8[0];
        for (int i = 1; i < num_elements; i++) {
            if (input_int8[i] < mn) mn = input_int8[i];
            if (input_int8[i] > mx) mx = input_int8[i];
        }
        LOGD("KoCitrinet input int8 min=%d max=%d first5=[%d,%d,%d,%d,%d]",
             (int)mn, (int)mx,
             (int)input_int8[0], (int)input_int8[1], (int)input_int8[2],
             (int)input_int8[3], (int)input_int8[4]);
        // Log t=100 for mel bins 0,20,40,60,79 to check cross-channel values
        LOGD("KoCitrinet t=100: ch0=%d ch20=%d ch40=%d ch60=%d ch79=%d",
             (int)input_int8[0*TIME_FRAMES+100],
             (int)input_int8[20*TIME_FRAMES+100],
             (int)input_int8[40*TIME_FRAMES+100],
             (int)input_int8[60*TIME_FRAMES+100],
             (int)input_int8[79*TIME_FRAMES+100]);
    }

    unsigned char *input_buffers[1] = { (unsigned char *)input_int8 };
    awnn_set_input_buffers(context, input_buffers);

    LOGD("KoCitrinet running NPU...");
    awnn_run(context);

    // Output: awnn_get_output_buffers returns float32 (dequantized internally)
    float **results = awnn_get_output_buffers(context);
    const float *output_float = results[0];

    // Debug: dump argmax for ALL 38 time steps
    {
        LOGD("KoCitrinet raw output[0..4]: %.4f %.4f %.4f %.4f %.4f",
             output_float[0], output_float[1], output_float[2], output_float[3], output_float[4]);
        char argmax_line[800];
        argmax_line[0] = '\0';
        int pos = 0;
        for (int t = 0; t < 38; t++) {
            int bc = 0; float bv = output_float[0 * 38 + t];
            for (int c = 1; c < 2049; c++) {
                float v = output_float[c * 38 + t];
                if (v > bv) { bv = v; bc = c; }
            }
            pos += snprintf(argmax_line + pos, sizeof(argmax_line) - pos,
                            "t%d:%d(%.2f) ", t, bc, bv);
        }
        LOGD("KoCitrinet all argmax: %s", argmax_line);
    }

    char transcription[4096];
    transcription[0] = '\0';
    float confidence = ko_citrinet_postprocess(output_float, ko->vocab_path, transcription);

    LOGD("KoCitrinet result='%s' confidence=%.3f", transcription, confidence);

    clock_gettime(CLOCK_MONOTONIC, &t1);
    long ms = (t1.tv_sec - t0.tv_sec)*1000 + (t1.tv_nsec - t0.tv_nsec)/1000000;
    LOGD("=== KoCitrinet done in %ld ms ===", ms);

    free(input_int8);

    jclass cls = (*env)->FindClass(env, "com/t527/wav2vecdemo/wav2vec/Wav2VecResult");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;F)V");
    jstring jtrans = (*env)->NewStringUTF(env, transcription);
    jobject result = (*env)->NewObject(env, cls, ctor, jtrans, confidence);
    (*env)->DeleteLocalRef(env, jtrans);
    return result;
}
