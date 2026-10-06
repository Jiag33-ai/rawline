#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <exception>
#include <new>
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
    ParamsRef(JNIEnv *e, jfloatArray a) : env(e), arr(a), p(e->GetFloatArrayElements(a, nullptr)) { if (!p) throw std::bad_alloc(); }
    ~ParamsRef() { if (p) env->ReleaseFloatArrayElements(arr, p, JNI_ABORT); }
};

// True when the Java array holds at least `count` elements and the dimensions are sane, so native code never reads or writes past it.
bool fits(JNIEnv *env, jarray a, jint w, jint h, int perPixel) {
    if (!a || w <= 0 || h <= 0 || w > 65536 || h > 65536) return false;
    return size_t(env->GetArrayLength(a)) >= size_t(w) * size_t(h) * size_t(perPixel);
}

// No C++ exception may cross the JNI boundary (that aborts the process). Out-of-memory and the like become an error value.
template <class F> struct Defer { F f; ~Defer() { f(); } };
template <class F> Defer<F> defer(F f) { return Defer<F>{f}; }

template <class R, class F> R guarded(const char *what, R fallback, F &&f) {
    try { return f(); }
    catch (const std::exception &e) { LOGE("%s: %s", what, e.what()); }
    catch (...) { LOGE("%s: unknown exception", what); }
    return fallback;
}
template <class F> void guardedV(const char *what, F &&f) {
    try { f(); }
    catch (const std::exception &e) { LOGE("%s: %s", what, e.what()); }
    catch (...) { LOGE("%s: unknown exception", what); }
}
}  // namespace

