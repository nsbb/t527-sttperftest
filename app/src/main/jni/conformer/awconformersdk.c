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
#include <jni.h>
#include "conformer_mel.h"

// SungBeom Conformer CTC Medium — T527 NPU uint8
// Input: [1, 80, 301] uint8 mel spectrogram
// Output: [1, 76, 2049] uint8 log softmax
#define MEL_BINS       80
#define TIME_FRAMES    301
#define SEQ_OUT        76
#define VOCAB_SIZE     2049
#define BLANK_ID       2048

// uint8 quantization params (from nbg_meta.json)
#define INPUT_SCALE    0.02418474107980728f
#define INPUT_ZP       67
#define OUTPUT_SCALE   0.2030104696750641f
#define OUTPUT_ZP      255

typedef struct {
    Awnn_Context_t *context;
} AwConformer;

// ---------------------------------------------------------------------------
// JNI — class: com.t527.wav2vecdemo.conformer.AwConformerJni
// ---------------------------------------------------------------------------

JNIEXPORT JNICALL
void Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeInitNpu(JNIEnv *env, jclass thiz) {
    awnn_init();
    LOGD("Conformer NPU Init OK");
}

JNIEXPORT JNICALL
void Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeReleaseNpu(JNIEnv *env, jclass thiz) {
    awnn_uninit();
    LOGD("Conformer NPU Release OK");
}

JNIEXPORT JNICALL
jlong Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeNew(
    JNIEnv *env, jobject thiz, jstring modelPath) {
    jboolean copy = 1;
    const char *model = (*env)->GetStringUTFChars(env, modelPath, &copy);

    AwConformer *ptr = (AwConformer *)malloc(sizeof(AwConformer));
    ptr->context = awnn_create((char *)model);
    LOGD("Conformer model loaded: %s, context=%p", model, ptr->context);

    (*env)->ReleaseStringUTFChars(env, modelPath, model);
    return (jlong)(long)ptr;
}

JNIEXPORT JNICALL
void Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeDelete(
    JNIEnv *env, jobject thiz, jlong nativePtr) {
    AwConformer *ptr = (AwConformer *)(long)nativePtr;
    if (ptr) {
        if (ptr->context) awnn_destroy(ptr->context);
        free(ptr);
    }
    LOGD("Conformer model destroyed");
}

// Run NPU with pre-computed uint8 mel bytes
// Returns int array: [argmax_0, argmax_1, ..., argmax_75]
JNIEXPORT JNICALL
jintArray Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeRunUint8(
    JNIEnv *env, jobject thiz, jlong nativePtr, jbyteArray uint8Mel) {
    AwConformer *ptr = (AwConformer *)(long)nativePtr;
    Awnn_Context_t *context = ptr->context;

    jsize len = (*env)->GetArrayLength(env, uint8Mel);
    LOGD("Conformer nativeRunUint8: %d bytes (expected %d)", (int)len, MEL_BINS * TIME_FRAMES);

    // Allocate input buffer
    unsigned char *input = (unsigned char *)calloc(MEL_BINS * TIME_FRAMES + 4096, 1);
    (*env)->GetByteArrayRegion(env, uint8Mel, 0, len, (jbyte *)input);

    // Run NPU
    struct timespec t1, t2;
    clock_gettime(CLOCK_MONOTONIC, &t1);

    unsigned char *input_buffers[1] = { input };
    awnn_set_input_buffers(context, input_buffers);
    awnn_run(context);

    clock_gettime(CLOCK_MONOTONIC, &t2);
    double ms = (t2.tv_sec - t1.tv_sec) * 1000.0 + (t2.tv_nsec - t1.tv_nsec) / 1e6;
    LOGD("Conformer NPU inference: %.1f ms", ms);

    // Get output
    float **results = awnn_get_output_buffers(context);
    const float *output = results[0];

    // Output layout from awnn: [VOCAB_SIZE, SEQ_OUT] or [SEQ_OUT, VOCAB_SIZE]
    // Need to determine — try argmax both ways and log
    // awnn output is typically dequantized float, layout depends on NB

    // CTC argmax for each time step
    jint argmax_ids[SEQ_OUT];
    int blank_count = 0;

    // Try layout [SEQ_OUT * VOCAB_SIZE] = time-major
    for (int t = 0; t < SEQ_OUT; t++) {
        int best_id = 0;
        float best_val = output[t * VOCAB_SIZE + 0];
        for (int v = 1; v < VOCAB_SIZE; v++) {
            float val = output[t * VOCAB_SIZE + v];
            if (val > best_val) {
                best_val = val;
                best_id = v;
            }
        }
        argmax_ids[t] = best_id;
        if (best_id == BLANK_ID) blank_count++;
    }

    LOGD("Conformer output (time-major): blank=%d/%d, first5=[%d,%d,%d,%d,%d]",
         blank_count, SEQ_OUT,
         argmax_ids[0], argmax_ids[1], argmax_ids[2], argmax_ids[3], argmax_ids[4]);

    // Also try vocab-major [VOCAB_SIZE * SEQ_OUT] for comparison
    int blank_count2 = 0;
    jint argmax_ids2[SEQ_OUT];
    for (int t = 0; t < SEQ_OUT; t++) {
        int best_id = 0;
        float best_val = output[0 * SEQ_OUT + t];
        for (int v = 1; v < VOCAB_SIZE; v++) {
            float val = output[v * SEQ_OUT + t];
            if (val > best_val) {
                best_val = val;
                best_id = v;
            }
        }
        argmax_ids2[t] = best_id;
        if (best_id == BLANK_ID) blank_count2++;
    }

    LOGD("Conformer output (vocab-major): blank=%d/%d, first5=[%d,%d,%d,%d,%d]",
         blank_count2, SEQ_OUT,
         argmax_ids2[0], argmax_ids2[1], argmax_ids2[2], argmax_ids2[3], argmax_ids2[4]);

    // awnn output is time-major [SEQ_OUT * VOCAB_SIZE] — matches vpm_run (blank=60~66)
    // vocab-major gives blank=1 which is incorrect
    jint *use_ids = argmax_ids;
    int use_blank = blank_count;
    LOGD("Conformer using time-major layout, blank=%d/%d", use_blank, SEQ_OUT);

    free(input);

    // Return argmax array to Java for BPE decoding
    jintArray result = (*env)->NewIntArray(env, SEQ_OUT);
    (*env)->SetIntArrayRegion(env, result, 0, SEQ_OUT, use_ids);
    return result;
}

