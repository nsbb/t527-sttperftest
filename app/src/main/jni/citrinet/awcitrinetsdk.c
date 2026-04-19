#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "../awnn/awnn_internal.h"
#undef LOG_TAG
#include "../common/Utils.h"
#include "../kissfft/kiss_fft.h"
#include "citrinet_postprocess.h"
#include <awnn_lib.h>
#include <jni.h>

// Citrinet model configuration
#define AUDIO_SAMPLE_RATE 16000
#define MEL_BINS 80         // Mel-spectrogram height
#define TIME_FRAMES 300     // Time frames (3 seconds)
#define MODEL_OUTPUT_SEQ_LEN 38  // Output sequence length
#define MODEL_VOCAB_SIZE 1025    // Vocabulary size

typedef struct {
  Awnn_Context_t *context;
} AwCitrinet;

AwCitrinet *newAwCitrinet(JNIEnv *env, Awnn_Context_t *context) {
  AwCitrinet *ptr = (AwCitrinet *)malloc(sizeof(AwCitrinet));
  ptr->context = context;
  return ptr;
}

// STFT computation using KissFFT
// n_fft=320, hop_length=160, win_length=320, window='hamming'
float *compute_mel_spectrogram(float *audio, int length) {
  const int n_fft = 320;
  const int hop_length = 160;
  const int win_length = 320;
  const int n_freq = n_fft / 2 + 1; // 161 frequency bins

  // Calculate number of frames
  int n_frames = (length - win_length) / hop_length + 1;
  if (n_frames > TIME_FRAMES) {
    n_frames = TIME_FRAMES;
  }

  LOGD("STFT: audio_len=%d, n_frames=%d, n_freq=%d", length, n_frames, n_freq);

  // Allocate spectrogram buffer [TIME_FRAMES, MEL_BINS]
  float *spec = (float *)calloc(TIME_FRAMES * MEL_BINS, sizeof(float));

  // Hamming window
  float *window = (float *)malloc(win_length * sizeof(float));
  for (int i = 0; i < win_length; i++) {
    window[i] = 0.54f - 0.46f * cosf(2.0f * M_PI * i / (win_length - 1));
  }

  // Initialize KissFFT
  kiss_fft_cfg fft_cfg = kiss_fft_alloc(n_fft, 0, NULL, NULL);
  kiss_fft_cpx *fft_in = (kiss_fft_cpx *)malloc(n_fft * sizeof(kiss_fft_cpx));
  kiss_fft_cpx *fft_out = (kiss_fft_cpx *)malloc(n_fft * sizeof(kiss_fft_cpx));

  // Compute STFT for each frame
  for (int t = 0; t < n_frames; t++) {
    int start = t * hop_length;

    // Apply window and prepare FFT input
    for (int i = 0; i < n_fft; i++) {
      if (i < win_length && (start + i) < length) {
        fft_in[i].r = audio[start + i] * window[i];
      } else {
        fft_in[i].r = 0.0f;
      }
      fft_in[i].i = 0.0f;
    }

    // Perform FFT
    kiss_fft(fft_cfg, fft_in, fft_out);

    // Compute magnitude spectrogram and apply log1p
    // Store in [time, freq] layout
    for (int f = 0; f < n_freq && f < MEL_BINS; f++) {
      float real = fft_out[f].r;
      float imag = fft_out[f].i;
      float magnitude = sqrtf(real * real + imag * imag);
      spec[t * MEL_BINS + f] = log1pf(magnitude);
    }
  }

  // Cleanup
  free(window);
  free(fft_in);
  free(fft_out);
  kiss_fft_free(fft_cfg);

  LOGD("STFT computation complete");

  return spec;
}

JNIEXPORT JNICALL void
Java_com_t527_wav2vecdemo_citrinet_AwCitrinetJni_nativeInitNpu(
    JNIEnv *env, jclass thiz) {
  awnn_init();
  LOGD("Citrinet NPU Init succeed");
}

JNIEXPORT JNICALL void
Java_com_t527_wav2vecdemo_citrinet_AwCitrinetJni_nativeReleaseNpu(
    JNIEnv *env, jclass thiz) {
  awnn_uninit();
  LOGD("Citrinet NPU Release succeed");
}

JNIEXPORT JNICALL jlong
Java_com_t527_wav2vecdemo_citrinet_AwCitrinetJni_nativeNew(
    JNIEnv *env, jobject thiz, jstring modelPath) {
  jboolean copy = 1;
  const char *modelPathString =
      (*env)->GetStringUTFChars(env, modelPath, &copy);

  Awnn_Context_t *context = awnn_create(modelPathString);
  LOGD("Create Citrinet context = %p (%s)", context, modelPathString);

  (*env)->ReleaseStringUTFChars(env, modelPath, modelPathString);

  if (!context) {
    return 0;
  }

  return (long)newAwCitrinet(env, context);
}