extern "C" {

// ---- Raw decode (any thread) ----

JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_Native_decodeRaw(JNIEnv *env, jobject, jint fd, jboolean half) {
    return guarded<jlong>("decodeRaw", 0, [&]() -> jlong {
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
    });
}

JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_Native_rawFromRgba(JNIEnv *env, jobject, jbyteArray px, jint w, jint h) {
    return guarded<jlong>("rawFromRgba", 0, [&]() -> jlong {
        if (!fits(env, px, w, h, 4)) return 0;
        std::unique_ptr<RawImage> img;
        try {
            img = std::make_unique<RawImage>();
            jbyte *p = env->GetByteArrayElements(px, nullptr);
            if (!p) return 0;
            auto unpin = defer([&] { env->ReleaseByteArrayElements(px, p, JNI_ABORT); });
            rawFromSrgb8(reinterpret_cast<uint8_t *>(p), w, h, *img);
        } catch (...) { LOGE("rawFromRgba: out of memory"); return 0; }
        return reinterpret_cast<jlong>(img.release());
    });
}

// Converts a software ARGB_8888 bitmap straight from its pixels (no Java heap copy of the picture).
JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_Native_rawFromBitmap(JNIEnv *env, jobject, jobject bmp) {
    return guarded<jlong>("rawFromBitmap", 0, [&]() -> jlong {
        AndroidBitmapInfo info;
        if (!bmp || AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 || info.width > 65536 || info.height > 65536) return 0;
        void *pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bmp, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return 0;
        auto unlock = defer([&] { AndroidBitmap_unlockPixels(env, bmp); });
        auto img = std::make_unique<RawImage>();
        rawFromSrgb8(static_cast<const uint8_t *>(pixels), int(info.width), int(info.height), *img, info.stride);
        return reinterpret_cast<jlong>(img.release());
    });
}

JNIEXPORT jintArray JNICALL Java_app_rawline_core_nativelib_Native_rawInfo(JNIEnv *env, jobject, jlong h) {
    return guarded<jintArray>("rawInfo", nullptr, [&]() -> jintArray {
        auto *img = reinterpret_cast<RawImage *>(h);
        jint v[3] = {img->width, img->height, img->orientation};
        jintArray a = env->NewIntArray(3);
        env->SetIntArrayRegion(a, 0, 3, v);
        return a;
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_freeRaw(JNIEnv *, jobject, jlong h) {
    guardedV("freeRaw", [&]() {
        delete reinterpret_cast<RawImage *>(h);
    });
}

// ---- GL engine (GL thread only) ----

JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_Native_engineCreate(JNIEnv *, jobject) {
    return guarded<jlong>("engineCreate", 0, [&]() -> jlong {
        return reinterpret_cast<jlong>(new Engine());
    });
}

JNIEXPORT jstring JNICALL Java_app_rawline_core_nativelib_Native_engineInit(JNIEnv *env, jobject, jlong h) {
    return guarded<jstring>("engineInit", nullptr, [&]() -> jstring {
        std::string err;
        if (reinterpret_cast<Engine *>(h)->init(err)) return nullptr;
        LOGE("engine init failed: %s", err.c_str());
        return env->NewStringUTF(err.c_str());
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineDestroy(JNIEnv *, jobject, jlong h) {
    guardedV("engineDestroy", [&]() {
        delete reinterpret_cast<Engine *>(h);
    });
}

// For an engine whose GL context is already gone (a restored surface): frees the C++ object without any GL call.
JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineAbandon(JNIEnv *, jobject, jlong h) {
    guardedV("engineAbandon", [&]() {
        auto *e = reinterpret_cast<Engine *>(h);
        if (!e) return;
        e->abandon();
        delete e;
    });
}

// Uploads the decoded raw to the GPU and frees the CPU copy.
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineSetSource(JNIEnv *, jobject, jlong h, jlong raw) {
    return guarded<jboolean>("engineSetSource", JNI_FALSE, [&]() -> jboolean {
        auto *img = reinterpret_cast<RawImage *>(raw);
        bool ok = reinterpret_cast<Engine *>(h)->setSource(img->width, img->height, img->half.data());
        delete img;
        return ok;
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetLayer(JNIEnv *env, jobject, jlong h, jint idx, jbyteArray data, jint w, jint hgt) {
    guardedV("engineSetLayer", [&]() {
        if (!fits(env, data, w, hgt, 1)) return;
        jbyte *p = env->GetByteArrayElements(data, nullptr);
        if (!p) return;
        reinterpret_cast<Engine *>(h)->setLayer(idx, reinterpret_cast<uint8_t *>(p), w, hgt);
        env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    });
}

// RGBA half float, premultiplied, linear working space. null clears.
JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetOverlay(JNIEnv *env, jobject, jlong h, jshortArray data, jint w, jint hgt) {
    guardedV("engineSetOverlay", [&]() {
        if (!data) { reinterpret_cast<Engine *>(h)->setOverlay(nullptr, 0, 0); return; }
        if (!fits(env, data, w, hgt, 4)) return;
        jshort *p = env->GetShortArrayElements(data, nullptr);
        if (!p) return;
        reinterpret_cast<Engine *>(h)->setOverlay(reinterpret_cast<uint8_t *>(p), w, hgt);
        env->ReleaseShortArrayElements(data, p, JNI_ABORT);
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineUpdateOverlay(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt, jshortArray data) {
    guardedV("engineUpdateOverlay", [&]() {
        if (!fits(env, data, w, hgt, 4) || x < 0 || y < 0) return;
        jshort *p = env->GetShortArrayElements(data, nullptr);
        if (!p) return;
        reinterpret_cast<Engine *>(h)->updateOverlayRegion(x, y, w, hgt, reinterpret_cast<uint8_t *>(p));
        env->ReleaseShortArrayElements(data, p, JNI_ABORT);
    });
}

// Raw files get the camera-look base tone curve; finished pictures (JPEG, HEIC, PNG) must not, or they come out far too bright.
JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetBaseCurve(JNIEnv *, jobject, jlong h, jboolean enabled) {
    guardedV("engineSetBaseCurve", [&]() {
        if (enabled) { reinterpret_cast<Engine *>(h)->setBaseCurve(kBaseCurve); return; }
        float identity[256];
        for (int i = 0; i < 256; i++) identity[i] = i / 255.0f;
        reinterpret_cast<Engine *>(h)->setBaseCurve(identity);
    });
}

JNIEXPORT jfloatArray JNICALL Java_app_rawline_core_nativelib_Native_baseCurve(JNIEnv *env, jobject) {
    return guarded<jfloatArray>("baseCurve", nullptr, [&]() -> jfloatArray {
        jfloatArray a = env->NewFloatArray(256);
        env->SetFloatArrayRegion(a, 0, 256, kBaseCurve);
        return a;
    });
}

// Linear working space values (rgb floats, 3 per pixel) of a rectangle of a decoded raw.
JNIEXPORT jfloatArray JNICALL Java_app_rawline_core_nativelib_Native_rawRead(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt) {
    return guarded<jfloatArray>("rawRead", nullptr, [&]() -> jfloatArray {
        auto *img = reinterpret_cast<RawImage *>(h);
        if (w <= 0 || hgt <= 0 || w > 16384 || hgt > 16384) return env->NewFloatArray(0);
        std::vector<float> out;
        try { out.resize(size_t(w) * hgt * 3); } catch (...) { return env->NewFloatArray(0); }
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
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_rawWrite(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt, jfloatArray data) {
    guardedV("rawWrite", [&]() {
        auto *img = reinterpret_cast<RawImage *>(h);
        if (!fits(env, data, w, hgt, 3)) return;
        jfloat *p = env->GetFloatArrayElements(data, nullptr);
        if (!p) return;
        for (int j = 0; j < hgt; j++)
            for (int i = 0; i < w; i++) {
                int dx = x + i, dy = y + j;
                if (dx < 0 || dy < 0 || dx >= img->width || dy >= img->height) continue;
                uint16_t *d = &img->half[(size_t(dy) * img->width + dx) * 4];
                const float *s = &p[(size_t(j) * w + i) * 3];
                d[0] = rl::floatToHalf(s[0]); d[1] = rl::floatToHalf(s[1]); d[2] = rl::floatToHalf(s[2]);
            }
        env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
    });
}

JNIEXPORT jintArray JNICALL Java_app_rawline_core_nativelib_Native_engineOutputSize(JNIEnv *env, jobject, jlong h, jfloatArray params) {
    return guarded<jintArray>("engineOutputSize", nullptr, [&]() -> jintArray {
        ParamsRef pr(env, params);
        int w, hh;
        reinterpret_cast<Engine *>(h)->outputSize(pr.p, w, hh);
        jint v[2] = {w, hh};
        jintArray a = env->NewIntArray(2);
        env->SetIntArrayRegion(a, 0, 2, v);
        return a;
    });
}

JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineRender(
    JNIEnv *env, jobject, jlong h, jfloatArray params, jint vx, jint vy, jint vw, jint vh, jfloat x, jfloat y, jfloat w, jfloat hgt) {
    return guarded<jboolean>("engineRender", JNI_FALSE, [&]() -> jboolean {
        ParamsRef pr(env, params);
        return reinterpret_cast<Engine *>(h)->renderToScreen(pr.p, vx, vy, vw, vh, {x, y, w, hgt});
    });
}

// Renders a region into a Java byte array (RGBA8, top row first). Used for tiles, histogram and thumbnails.
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineRenderRegion(
    JNIEnv *env, jobject, jlong h, jfloatArray params, jint pw, jint ph, jfloat x, jfloat y, jfloat w, jfloat hgt, jbyteArray out) {
    return guarded<jboolean>("engineRenderRegion", JNI_FALSE, [&]() -> jboolean {
        if (!fits(env, out, pw, ph, 4)) return false;
        ParamsRef pr(env, params);
        jbyte *o = env->GetByteArrayElements(out, nullptr);
        if (!o) return JNI_FALSE;
        auto unpin = defer([&] { env->ReleaseByteArrayElements(out, o, 0); });
        return reinterpret_cast<Engine *>(h)->renderRegion(pr.p, pw, ph, {x, y, w, hgt}, reinterpret_cast<uint8_t *>(o));
    });
}

// 16 bit export: out has pw*ph*3 values (unsigned 16 bit, stored in shorts), display encoded in the chosen output space.
// The engine must be in high precision mode (engineSetHighPrecision) before the first render.
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineRenderRegion16(
    JNIEnv *env, jobject, jlong h, jfloatArray params, jint pw, jint ph, jfloat x, jfloat y, jfloat w, jfloat hgt, jshortArray out) {
    return guarded<jboolean>("engineRenderRegion16", JNI_FALSE, [&]() -> jboolean {
        if (!fits(env, out, pw, ph, 3)) return false;
        ParamsRef pr(env, params);
        jshort *o = env->GetShortArrayElements(out, nullptr);
        if (!o) return JNI_FALSE;
        auto unpin = defer([&] { env->ReleaseShortArrayElements(out, o, 0); });
        return reinterpret_cast<Engine *>(h)->renderRegion16(pr.p, pw, ph, {x, y, w, hgt}, reinterpret_cast<uint16_t *>(o));
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetHighPrecision(JNIEnv *, jobject, jlong h, jboolean on) {
    guardedV("engineSetHighPrecision", [&]() {
        reinterpret_cast<Engine *>(h)->setHighPrecision(on);
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetOutputSpace(JNIEnv *, jobject, jlong h, jint space) {
    guardedV("engineSetOutputSpace", [&]() {
        reinterpret_cast<Engine *>(h)->setOutputSpace(space);
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineInvalidate(JNIEnv *, jobject, jlong h) {
    guardedV("engineInvalidate", [&]() {
        reinterpret_cast<Engine *>(h)->invalidateAnalysis();
    });
}

JNIEXPORT jint JNICALL Java_app_rawline_core_nativelib_Native_paramFloats(JNIEnv *, jobject) { return rl::kParamFloats; }

}
