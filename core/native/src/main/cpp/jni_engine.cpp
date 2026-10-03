#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include "engine/base_curve.h"
#include "engine/engine.h"
#include "engine/halfs.h"
#include "engine/params.h"
#include "raw_decode.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "rawline", __VA_ARGS__)

using rl::Engine;

namespace {
struct ParamsRef {
    JNIEnv *env; jfloatArray arr; jfloat *p;
    ParamsRef(JNIEnv *e, jfloatArray a) : env(e), arr(a), p(e->GetFloatArrayElements(a, nullptr)) {}
    ~ParamsRef() { env->ReleaseFloatArrayElements(arr, p, JNI_ABORT); }
};
}  // namespace

extern "C" {

// ---- Raw decode (any thread) ----

JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_Native_decodeRaw(JNIEnv *env, jobject, jint fd, jboolean half) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    auto *img = new RawImage();
    std::string err;
    if (!decodeRaw(path, half, *img, err)) {
        LOGE("decodeRaw failed: %s", err.c_str());
        delete img;
        return 0;
    }
    return reinterpret_cast<jlong>(img);
}

JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_Native_rawFromRgba(JNIEnv *env, jobject, jbyteArray px, jint w, jint h) {
    auto *img = new RawImage();
    jbyte *p = env->GetByteArrayElements(px, nullptr);
    rawFromSrgb8(reinterpret_cast<uint8_t *>(p), w, h, *img);
    env->ReleaseByteArrayElements(px, p, JNI_ABORT);
    return reinterpret_cast<jlong>(img);
}

