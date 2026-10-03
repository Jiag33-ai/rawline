#include <jni.h>
#include <libraw/libraw.h>

extern "C" JNIEXPORT jstring JNICALL
Java_app_rawline_core_nativelib_Native_librawVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(LibRaw::version());
}
