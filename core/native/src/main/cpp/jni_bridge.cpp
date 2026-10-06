#include <jni.h>
#include <libraw/libraw.h>

#include "rw2_preview.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_app_rawline_core_nativelib_Native_librawVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(LibRaw::version());
}

// Returns [offset, length, orientation] or null when no embedded JPEG is found.
JNIEXPORT jlongArray JNICALL
Java_app_rawline_core_nativelib_Native_findPreview(JNIEnv *env, jobject, jint fd) {
    PreviewInfo info;
    try { if (!findEmbeddedPreview(fd, info)) return nullptr; } catch (...) { return nullptr; }  // no C++ exception may cross JNI
    jlong v[3] = {info.offset, info.length, info.orientation};
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, v);
    return arr;
}

// pread() straight into a Java byte array.
JNIEXPORT jbyteArray JNICALL
Java_app_rawline_core_nativelib_Native_readBytes(JNIEnv *env, jobject, jint fd, jlong off, jint len) {
    if (len <= 0) return nullptr;
    jbyteArray arr = env->NewByteArray(len);
    if (!arr) return nullptr;
    jbyte *p = env->GetByteArrayElements(arr, nullptr);
    if (!p) return nullptr;
    int64_t got = -1;
    try { got = readFully(fd, off, reinterpret_cast<uint8_t *>(p), len); } catch (...) {}
    env->ReleaseByteArrayElements(arr, p, 0);
    return got == len ? arr : nullptr;
}

}
