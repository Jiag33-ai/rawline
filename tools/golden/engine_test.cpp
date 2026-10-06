// Host tests for the engine's GL robustness (Mesa llvmpipe through EGL). No RAW file needed.
//   AE-006  a failed source upload keeps the previous source (no black frame, size unchanged)
//   AE-011  an engine whose context is gone can be dropped in a new context without leaving a GL error, and the next upload works
//   AE-034  stale GL errors never fail a good upload
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "engine/engine.h"
#include "engine/halfs.h"
#include "engine/params.h"

using namespace rl;

static int fails = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("FAIL " __VA_ARGS__); std::printf("\n"); fails++; } } while (0)

struct Ctx { EGLDisplay d; EGLSurface s; EGLContext c; };

static Ctx makeContext() {
    auto getPD = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    static EGLDisplay d = [&] {
        EGLDisplay dd = getPD(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
        EGLint maj, min;
        eglInitialize(dd, &maj, &min);
        eglBindAPI(EGL_OPENGL_ES_API);
        return dd;
    }();
    EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig cfg; EGLint n;
    eglChooseConfig(d, ca, &cfg, 1, &n);
    EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    EGLSurface s = eglCreatePbufferSurface(d, cfg, pa);
    EGLint cx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
    return {d, s, c};
}
static void use(const Ctx &c) { eglMakeCurrent(c.d, c.s, c.s, c.c); }
static void destroy(Ctx &c) { eglMakeCurrent(c.d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); eglDestroyContext(c.d, c.c); eglDestroySurface(c.d, c.s); }

static std::vector<uint16_t> picture(int w, int h, float k) {
    std::vector<uint16_t> p(size_t(w) * h * 4);
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        uint16_t *d = &p[(size_t(y) * w + x) * 4];
        d[0] = floatToHalf(k * x / w); d[1] = floatToHalf(k * y / h); d[2] = floatToHalf(0.2f * k); d[3] = floatToHalf(1.f);
    }
    return p;
}

static std::vector<float> params() { std::vector<float> p(kParamFloats); initDefaultParams(p.data()); return p; }

static std::vector<uint8_t> render(Engine &e) {
    auto p = params();
    std::vector<uint8_t> out(32 * 32 * 4);
    if (!e.renderRegion(p.data(), 32, 32, {0, 0, 1, 1}, out.data())) out.clear();
    return out;
}

static unsigned drain() { unsigned n = 0, e; while ((e = glGetError()) != GL_NO_ERROR && n < 16) n++; return n; }

