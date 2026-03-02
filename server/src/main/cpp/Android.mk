LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := scrcpy_v4l2
LOCAL_SRC_FILES := v4l2_capture_jni.cpp
LOCAL_LDLIBS := -llog
LOCAL_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti

include $(BUILD_SHARED_LIBRARY)
