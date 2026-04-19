#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <math.h>
#include <time.h>

#include <awnn_lib.h>
#include <jni.h>
#include "wav2vec_postprocess.h"
#include "../common/Utils.h"

// wav2vec2 English model configuration (facebook/wav2vec2-base-960h)
#define AUDIO_SAMPLE_RATE 16000
#define MODEL_INPUT_LENGTH 320000   // Model input size: [1, 320000] (20 seconds)
#define MODEL_OUTPUT_SEQ_LEN 999    // Output sequence length: [1, 999, 32]
#define MODEL_VOCAB_SIZE 32         // Vocab size (English alphabet + special tokens)

typedef struct {
    Awnn_Context_t *context;
} AwWav2Vec;

AwWav2Vec *newAwWav2Vec(JNIEnv *env, Awnn_Context_t *context) {
    AwWav2Vec *ptr = (AwWav2Vec *) malloc(sizeof(AwWav2Vec));
    ptr->context = context;
    return ptr;
}

JNIEXPORT JNICALL
void Java_com_t527_wav2vecdemo_wav2vec_AwWav2VecJni_nativeInitNpu(JNIEnv *env, jclass thiz) {
    // NPU 초기화
    awnn_init();
    LOGD("wav2vec NPU Init succeed");
}

JNIEXPORT JNICALL
void Java_com_t527_wav2vecdemo_wav2vec_AwWav2VecJni_nativeReleaseNpu(JNIEnv *env, jclass thiz) {
    // NPU 해제
    awnn_uninit();
    LOGD("wav2vec NPU Release succeed");
}

JNIEXPORT JNICALL
jlong Java_com_t527_wav2vecdemo_wav2vec_AwWav2VecJni_nativeNew(JNIEnv *env, jobject thiz, jstring modelPath) {
    jboolean copy = 1;
    const char *modelPathString = (*env)->GetStringUTFChars(env, modelPath, &copy);

    // 네트워크 생성
    Awnn_Context_t *context = awnn_create(modelPathString);
    LOGD("Create wav2vec context = %p (%s)", context, modelPathString);

    (*env)->ReleaseStringUTFChars(env, modelPath, modelPathString);

    if (!context) {
        return 0;
    }

    return (long) newAwWav2Vec(env, context);
}

JNIEXPORT JNICALL
void Java_com_t527_wav2vecdemo_wav2vec_AwWav2VecJni_nativeDelete(JNIEnv *env, jobject thiz, jlong ptr) {
    if (!ptr) {
        return;
    }
    AwWav2Vec *awwav2vec = (AwWav2Vec *) ptr;

    Awnn_Context_t *context = (Awnn_Context_t *)(awwav2vec->context);

    if (context) {
        // 네트워크 해제
        awnn_destroy(context);
    }

    free(awwav2vec);
}