JNIEXPORT jintArray JNICALL Java_app_rawline_core_nativelib_Native_rawInfo(JNIEnv *env, jobject, jlong h) {
    auto *img = reinterpret_cast<RawImage *>(h);
    jint v[3] = {img->width, img->height, img->orientation};
    jintArray a = env->NewIntArray(3);
    env->SetIntArrayRegion(a, 0, 3, v);
    return a;
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_freeRaw(JNIEnv *, jobject, jlong h) {
    delete reinterpret_cast<RawImage *>(h);
}

// ---- GL engine (GL thread only) ----

JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_Native_engineCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT jstring JNICALL Java_app_rawline_core_nativelib_Native_engineInit(JNIEnv *env, jobject, jlong h) {
    std::string err;
    if (reinterpret_cast<Engine *>(h)->init(err)) return nullptr;
    LOGE("engine init failed: %s", err.c_str());
    return env->NewStringUTF(err.c_str());
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineDestroy(JNIEnv *, jobject, jlong h) {
    delete reinterpret_cast<Engine *>(h);
}

// Uploads the decoded raw to the GPU and frees the CPU copy.
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineSetSource(JNIEnv *, jobject, jlong h, jlong raw) {
    auto *img = reinterpret_cast<RawImage *>(raw);
    bool ok = reinterpret_cast<Engine *>(h)->setSource(img->width, img->height, img->half.data());
    delete img;
    return ok;
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetLayer(JNIEnv *env, jobject, jlong h, jint idx, jbyteArray data, jint w, jint hgt) {
    jbyte *p = env->GetByteArrayElements(data, nullptr);
    reinterpret_cast<Engine *>(h)->setLayer(idx, reinterpret_cast<uint8_t *>(p), w, hgt);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
}

// RGBA half float, premultiplied, linear working space. null clears.
JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetOverlay(JNIEnv *env, jobject, jlong h, jshortArray data, jint w, jint hgt) {
    if (!data) { reinterpret_cast<Engine *>(h)->setOverlay(nullptr, 0, 0); return; }
    jshort *p = env->GetShortArrayElements(data, nullptr);
    reinterpret_cast<Engine *>(h)->setOverlay(reinterpret_cast<uint8_t *>(p), w, hgt);
    env->ReleaseShortArrayElements(data, p, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineUpdateOverlay(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt, jshortArray data) {
    jshort *p = env->GetShortArrayElements(data, nullptr);
    reinterpret_cast<Engine *>(h)->updateOverlayRegion(x, y, w, hgt, reinterpret_cast<uint8_t *>(p));
    env->ReleaseShortArrayElements(data, p, JNI_ABORT);
}

JNIEXPORT jfloatArray JNICALL Java_app_rawline_core_nativelib_Native_baseCurve(JNIEnv *env, jobject) {
    jfloatArray a = env->NewFloatArray(256);
    env->SetFloatArrayRegion(a, 0, 256, kBaseCurve);
    return a;
}

// Linear working space values (rgb floats, 3 per pixel) of a rectangle of a decoded raw.
JNIEXPORT jfloatArray JNICALL Java_app_rawline_core_nativelib_Native_rawRead(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt) {
    auto *img = reinterpret_cast<RawImage *>(h);
    std::vector<float> out(size_t(w) * hgt * 3);
    for (int j = 0; j < hgt; j++)
        for (int i = 0; i < w; i++) {
            int sx = std::min(std::max(x + i, 0), img->width - 1), sy = std::min(std::max(y + j, 0), img->height - 1);
            const uint16_t *s = &img->half[(size_t(sy) * img->width + sx) * 4];
            float *o = &out[(size_t(j) * w + i) * 3];
            o[0] = rl::halfToFloat(s[0]); o[1] = rl::halfToFloat(s[1]); o[2] = rl::halfToFloat(s[2]);
        }
    jfloatArray a = env->NewFloatArray(int(out.size()));
    env->SetFloatArrayRegion(a, 0, int(out.size()), out.data());
    return a;
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_rawWrite(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt, jfloatArray data) {
    auto *img = reinterpret_cast<RawImage *>(h);
    jfloat *p = env->GetFloatArrayElements(data, nullptr);
    for (int j = 0; j < hgt; j++)
        for (int i = 0; i < w; i++) {
            int dx = x + i, dy = y + j;
            if (dx < 0 || dy < 0 || dx >= img->width || dy >= img->height) continue;
            uint16_t *d = &img->half[(size_t(dy) * img->width + dx) * 4];
            const float *s = &p[(size_t(j) * w + i) * 3];
            d[0] = rl::floatToHalf(s[0]); d[1] = rl::floatToHalf(s[1]); d[2] = rl::floatToHalf(s[2]);
        }
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
}

JNIEXPORT jintArray JNICALL Java_app_rawline_core_nativelib_Native_engineOutputSize(JNIEnv *env, jobject, jlong h, jfloatArray params) {
    ParamsRef pr(env, params);
    int w, hh;
    reinterpret_cast<Engine *>(h)->outputSize(pr.p, w, hh);
    jint v[2] = {w, hh};
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, v);
    return a;
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineRender(
    JNIEnv *env, jobject, jlong h, jfloatArray params, jint vx, jint vy, jint vw, jint vh, jfloat x, jfloat y, jfloat w, jfloat hgt) {
    ParamsRef pr(env, params);
    reinterpret_cast<Engine *>(h)->renderToScreen(pr.p, vx, vy, vw, vh, {x, y, w, hgt});
}

// Renders a region into a Java byte array (RGBA8, top row first). Used for tiles, histogram and thumbnails.
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineRenderRegion(
    JNIEnv *env, jobject, jlong h, jfloatArray params, jint pw, jint ph, jfloat x, jfloat y, jfloat w, jfloat hgt, jbyteArray out) {
    ParamsRef pr(env, params);
    jbyte *o = env->GetByteArrayElements(out, nullptr);
    bool ok = reinterpret_cast<Engine *>(h)->renderRegion(pr.p, pw, ph, {x, y, w, hgt}, reinterpret_cast<uint8_t *>(o));
    env->ReleaseByteArrayElements(out, o, 0);
    return ok;
}

// 16 bit linear sRGB half floats (for TIFF export): out has pw*ph*4 shorts.
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineRenderRegionHalf(
    JNIEnv *env, jobject, jlong h, jfloatArray params, jint pw, jint ph, jfloat x, jfloat y, jfloat w, jfloat hgt, jshortArray out) {
    ParamsRef pr(env, params);
    jshort *o = env->GetShortArrayElements(out, nullptr);
    bool ok = reinterpret_cast<Engine *>(h)->renderRegion(pr.p, pw, ph, {x, y, w, hgt}, nullptr, true, reinterpret_cast<uint16_t *>(o));
    env->ReleaseShortArrayElements(out, o, 0);
    return ok;
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetOutputSpace(JNIEnv *, jobject, jlong h, jint space) {
    reinterpret_cast<Engine *>(h)->setOutputSpace(space);
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineInvalidate(JNIEnv *, jobject, jlong h) {
    reinterpret_cast<Engine *>(h)->invalidateAnalysis();
}

JNIEXPORT jint JNICALL Java_app_rawline_core_nativelib_Native_paramFloats(JNIEnv *, jobject) { return rl::kParamFloats; }

}
