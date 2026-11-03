#pragma once
#include <android/log.h>
namespace Log { enum { ME = 0 }; }
#define DEBUG_LOG(cat, ...) ((void)0)
#define INFO_LOG(cat, ...) __android_log_print(ANDROID_LOG_INFO, "at3", __VA_ARGS__)
#define WARN_LOG(cat, ...) __android_log_print(ANDROID_LOG_WARN, "at3", __VA_ARGS__)
#define ERROR_LOG(cat, ...) __android_log_print(ANDROID_LOG_ERROR, "at3", __VA_ARGS__)