int main() {
    Ctx a = makeContext();
    use(a);
    std::string err;

    // ---- AE-006: failed upload keeps the previous source ----
    {
        Engine e;
        CHECK(e.init(err), "init: %s", err.c_str());
        auto img = picture(64, 48, 0.5f);
        CHECK(e.setSource(64, 48, img.data()), "first upload");
        auto before = render(e);
        CHECK(!before.empty(), "render before");
        // a width over the driver's texture size limit: glTexStorage2D fails, the buffer is never read
        std::vector<uint16_t> tiny(16, 0);
        bool ok = e.setSource(1 << 20, 2, tiny.data());
        CHECK(!ok, "an impossible source size must report failure");
        CHECK(e.hasSource() && e.sourceW() == 64 && e.sourceH() == 48, "failed upload changed the source size to %dx%d", e.sourceW(), e.sourceH());
        auto after = render(e);
        CHECK(!after.empty() && after == before, "picture changed after a failed upload");
        CHECK(drain() == 0, "failed upload left a GL error behind");
        // a good upload of another size works afterwards and replaces the source
        auto img2 = picture(96, 40, 0.8f);
        CHECK(e.setSource(96, 40, img2.data()), "upload after a failed one");
        CHECK(e.sourceW() == 96 && e.sourceH() == 40, "size after replacing");
        auto third = render(e);
        CHECK(!third.empty() && third != before, "new source not used");
        // zero size or null data are refused without touching anything
        CHECK(!e.setSource(0, 10, img2.data()) && !e.setSource(10, 10, nullptr), "empty uploads must fail");
        CHECK(e.sourceW() == 96, "size after refused uploads");
    }

#ifndef OLD_ENGINE
    // ---- AE-010: histogram, picker and statistics renders must not resize the targets the screen frame uses ----
    {
        Engine e;
        CHECK(e.init(err), "init: %s", err.c_str());
        auto img = picture(512, 384, 0.5f);
        CHECK(e.setSource(512, 384, img.data()), "upload");
        auto p = params();
        std::vector<uint8_t> big(800 * 600 * 4), small(256 * 171 * 4), tiny(8 * 8 * 4);
        CHECK(e.renderRegion(p.data(), 800, 600, {0, 0, 1, 1}, big.data()), "screen sized render");
        CHECK(e.renderRegion(p.data(), 256, 171, {0, 0, 1, 1}, small.data()), "histogram sized render");
        CHECK(e.renderRegion(p.data(), 8, 8, {0.4f, 0.4f, 0.01f, 0.01f}, tiny.data()), "picker sized render");
        uint64_t settled = e.targetAllocations();
        for (int i = 0; i < 20; i++) {   // a slider drag: a screen frame, a histogram, a sample, over and over
            CHECK(e.renderRegion(p.data(), 800, 600, {0, 0, 1, 1}, big.data()), "screen frame");
            CHECK(e.renderRegion(p.data(), 256, 171, {0, 0, 1, 1}, small.data()), "histogram");
            CHECK(e.renderRegion(p.data(), 8, 8, {0.4f, 0.4f, 0.01f, 0.01f}, tiny.data()), "sample");
        }
        CHECK(e.targetAllocations() == settled, "steady state reallocated render targets %llu times", (unsigned long long)(e.targetAllocations() - settled));
        // the small render must still give the same picture as a render at that size through the big targets would (same pipeline)
        std::vector<uint8_t> again(256 * 171 * 4);
        e.renderRegion(p.data(), 256, 171, {0, 0, 1, 1}, again.data());
        CHECK(again == small, "small render is not repeatable");
        // the fixed capacity small targets (drawn through a smaller viewport) give exactly the picture the exact size targets give
        for (int w : {256, 100, 37}) {
            int h = w * 2 / 3;
            std::vector<uint8_t> a(size_t(w) * h * 4), b(size_t(w) * h * 4);
            e.setForceBigTargets(false); CHECK(e.renderRegion(p.data(), w, h, {0.1f, 0.2f, 0.7f, 0.6f}, a.data()), "small path %d", w);
            e.setForceBigTargets(true);  CHECK(e.renderRegion(p.data(), w, h, {0.1f, 0.2f, 0.7f, 0.6f}, b.data()), "big path %d", w);
            e.setForceBigTargets(false);
            CHECK(a == b, "small and exact size targets disagree at %dx%d", w, h);
        }
    }
#endif

    // ---- AE-034: a stale GL error must not fail a good upload ----
    {
        Engine e;
        CHECK(e.init(err), "init: %s", err.c_str());
        glDeleteProgram(123456);   // invalid name: leaves GL_INVALID_VALUE queued
        auto img = picture(32, 32, 0.4f);
        CHECK(e.setSource(32, 32, img.data()), "setSource must not report a stale error as its own failure");
        glDeleteProgram(123457);
        auto p = params();
        std::vector<uint8_t> out(32 * 32 * 4);
        CHECK(e.renderRegion(p.data(), 32, 32, {0, 0, 1, 1}, out.data()), "renderRegion must not report a stale error");
    }

    // ---- AE-011: the context is lost, a new one is made, the old engine is dropped there ----
    {
        auto *dead = new Engine();
        CHECK(dead->init(err), "init: %s", err.c_str());
        auto img = picture(64, 48, 0.5f);
        CHECK(dead->setSource(64, 48, img.data()), "upload in the first context");
        destroy(a);                       // context lost: every GL name is gone
        Ctx b = makeContext();
        use(b);
        drain();
#ifdef OLD_ENGINE
        delete dead;                      // what the editor used to do: release() ran GL calls with dead names
#else
        dead->abandon();                  // what it does now: no GL calls at all
        delete dead;
#endif
        unsigned leftover = drain();
        CHECK(leftover == 0, "dropping the dead engine left %u GL errors in the new context", leftover);
        Engine fresh;
        CHECK(fresh.init(err), "init in the new context: %s", err.c_str());
        auto img2 = picture(64, 48, 0.5f);
        CHECK(fresh.setSource(64, 48, img2.data()), "first upload after the restore failed");
        auto r = render(fresh);
        CHECK(!r.empty(), "render after the restore");
        destroy(b);
    }

    if (fails == 0) std::printf("ok   engine robustness: failed upload, stale errors, context restore\n");
    return fails ? 1 : 0;
}