JNIEXPORT JNICALL void
Java_com_t527_wav2vecdemo_citrinet_AwCitrinetJni_nativeDelete(
    JNIEnv *env, jobject thiz, jlong ptr) {
  if (!ptr) {
    return;
  }
  AwCitrinet *awcitrinet = (AwCitrinet *)ptr;

  Awnn_Context_t *context = (Awnn_Context_t *)(awcitrinet->context);

  if (context) {
    awnn_destroy(context);
  }

  free(awcitrinet);
}

JNIEXPORT JNICALL jobject
Java_com_t527_wav2vecdemo_citrinet_AwCitrinetJni_nativeProcess(
    JNIEnv *env, jobject thiz, jlong ptr, jfloatArray audioData, jint length,
    jint sampleRate) {
  AwCitrinet *awcitrinet = (AwCitrinet *)ptr;
  Awnn_Context_t *context = (Awnn_Context_t *)(awcitrinet->context);

  struct timespec start_time, end_time;
  clock_gettime(CLOCK_MONOTONIC, &start_time);

  LOGD("=== Citrinet NPU Processing Start ===");
  LOGD("Input: length=%d, sampleRate=%d", length, sampleRate);

  // Get audio data
  jfloat *audioPtr = (*env)->GetFloatArrayElements(env, audioData, NULL);

  // Compute Mel-spectrogram
  LOGD("Computing Mel-spectrogram...");
  float *mel_spec = compute_mel_spectrogram(audioPtr, length);

  (*env)->ReleaseFloatArrayElements(env, audioData, audioPtr, 0);

  // Quantization
  unsigned char *input_buffers[1] = {NULL};
  unsigned char *quantized_buffer = NULL;

  if (context->input_count > 0) {
    Awnn_params_t *in_param = &context->input_params[0];
    vip_buffer_create_params_t *vip_param = &in_param->vip_param;

    int num_elements = TIME_FRAMES * MEL_BINS;

    // Check if quantization is needed
    if ((vip_param->data_format == VIP_BUFFER_FORMAT_UINT8 ||
         vip_param->data_format == VIP_BUFFER_FORMAT_INT8) &&
        vip_param->quant_format == VIP_BUFFER_QUANTIZE_TF_ASYMM) {

      float scale = vip_param->quant_data.affine.scale;
      int32_t zero_point = vip_param->quant_data.affine.zeroPoint;

      LOGD("Quantizing input: scale=%f, zero_point=%d", scale, zero_point);

      quantized_buffer = (unsigned char *)malloc(num_elements);
      if (quantized_buffer) {
        for (int i = 0; i < num_elements; i++) {
          float val = mel_spec[i];
          int32_t q = (int32_t)(roundf(val / scale) + zero_point);

          // Clamp to range
          if (vip_param->data_format == VIP_BUFFER_FORMAT_UINT8) {
            if (q < 0) q = 0;
            if (q > 255) q = 255;
          } else {
            if (q < -128) q = -128;
            if (q > 127) q = 127;
          }
          quantized_buffer[i] = (unsigned char)q;
        }
        input_buffers[0] = quantized_buffer;
      } else {
        LOGE("Failed to allocate quantization buffer!");
        input_buffers[0] = (unsigned char *)mel_spec;
      }
    } else {
      LOGD("No quantization required");
      input_buffers[0] = (unsigned char *)mel_spec;
    }
  } else {
    input_buffers[0] = (unsigned char *)mel_spec;
  }

  // Set NPU input buffer
  awnn_set_input_buffers(context, input_buffers);

  // Run network on NPU
  LOGD("Running Citrinet model on NPU...");
  awnn_run(context);

  // Get output results
  float **results = awnn_get_output_buffers(context);

  char transcription[1024];
  transcription[0] = '\0';

  // Call postprocess function
  float confidence = citrinet_postprocess(results[0], transcription);

  LOGD("Transcription: '%s'", transcription);
  LOGD("Confidence: %.3f", confidence);

  clock_gettime(CLOCK_MONOTONIC, &end_time);
  long processing_time_ms = (end_time.tv_sec - start_time.tv_sec) * 1000 +
                            (end_time.tv_nsec - start_time.tv_nsec) / 1000000;

  LOGD("Processing time: %ld ms", processing_time_ms);
  LOGD("=== Citrinet NPU Processing Complete ===");

  free(mel_spec);
  if (quantized_buffer) {
    free(quantized_buffer);
  }

  // Create Java result object
  jclass classResult =
      (*env)->FindClass(env, "com/t527/wav2vecdemo/wav2vec/Wav2VecResult");
  jmethodID constructorResult =
      (*env)->GetMethodID(env, classResult, "<init>", "(Ljava/lang/String;F)V");

  jstring jTranscription = (*env)->NewStringUTF(env, transcription);
  jobject result = (*env)->NewObject(env, classResult, constructorResult,
                                     jTranscription, confidence);

  (*env)->DeleteLocalRef(env, jTranscription);

  return result;
}
