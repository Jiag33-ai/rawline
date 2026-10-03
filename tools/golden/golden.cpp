// Host-side harness: runs the real GLSL pipeline on Mesa llvmpipe and writes PPM images.
// usage: golden <raw> <out.ppm> <width> [half|full] [key=value ...]
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>

#include "engine/engine.h"
#include "engine/params.h"
#include "raw_decode.h"

using namespace rl;

static double now() { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage\n"); return 2; }
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

    Engine eng;
    std::string err;
    if (!eng.init(err)) { fprintf(stderr, "init failed:\n%s\n", err.c_str()); return 4; }

    bool half = !(argc > 4 && !strcmp(argv[4], "full"));
    RawImage img;
    double t0 = now();
    if (!decodeRaw(argv[1], half, img, err)) { fprintf(stderr, "decode: %s\n", err.c_str()); return 5; }
    double t1 = now();
    fprintf(stderr, "decode %s %dx%d in %.0f ms (orientation %d)\n", half ? "half" : "full", img.width, img.height, t1 - t0, img.orientation);
    if (!eng.setSource(img.width, img.height, img.half.data())) { fprintf(stderr, "setSource gl error\n"); return 6; }
    double t2 = now();
    fprintf(stderr, "upload %.0f ms\n", t2 - t1);

    std::vector<float> p(kParamFloats);
    initDefaultParams(p.data());
    p[G_SHOWMASK] = -1;
    static const char *rot[] = {"", "", "", "", ""};
    (void)rot;
    // orientation
    int ori = img.orientation;
    p[G_GEO + 3] = ori == 6 ? 1 : ori == 3 ? 2 : ori == 8 ? 3 : 0;
    std::map<std::string, int> blockSlots = {
        {"exposure", S_EXPOSURE}, {"contrast", S_CONTRAST}, {"highlights", S_HIGHLIGHTS}, {"shadows", S_SHADOWS},
        {"whites", S_WHITES}, {"blacks", S_BLACKS}, {"temp", S_TEMP}, {"tint", S_TINT}, {"vibrance", S_VIBRANCE},
        {"saturation", S_SATURATION}, {"texture", S_TEXTURE}, {"clarity", S_CLARITY}, {"dehaze", S_DEHAZE}};
    for (int i = 5; i < argc; i++) {
        std::string a = argv[i];
        size_t eq = a.find('=');
        if (eq == std::string::npos) continue;
        std::string k = a.substr(0, eq);
        float v = float(atof(a.c_str() + eq + 1));
        if (blockSlots.count(k)) p[kOffBlocks + blockSlots[k]] = v;
        else if (k == "sharpen") p[G_DETAIL] = v;
        else if (k == "nrl") p[G_NR] = v;
        else if (k == "vig") p[G_FX] = v;
        else if (k == "angle") p[G_GEO] = v;
        else if (k == "grain") p[G_FX2] = v;
        else if (k == "cropw") p[G_CROP + 2] = v;
        else if (k == "maskexp") {   // linear gradient mask 0 with exposure delta
            float *m = p.data() + kOffMasks;
            m[0] = 1; m[1] = 1; m[2] = 0; m[3] = 0;                 // header: 1 component, amount 1
            m[4] = 1; m[5] = 0; m[6] = 0; m[7] = 0;                 // comp: type linear, op add
            m[8] = 0.2f; m[9] = 0.5f; m[10] = 0.8f; m[11] = 0.5f;   // start / end
            p[G_NUM_MASKS] = 1;
            p[kOffBlocks + kBlockFloats + S_EXPOSURE] = v;
        } else if (k == "masklayer") {   // bitmap mask: disc near the top left of the full frame
            const int lw = 512, lh = 341;
            std::vector<uint8_t> a(lw * lh, 0);
            for (int y = 0; y < lh; y++) for (int x = 0; x < lw; x++) { float dx = x - 130.f, dy = y - 90.f; if (dx * dx + dy * dy < 80.f * 80.f) a[y * lw + x] = 255; }
            eng.setLayer(0, a.data(), lw, lh);
            float *m = p.data() + kOffMasks;
            m[0] = 1; m[1] = 1; m[2] = 0; m[3] = 0;
            m[4] = 3; m[5] = 0; m[6] = 0; m[7] = 0;   // type bitmap, layer 0
            p[G_NUM_MASKS] = 1;
            p[kOffBlocks + kBlockFloats + S_EXPOSURE] = v;
        } else if (k == "maskrad") {
            float *m = p.data() + kOffMasks;
            m[0] = 1; m[1] = 1; m[2] = 0; m[3] = 0;
            m[4] = 2; m[5] = 0; m[6] = 0; m[7] = 0;
            m[8] = 0.5f; m[9] = 0.5f; m[10] = 0.35f; m[11] = 0.35f;
            m[12] = 0; m[13] = 0.5f; m[14] = 0; m[15] = 0;
            p[G_NUM_MASKS] = 1;
            p[kOffBlocks + kBlockFloats + S_EXPOSURE] = v;
        }
    }
    int ow, oh;
    eng.outputSize(p.data(), ow, oh);
    int W = atoi(argv[3]);
    int H = int(double(W) * oh / ow + 0.5);
    std::vector<uint8_t> rgba(size_t(W) * H * 4);
    for (int rep = 0; rep < 3; rep++) {
        double a = now();
        if (!eng.renderRegion(p.data(), W, H, {0, 0, 1, 1}, rgba.data())) { fprintf(stderr, "render gl error\n"); return 7; }
        fprintf(stderr, "render %dx%d: %.1f ms (llvmpipe, CPU)\n", W, H, now() - a);
    }
    FILE *f = fopen(argv[2], "wb");
    fprintf(f, "P6\n%d %d\n255\n", W, H);
    for (size_t i = 0; i < size_t(W) * H; i++) fwrite(&rgba[i * 4], 1, 3, f);
    fclose(f);
    fprintf(stderr, "wrote %s\n", argv[2]);
    return 0;
}