JNIEXPORT JNICALL
jobject Java_com_t527_wav2vecdemo_wav2vec_AwWav2VecJni_nativeProcess(JNIEnv *env, jobject thiz, 
                                                                   jlong ptr, jfloatArray audioData, 
                                                                   jint length, jint sampleRate) {
    AwWav2Vec *awwav2vec = (AwWav2Vec *) ptr;
    Awnn_Context_t *context = (Awnn_Context_t *)(awwav2vec->context);
    
    // 시간 측정 시작
    struct timespec start_time, end_time;
    clock_gettime(CLOCK_MONOTONIC, &start_time);
    
    LOGD("=== wav2vec2 NPU Processing Start ===");
    LOGD("Input: length=%d, sampleRate=%d", length, sampleRate);
    LOGD("NPU: true (Allwinner VIPLite NPU)");
    LOGD("Model: facebook/wav2vec2-base-960h (English)");

    // 오디오 데이터 복사
    jfloat *audioPtr = (*env)->GetFloatArrayElements(env, audioData, NULL);
    
    // Adjust audio to model input size
    float *processedAudio = (float *) malloc(MODEL_INPUT_LENGTH * sizeof(float));
    
    if (length >= MODEL_INPUT_LENGTH) {
        // If input is larger than model size, use first part only
        memcpy(processedAudio, audioPtr, MODEL_INPUT_LENGTH * sizeof(float));
        LOGD("Audio truncated: %d -> %d samples", length, MODEL_INPUT_LENGTH);
    } else {
        // If input is smaller than model size, pad with zeros
        memcpy(processedAudio, audioPtr, length * sizeof(float));
        memset(processedAudio + length, 0, (MODEL_INPUT_LENGTH - length) * sizeof(float));
        LOGD("Audio padded: %d -> %d samples", length, MODEL_INPUT_LENGTH);
    }
    
    (*env)->ReleaseFloatArrayElements(env, audioData, audioPtr, 0);

    // Audio statistics check
    float min_val = processedAudio[0], max_val = processedAudio[0];
    float sum = 0.0f, sum_abs = 0.0f;
    for (int i = 0; i < MODEL_INPUT_LENGTH; i++) {
        float val = processedAudio[i];
        if (val < min_val) min_val = val;
        if (val > max_val) max_val = val;
        sum += val;
        sum_abs += fabsf(val);
    }
    float mean = sum / MODEL_INPUT_LENGTH;
    float mean_abs = sum_abs / MODEL_INPUT_LENGTH;
    
    LOGD("Audio stats: min=%.6f, max=%.6f, mean=%.6f, mean_abs=%.6f", min_val, max_val, mean, mean_abs);
    
    // Normalize to [-1.0, 1.0] range
    for (int i = 0; i < MODEL_INPUT_LENGTH; i++) {
        if (processedAudio[i] > 1.0f) processedAudio[i] = 1.0f;
        if (processedAudio[i] < -1.0f) processedAudio[i] = -1.0f;
    }


    //인풋 int8로 변환하는거.
    // Quantize input to UINT8 for NPU
    // Input quantization parameters from model: scale=0.002851, zero_point=119
    unsigned char *quantized_input = (unsigned char *) malloc(MODEL_INPUT_LENGTH * sizeof(unsigned char));
    float input_scale = 0.002851f;
    int input_zero_point = 119;

    for (int i = 0; i < MODEL_INPUT_LENGTH; i++) {
        // Quantize: uint8 = clip(round(float / scale) + zero_point, 0, 255)
        int quantized = (int)(processedAudio[i] / input_scale + input_zero_point + 0.5f);
        if (quantized < 0) quantized = 0;
        if (quantized > 255) quantized = 255;
        quantized_input[i] = (unsigned char)quantized;
    }

    LOGD("Input quantized to UINT8 (scale=%.6f, zero_point=%d)", input_scale, input_zero_point);



    // Set NPU input buffer
    unsigned char *input_buffers[1] = {(unsigned char*)processedAudio};
    awnn_set_input_buffers(context, input_buffers);

    // Run network on NPU
    LOGD("Running wav2vec2 model on NPU...");
    awnn_run(context);

    // Get output results
    LOGD("Getting NPU output results");
    float **results = awnn_get_output_buffers(context);

    // Post-processing (CTC decoding)
    LOGD("Starting CTC decoding and post-processing");
    LOGD("Output tensor pointer: %p", results[0]);
    
    char transcription[1024];
    transcription[0] = '\0';
    
    // Call postprocess function
    float confidence = wav2vec_postprocess(results[0], transcription);
    
    LOGD("=== wav2vec_postprocess RETURNED ===");
    LOGD("Transcription: '%s'", transcription);
    LOGD("Confidence: %.3f", confidence);

    // End time measurement
    clock_gettime(CLOCK_MONOTONIC, &end_time);
    long processing_time_ms = (end_time.tv_sec - start_time.tv_sec) * 1000 + 
                             (end_time.tv_nsec - start_time.tv_nsec) / 1000000;
    
    LOGD("Processing time: %ld ms", processing_time_ms);
    LOGD("Confidence: %.3f (CTC decoding average probability)", confidence);
    LOGD("=== wav2vec2 NPU Processing Complete ===");

    free(processedAudio);

    // Java 결과 객체 생성
    jclass classWav2VecResult = (*env)->FindClass(env, "com/t527/wav2vecdemo/wav2vec/Wav2VecResult");
    jmethodID constructorWav2VecResult = (*env)->GetMethodID(env, classWav2VecResult, "<init>", "(Ljava/lang/String;F)V");
    
    jstring jTranscription = (*env)->NewStringUTF(env, transcription);
    jobject result = (*env)->NewObject(env, classWav2VecResult, constructorWav2VecResult, jTranscription, confidence);

    (*env)->DeleteLocalRef(env, jTranscription);

    return result;
}