// Load .dat file from path and run NPU (for testing with pre-computed mel)
JNIEXPORT JNICALL
jintArray Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeRunDatFile(
    JNIEnv *env, jobject thiz, jlong nativePtr, jstring datPath) {
    jboolean copy = 1;
    const char *path = (*env)->GetStringUTFChars(env, datPath, &copy);

    FILE *f = fopen(path, "rb");
    if (!f) {
        LOGD("Conformer: cannot open %s", path);
        (*env)->ReleaseStringUTFChars(env, datPath, path);
        return NULL;
    }

    fseek(f, 0, SEEK_END);
    long fsize = ftell(f);
    fseek(f, 0, SEEK_SET);

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)fsize);
    unsigned char *buf = (unsigned char *)malloc(fsize);
    fread(buf, 1, fsize, f);
    fclose(f);
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)fsize, (jbyte *)buf);
    free(buf);

    (*env)->ReleaseStringUTFChars(env, datPath, path);

    return Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeRunUint8(
        env, thiz, nativePtr, arr);
}

// Compute mel from 16kHz float audio → uint8 mel bytes
JNIEXPORT JNICALL
jbyteArray Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeComputeMel(
    JNIEnv *env, jobject thiz, jfloatArray audio16k, jint audioLen,
    jfloat scale, jint zeroPoint) {

    float *audio = (*env)->GetFloatArrayElements(env, audio16k, NULL);

    uint8_t out_mel[CONF_MEL_N_MELS * CONF_MEL_TIME_FRAMES];
    int n_frames = conformer_mel_compute(audio, (int)audioLen, out_mel, scale, zeroPoint);

    (*env)->ReleaseFloatArrayElements(env, audio16k, audio, 0);

    LOGD("nativeComputeMel: audio=%d samples, frames=%d", (int)audioLen, n_frames);

    jbyteArray result = (*env)->NewByteArray(env, CONF_MEL_N_MELS * CONF_MEL_TIME_FRAMES);
    (*env)->SetByteArrayRegion(env, result, 0, CONF_MEL_N_MELS * CONF_MEL_TIME_FRAMES, (jbyte *)out_mel);
    return result;
}

