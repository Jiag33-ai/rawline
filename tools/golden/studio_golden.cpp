// Host harness for the Studio compositor on Mesa llvmpipe: reads a scene file, renders it with the real GLSL, writes straight RGBA8.
// usage: studio_golden <scene.txt> <out.rgba>
// scene.txt: `view vx vy zoom outW outH`, then one `layer file.rgba w h x y scale opacity mode` per layer, bottom to top.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "studio/studio_compositor.h"

int main(int argc, char **argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: studio_golden scene.txt out.rgba\n"); return 2; }
    auto getPD = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLDisplay d = getPD(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    EGLint maj, min;
    if (!eglInitialize(d, &maj, &min)) return 3;
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig cfg; EGLint n;
    eglChooseConfig(d, ca, &cfg, 1, &n);
    EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    EGLSurface s = eglCreatePbufferSurface(d, cfg, pa);
    EGLint cx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
    eglMakeCurrent(d, s, s, c);

    rl::studio::Compositor comp;
    std::string err;
    if (!comp.init(err)) { std::fprintf(stderr, "init failed:\n%s\n", err.c_str()); return 4; }

    FILE *f = std::fopen(argv[1], "r");
    if (!f) { std::fprintf(stderr, "no scene\n"); return 5; }
    float vx = 0, vy = 0, zoom = 1; int ow = 0, oh = 0;
    std::vector<rl::studio::LayerDraw> draws;
    char kind[16], path[1024];
    while (std::fscanf(f, "%15s", kind) == 1) {
        if (std::string(kind) == "view") { if (std::fscanf(f, "%f %f %f %d %d", &vx, &vy, &zoom, &ow, &oh) != 5) return 5; }
        else if (std::string(kind) == "layer") {
            int w, h, mode; float x, y, sc, op;
            if (std::fscanf(f, "%1023s %d %d %f %f %f %f %d", path, &w, &h, &x, &y, &sc, &op, &mode) != 8) return 5;
            std::vector<uint8_t> px(size_t(w) * h * 4);
            FILE *lf = std::fopen(path, "rb");
            if (!lf || std::fread(px.data(), 1, px.size(), lf) != px.size()) { std::fprintf(stderr, "bad layer file %s\n", path); return 5; }
            std::fclose(lf);
            int slot = int(draws.size());
            if (!comp.setLayerImage(slot, px.data(), w, h)) { std::fprintf(stderr, "upload failed\n"); return 6; }
            draws.push_back({slot, x, y, sc, op, mode});
        }
    }
    std::fclose(f);
    std::vector<uint8_t> out(size_t(ow) * oh * 4);
    if (!comp.render(draws, vx, vy, zoom, ow, oh, out.data())) { std::fprintf(stderr, "render failed\n"); return 7; }
    std::fprintf(stderr, "rendered %dx%d, %d layers, %.2f MB of textures\n", ow, oh, int(draws.size()), comp.textureBytes() / 1048576.0);
    FILE *o = std::fopen(argv[2], "wb");
    std::fwrite(out.data(), 1, out.size(), o);
    std::fclose(o);
    return 0;
}
