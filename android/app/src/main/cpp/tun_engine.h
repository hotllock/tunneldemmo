#pragma once

#include <jni.h>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_nativeInit(
    JNIEnv *env, jclass clazz, jint tun_fd);

JNIEXPORT jboolean JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_nativeIsRunning(
    JNIEnv *env, jclass clazz);

JNIEXPORT void JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_nativeStop(
    JNIEnv *env, jclass clazz);

JNIEXPORT void JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_nativeSetProxyPort(
    JNIEnv *env, jclass clazz, jint port);

JNIEXPORT jint JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_nativeGetStats(
    JNIEnv *env, jclass clazz, jint stat_type);

JNIEXPORT jboolean JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_nativeProtectSocket(
    JNIEnv *env, jclass clazz, jint fd);

#ifdef __cplusplus
}
#endif