// One-shot: 16kHz float audio → NPU → argmax
JNIEXPORT JNICALL
jintArray Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeAudioToArgmax(
    JNIEnv *env, jobject thiz, jlong nativePtr, jfloatArray audio16k, jint audioLen,
    jfloat melScale, jint melZp) {

    float *audio = (*env)->GetFloatArrayElements(env, audio16k, NULL);

    uint8_t out_mel[CONF_MEL_N_MELS * CONF_MEL_TIME_FRAMES];
    conformer_mel_compute(audio, (int)audioLen, out_mel, melScale, melZp);

    (*env)->ReleaseFloatArrayElements(env, audio16k, audio, 0);

    // Wrap as byte array and run NPU
    jbyteArray melArr = (*env)->NewByteArray(env, CONF_MEL_N_MELS * CONF_MEL_TIME_FRAMES);
    (*env)->SetByteArrayRegion(env, melArr, 0, CONF_MEL_N_MELS * CONF_MEL_TIME_FRAMES, (jbyte *)out_mel);

    return Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeRunUint8(
        env, thiz, nativePtr, melArr);
}

// ===== Wakeword (BCResNet) =====
// Input: [1, 1, 40, 151] uint8
// Output: [1, 2] uint8 → softmax → wake probability

#include "wakeword_mel.h"

// Compute wakeword mel from 16kHz audio (C/KissFFT — fast)
JNIEXPORT JNICALL
jbyteArray Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeComputeWakewordMel(
    JNIEnv *env, jobject thiz, jfloatArray audio16k, jint audioLen,
    jfloat scale, jint zeroPoint) {
    float *audio = (*env)->GetFloatArrayElements(env, audio16k, NULL);
    uint8_t out[WK_MEL_N_MELS * WK_MEL_FRAMES];
    wakeword_mel_compute(audio, (int)audioLen, out, scale, zeroPoint);
    (*env)->ReleaseFloatArrayElements(env, audio16k, audio, 0);
    jbyteArray result = (*env)->NewByteArray(env, WK_MEL_N_MELS * WK_MEL_FRAMES);
    (*env)->SetByteArrayRegion(env, result, 0, WK_MEL_N_MELS * WK_MEL_FRAMES, (jbyte *)out);
    return result;
}

typedef struct {
    Awnn_Context_t *context;
} AwWakeword;

JNIEXPORT JNICALL
jlong Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeNewWakeword(
    JNIEnv *env, jobject thiz, jstring modelPath) {
    jboolean copy = 1;
    const char *model = (*env)->GetStringUTFChars(env, modelPath, &copy);
    AwWakeword *ptr = (AwWakeword *)malloc(sizeof(AwWakeword));
    ptr->context = awnn_create((char *)model);
    LOGD("Wakeword model loaded: %s, context=%p", model, ptr->context);
    (*env)->ReleaseStringUTFChars(env, modelPath, model);
    return (jlong)(long)ptr;
}

JNIEXPORT JNICALL
void Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeDeleteWakeword(
    JNIEnv *env, jobject thiz, jlong ptr) {
    AwWakeword *wk = (AwWakeword *)(long)ptr;
    if (wk) {
        if (wk->context) awnn_destroy(wk->context);
        free(wk);
    }
}

// Run wakeword: uint8 mel [1,1,40,151] → float[2] (non-wake, wake)
JNIEXPORT JNICALL
jfloatArray Java_com_t527_wav2vecdemo_conformer_AwConformerJni_nativeRunWakeword(
    JNIEnv *env, jobject thiz, jlong nativePtr, jbyteArray uint8Mel,
    jfloat outScale, jint outZp) {
    AwWakeword *wk = (AwWakeword *)(long)nativePtr;

    jsize len = (*env)->GetArrayLength(env, uint8Mel);
    unsigned char *input = (unsigned char *)calloc(len + 4096, 1);
    (*env)->GetByteArrayRegion(env, uint8Mel, 0, len, (jbyte *)input);

    unsigned char *input_buffers[1] = { input };
    awnn_set_input_buffers(wk->context, input_buffers);
    awnn_run(wk->context);

    float **results = awnn_get_output_buffers(wk->context);
    const float *output = results[0];

    // output is dequantized float [2]
    // softmax
    float max_val = output[0] > output[1] ? output[0] : output[1];
    float exp0 = expf(output[0] - max_val);
    float exp1 = expf(output[1] - max_val);
    float sum = exp0 + exp1;

    jfloat probs[2];
    probs[0] = exp0 / sum; // non-wake
    probs[1] = exp1 / sum; // wake

    LOGD("Wakeword: raw=[%.4f, %.4f] prob=[%.4f, %.4f]", output[0], output[1], probs[0], probs[1]);

    free(input);

    jfloatArray result = (*env)->NewFloatArray(env, 2);
    (*env)->SetFloatArrayRegion(env, result, 0, 2, probs);
    return result;
}
