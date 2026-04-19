/**
 * alsa_capture.c
 *
 * Direct ALSA PCM capture via libtinyalsa, bypassing AudioFlinger silencing policy.
 * Used when system services (com.hdclabs.wallpadserver, com.android.localtransport, etc.)
 * permanently hold UNPROCESSED mic capture and silence third-party AudioRecord sessions.
 *
 * Target device: T527 wallpad, card=0, device=0 (pcmC0D0c), world-accessible
 */

#include <stdlib.h>
#include <string.h>
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "AlsaCapture"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---- tinyalsa types (from AOSP tinyalsa/pcm.h) ---- */
#define PCM_IN         0x10000000  /* Input (capture) PCM */
#define PCM_FORMAT_S16_LE 0

struct pcm_config {
    unsigned int channels;
    unsigned int rate;
    unsigned int period_size;
    unsigned int period_count;
    int format;
    unsigned int start_threshold;
    unsigned int stop_threshold;
    unsigned int silence_threshold;
    unsigned int silence_size;
    int avail_min;
};

struct pcm;  /* opaque */

/* libtinyalsa symbols (dynamically resolved from libtinyalsa.so) */
struct pcm *(*fp_pcm_open)(unsigned int card, unsigned int device, unsigned int flags, const struct pcm_config *config) = NULL;
int (*fp_pcm_read)(struct pcm *pcm, void *data, unsigned int count) = NULL;
int (*fp_pcm_close)(struct pcm *pcm) = NULL;
int (*fp_pcm_is_ready)(const struct pcm *pcm) = NULL;
unsigned int (*fp_pcm_frames_to_bytes)(const struct pcm *pcm, unsigned int frames) = NULL;
const char *(*fp_pcm_get_error)(const struct pcm *pcm) = NULL;

static void *g_tinyalsa_handle = NULL;

/* ---- dynamic loading ---- */
#include <dlfcn.h>

static int load_tinyalsa(void) {
    if (g_tinyalsa_handle) return 0;  /* already loaded */
    g_tinyalsa_handle = dlopen("libtinyalsa.so", RTLD_NOW);
    if (!g_tinyalsa_handle) {
        LOGE("dlopen libtinyalsa.so failed: %s", dlerror());
        return -1;
    }
    fp_pcm_open  = dlsym(g_tinyalsa_handle, "pcm_open");
    fp_pcm_read  = dlsym(g_tinyalsa_handle, "pcm_read");
    fp_pcm_close = dlsym(g_tinyalsa_handle, "pcm_close");
    fp_pcm_is_ready = dlsym(g_tinyalsa_handle, "pcm_is_ready");
    fp_pcm_frames_to_bytes = dlsym(g_tinyalsa_handle, "pcm_frames_to_bytes");
    fp_pcm_get_error = dlsym(g_tinyalsa_handle, "pcm_get_error");
    if (!fp_pcm_open || !fp_pcm_read || !fp_pcm_close || !fp_pcm_is_ready) {
        LOGE("Failed to resolve tinyalsa symbols");
        dlclose(g_tinyalsa_handle);
        g_tinyalsa_handle = NULL;
        return -1;
    }
    LOGD("libtinyalsa loaded OK");
    return 0;
}

/* ---- JNI: com.t527.wav2vecdemo.AlsaCapture ---- */

/*
 * Open ALSA PCM capture device.
 * Returns pointer as jlong (handle), 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_t527_wav2vecdemo_AlsaCapture_nativeOpen(
    JNIEnv *env, jclass thiz,
    jint card, jint device,
    jint rate, jint channels,
    jint period_size, jint period_count)
{
    if (load_tinyalsa() != 0) return 0;

    struct pcm_config config = {
        .channels       = (unsigned int)channels,
        .rate           = (unsigned int)rate,
        .period_size    = (unsigned int)period_size,
        .period_count   = (unsigned int)period_count,
        .format         = PCM_FORMAT_S16_LE,
        .start_threshold = 0,
        .stop_threshold  = 0,
        .silence_threshold = 0,
        .silence_size    = 0,
        .avail_min       = 0,
    };

    struct pcm *pcm = fp_pcm_open((unsigned int)card, (unsigned int)device, PCM_IN, &config);
    if (!fp_pcm_is_ready(pcm)) {
        LOGE("pcm_open failed: %s", fp_pcm_get_error ? fp_pcm_get_error(pcm) : "unknown");
        fp_pcm_close(pcm);
        return 0;
    }
    LOGD("ALSA pcmC%dD%dc opened: %dHz %dch period=%d*%d",
         card, device, rate, channels, period_size, period_count);
    return (jlong)(uintptr_t)pcm;
}

/*
 * Read PCM samples into a short[] buffer.
 * Returns number of samples read, or negative on error.
 */
JNIEXPORT jint JNICALL
Java_com_t527_wav2vecdemo_AlsaCapture_nativeRead(
    JNIEnv *env, jclass thiz,
    jlong handle, jshortArray jbuf, jint offset, jint numSamples)
{
    struct pcm *pcm = (struct pcm *)(uintptr_t)handle;
    if (!pcm) return -1;

    jshort *buf = (*env)->GetShortArrayElements(env, jbuf, NULL);
    unsigned int bytes = (unsigned int)numSamples * 2;  /* S16_LE: 2 bytes/sample */
    int ret = fp_pcm_read(pcm, buf + offset, bytes);
    (*env)->ReleaseShortArrayElements(env, jbuf, buf, 0);

    if (ret != 0) {
        LOGE("pcm_read failed: %s", fp_pcm_get_error ? fp_pcm_get_error(pcm) : "err");
        return -1;
    }
    return numSamples;
}

/*
 * Close ALSA PCM capture device.
 */
JNIEXPORT void JNICALL
Java_com_t527_wav2vecdemo_AlsaCapture_nativeClose(
    JNIEnv *env, jclass thiz, jlong handle)
{
    struct pcm *pcm = (struct pcm *)(uintptr_t)handle;
    if (pcm) {
        fp_pcm_close(pcm);
        LOGD("ALSA PCM closed");
    }
}
