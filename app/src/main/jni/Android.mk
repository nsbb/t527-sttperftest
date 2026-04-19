LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := awwav2vec
LOCAL_SRC_FILES := \
    wav2vec/awwav2vecsdk.c \
    wav2vec/wav2vec_postprocess.cpp \
    awnn/awnn_lib.c \
    awnn/awnn_quantize.c

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/common \
    $(LOCAL_PATH)/wav2vec \
    $(LOCAL_PATH)/awnn \
    $(LOCAL_PATH)/vip

LOCAL_CFLAGS := -O2 -fPIC -Wall
LOCAL_CPPFLAGS := -std=c++17

LOCAL_LDLIBS := -llog -landroid

# 기존 라이브러리들과 링크
LOCAL_SHARED_LIBRARIES := libawnn.viplite libVIPlite libVIPuser

include $(BUILD_SHARED_LIBRARY)

# 기존 라이브러리 참조들
include $(CLEAR_VARS)
LOCAL_MODULE := libawnn.viplite
LOCAL_SRC_FILES := ../jniLibs/$(TARGET_ARCH_ABI)/libawnn.viplite.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libVIPlite
LOCAL_SRC_FILES := ../jniLibs/$(TARGET_ARCH_ABI)/libVIPlite.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libVIPuser
LOCAL_SRC_FILES := ../jniLibs/$(TARGET_ARCH_ABI)/libVIPuser.so
include $(PREBUILT_SHARED_LIBRARY)


# DeepSpeech2 library
include $(CLEAR_VARS)
LOCAL_MODULE := awdeepspeech2
LOCAL_SRC_FILES := \
    deepspeech2/awdeepspeech2sdk.c \
    deepspeech2/deepspeech2_postprocess.cpp \
    kissfft/kiss_fft.c \
    awnn/awnn_lib.c \
    awnn/awnn_quantize.c

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/common \
    $(LOCAL_PATH)/deepspeech2 \
    $(LOCAL_PATH)/kissfft \
    $(LOCAL_PATH)/awnn \
    $(LOCAL_PATH)/vip

LOCAL_CFLAGS := -O2 -fPIC -Wall
LOCAL_CPPFLAGS := -std=c++17

LOCAL_LDLIBS := -llog -landroid -lm

LOCAL_SHARED_LIBRARIES := libawnn.viplite libVIPlite libVIPuser

include $(BUILD_SHARED_LIBRARY)


# Citrinet library
include $(CLEAR_VARS)
LOCAL_MODULE := awcitrinet
LOCAL_SRC_FILES := \
    citrinet/awcitrinetsdk.c \
    citrinet/citrinet_postprocess.cpp \
    kissfft/kiss_fft.c \
    awnn/awnn_lib.c \
    awnn/awnn_quantize.c

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/common \
    $(LOCAL_PATH)/citrinet \
    $(LOCAL_PATH)/kissfft \
    $(LOCAL_PATH)/awnn \
    $(LOCAL_PATH)/vip

LOCAL_CFLAGS := -O2 -fPIC -Wall
LOCAL_CPPFLAGS := -std=c++17

LOCAL_LDLIBS := -llog -landroid -lm

LOCAL_SHARED_LIBRARIES := libawnn.viplite libVIPlite libVIPuser

include $(BUILD_SHARED_LIBRARY)


# Conformer library (SungBeom Korean Conformer CTC uint8) + ALSA capture
include $(CLEAR_VARS)
LOCAL_MODULE := awconformer
LOCAL_SRC_FILES := \
    conformer/awconformersdk.c \
    conformer/conformer_mel.c \
    conformer/wakeword_mel.c \
    kissfft/kiss_fft.c \
    alsa/alsa_capture.c

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/common \
    $(LOCAL_PATH)/conformer \
    $(LOCAL_PATH)/kissfft \
    $(LOCAL_PATH)/awnn \
    $(LOCAL_PATH)/vip \
    $(LOCAL_PATH)/alsa

LOCAL_CFLAGS := -O2 -fPIC -Wall

LOCAL_LDLIBS := -llog -landroid -lm -ldl

LOCAL_SHARED_LIBRARIES := libawnn.viplite libVIPlite libVIPuser

include $(BUILD_SHARED_LIBRARY)


# libtinyalsa prebuilt (pulled from T527 device /system/lib64/libtinyalsa.so)
include $(CLEAR_VARS)
LOCAL_MODULE := libtinyalsa
LOCAL_SRC_FILES := ../jniLibs/$(TARGET_ARCH_ABI)/libtinyalsa.so
include $(PREBUILT_SHARED_LIBRARY)
