
#include <android/log.h>
#include <pthread.h>
#include <sys/time.h>
#include <vip_lite.h>

// Android 로그 매크로 정의
#define LOG_TAG "AWNN"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

typedef struct Awnn_params {
  vip_buffer_create_params_t vip_param;
  /* awnn addition param */
  vip_char_t name[256];
  vip_uint32_t elements;
} Awnn_params_t;

typedef struct Awnn_Context {
  pthread_mutex_t mutex;
  /* VIP lite buffer objects. */
  vip_network network;
  vip_uint32_t input_count;
  vip_uint32_t output_count;
  vip_buffer *input_buffers;
  vip_buffer *output_buffers;
  Awnn_params_t *input_params;
  Awnn_params_t *output_params;
  vip_float_t **quantize_maps;
  unsigned char **user_input_buffers;
  float **user_output_buffers;
} Awnn_Context_t;

#define _CHECK_STATUS(stat, lbl)                                               \
  do {                                                                         \
    if (VIP_SUCCESS != stat) {                                                 \
      ALOGD("Error: %s, %s at %d\n", __FILE__, __FUNCTION__, __LINE__);        \
      goto lbl;                                                                \
    }                                                                          \
  } while (0)

#define TIME_SLOTS 10
#define TIME_SLOTS 10
static vip_uint64_t time_begin[TIME_SLOTS];
static vip_uint64_t time_end[TIME_SLOTS];
static vip_uint64_t GetTime() {
  struct timeval time;
  gettimeofday(&time, NULL);
  return (vip_uint64_t)(time.tv_usec + time.tv_sec * 1000000LL);
}

#define TimeBegin(id)                                                          \
  do {                                                                         \
    time_begin[id] = GetTime();                                                \
  } while (0)

#define TimeEnd(id, PREFIX, ...)                                               \
  do {                                                                         \
    time_end[id] = GetTime();                                                  \
    ALOGD(PREFIX "%.2f ms.\n", ##__VA_ARGS__,                                  \
          (float)(time_end[id] - time_begin[id]) / 1000);                      \
  } while (0)
