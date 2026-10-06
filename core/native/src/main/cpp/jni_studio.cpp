#include <jni.h>
#include <android/log.h>

#include <exception>
#include <new>
#include <vector>

#include "studio/studio_compositor.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "rawline-studio", __VA_ARGS__)

using rl::studio::Compositor;
using rl::studio::LayerDraw;

namespace {
template <class R, class F> R guarded(const char *what, R fallback, F &&f) {
    try { return f(); }
    catch (const std::exception &e) { LOGE("%s: %s", what, e.what()); }
    catch (...) { LOGE("%s: unknown exception", what); }
    return fallback;
}
template <class F> void guardedV(const char *what, F &&f) { guarded<int>(what, 0, [&] { f(); return 0; }); }

// True when the Java array holds at least w*h*4 bytes and the size is sane, so native code never reads or writes past it.
bool fits(JNIEnv *env, jarray a, jint w, jint h) {
    if (!a || w <= 0 || h <= 0 || w > 16384 || h > 16384) return false;
    return size_t(env->GetArrayLength(a)) >= size_t(w) * size_t(h) * 4;
}
struct Bytes {
    JNIEnv *env; jbyteArray a; jbyte *p; jint mode;
    Bytes(JNIEnv *e, jbyteArray arr, jint releaseMode) : env(e), a(arr), p(e->GetByteArrayElements(arr, nullptr)), mode(releaseMode) { if (!p) throw std::bad_alloc(); }
    ~Bytes() { if (p) env->ReleaseByteArrayElements(a, p, mode); }
};
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_StudioNative_create(JNIEnv *, jobject) {
    return guarded<jlong>("studioCreate", 0, [&]() -> jlong { return reinterpret_cast<jlong>(new Compositor()); });
}

/** null on success, else the GL error text. GL thread. */
JNIEXPORT jstring JNICALL Java_app_rawline_core_nativelib_StudioNative_init(JNIEnv *env, jobject, jlong h) {
    return guarded<jstring>("studioInit", nullptr, [&]() -> jstring {
        std::string err;
        if (reinterpret_cast<Compositor *>(h)->init(err)) return nullptr;
        return env->NewStringUTF(err.empty() ? "init failed" : err.c_str());
    });
}

/** Releases the GL objects (needs the context) and deletes the object. */
JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_StudioNative_destroy(JNIEnv *, jobject, jlong h) {
    guardedV("studioDestroy", [&] { delete reinterpret_cast<Compositor *>(h); });
}

/** The context is gone: delete the object without touching GL (the new context holds other objects with the same names). */
JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_StudioNative_abandon(JNIEnv *, jobject, jlong h) {
    guardedV("studioAbandon", [&] { auto *c = reinterpret_cast<Compositor *>(h); c->abandon(); delete c; });
}

JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_setLayerImage(JNIEnv *env, jobject, jlong h, jint slot, jbyteArray rgba, jint w, jint hgt) {
    return guarded<jboolean>("studioSetLayerImage", JNI_FALSE, [&]() -> jboolean {
        if (!fits(env, rgba, w, hgt)) return JNI_FALSE;
        Bytes b(env, rgba, JNI_ABORT);
        return reinterpret_cast<Compositor *>(h)->setLayerImage(slot, reinterpret_cast<const uint8_t *>(b.p), w, hgt);
    });
}

JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_updateLayerRegion(JNIEnv *env, jobject, jlong h, jint slot, jint x, jint y, jint w, jint hgt, jbyteArray rgba) {
    return guarded<jboolean>("studioUpdateLayerRegion", JNI_FALSE, [&]() -> jboolean {
        if (!fits(env, rgba, w, hgt)) return JNI_FALSE;
        Bytes b(env, rgba, JNI_ABORT);
        return reinterpret_cast<Compositor *>(h)->updateLayerRegion(slot, x, y, w, hgt, reinterpret_cast<const uint8_t *>(b.p));
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_StudioNative_removeLayer(JNIEnv *, jobject, jlong h, jint slot) {
    guardedV("studioRemoveLayer", [&] { reinterpret_cast<Compositor *>(h)->removeLayer(slot); });
}

/** layers: 6 floats per layer, bottom to top: slot, x, y, scale, opacity (0..1), mode. out: outW * outH * 4 bytes, straight RGBA8, row 0 top. */
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_render(JNIEnv *env, jobject, jlong h, jfloatArray layers, jfloat vx, jfloat vy, jfloat zoom, jint outW, jint outH, jbyteArray out) {
    return guarded<jboolean>("studioRender", JNI_FALSE, [&]() -> jboolean {
        if (!fits(env, out, outW, outH) || !layers) return JNI_FALSE;
        const jsize n = env->GetArrayLength(layers) / 6;
        std::vector<LayerDraw> draws(n);
        {
            jfloat *p = env->GetFloatArrayElements(layers, nullptr);
            if (!p) throw std::bad_alloc();
            for (jsize i = 0; i < n; i++) draws[i] = {int(p[i * 6]), p[i * 6 + 1], p[i * 6 + 2], p[i * 6 + 3], p[i * 6 + 4], int(p[i * 6 + 5])};
            env->ReleaseFloatArrayElements(layers, p, JNI_ABORT);
        }
        Bytes o(env, out, 0);
        return reinterpret_cast<Compositor *>(h)->render(draws, vx, vy, zoom, outW, outH, reinterpret_cast<uint8_t *>(o.p));
    });
}

/** Clears an R16F coverage buffer the size of the slot's layer and remembers the stroke style. */
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_beginStroke(JNIEnv *, jobject, jlong h, jint slot, jfloat r, jfloat g, jfloat b, jfloat opacity, jboolean erase, jfloat hardness, jfloat flow) {
    return guarded<jboolean>("studioBeginStroke", JNI_FALSE, [&]() -> jboolean { return reinterpret_cast<Compositor *>(h)->beginStroke(slot, r, g, b, opacity, erase, hardness, flow); });
}

/** xyr: count * 3 floats (x, y, radius in layer pixels). */
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_addStamps(JNIEnv *env, jobject, jlong h, jfloatArray xyr, jint count) {
    return guarded<jboolean>("studioAddStamps", JNI_FALSE, [&]() -> jboolean {
        if (!xyr || count <= 0 || size_t(env->GetArrayLength(xyr)) < size_t(count) * 3) return JNI_FALSE;
        jfloat *p = env->GetFloatArrayElements(xyr, nullptr);
        if (!p) throw std::bad_alloc();
        bool ok = reinterpret_cast<Compositor *>(h)->addStamps(p, count);
        env->ReleaseFloatArrayElements(xyr, p, JNI_ABORT);
        return ok;
    });
}

/** coverage: w * h floats, row 0 = the rectangle's top row. */
JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_readStroke(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt, jfloatArray coverage) {
    return guarded<jboolean>("studioReadStroke", JNI_FALSE, [&]() -> jboolean {
        if (!coverage || w <= 0 || hgt <= 0 || w > 16384 || hgt > 16384 || size_t(env->GetArrayLength(coverage)) < size_t(w) * size_t(hgt)) return JNI_FALSE;
        jfloat *p = env->GetFloatArrayElements(coverage, nullptr);
        if (!p) throw std::bad_alloc();
        bool ok = reinterpret_cast<Compositor *>(h)->readStroke(x, y, w, hgt, p);
        env->ReleaseFloatArrayElements(coverage, p, 0);
        return ok;
    });
}

JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_StudioNative_endStroke(JNIEnv *, jobject, jlong h) {
    guardedV("studioEndStroke", [&] { reinterpret_cast<Compositor *>(h)->endStroke(); });
}

JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_StudioNative_textureBytes(JNIEnv *, jobject, jlong h) {
    return guarded<jlong>("studioTextureBytes", 0, [&]() -> jlong { return reinterpret_cast<Compositor *>(h)->textureBytes(); });
}

}  // extern "C"
