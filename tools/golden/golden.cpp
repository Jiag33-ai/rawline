// Host-side harness: runs the real GLSL pipeline on Mesa llvmpipe and writes PPM images.
// usage: golden <raw> <out.ppm> <width> [half|full] [key=value ...]
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>

#include "engine/engine.h"
#include "engine/halfs.h"
#include "engine/params.h"
#include "raw_decode.h"

using namespace rl;

static double now() { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

// ---- Constrain-to-image solver. A C++ copy of Geo.fitCrop (core/render Geo.kt); tools/golden/fitcrop.expected pins both to the same numbers. ----
static const float kEdgeMargin = 0.0015f;
struct FitCtx {
    const float *p; float srcW, srcH;
    void map(float fx, float fy, float &u, float &v) const {
        int rot = int(p[G_GEO + 3] + 0.5f);
        bool odd = rot % 2 == 1;
        float dw = odd ? srcH : srcW, dh = odd ? srcW : srcH;
        float qx = (fx - 0.5f) * dw, qy = (fy - 0.5f) * dh;
        float nx = qx / dw, ny = qy / dh;
        qx *= 1.f + p[G_GEO2] * ny;
        qy *= 1.f + p[G_GEO2 + 1] * nx;
        float s = std::sin(p[G_GEO]), co = std::cos(p[G_GEO]);
        float rx = co * qx - s * qy, ry = s * qx + co * qy;
        if (p[G_LDIST_ON] > 0.5f) {
            float rn = std::sqrt(rx * rx + ry * ry) / (0.5f * std::min(dw, dh));
            float f = p[G_LDIST] + rn * (p[G_LDIST + 1] + rn * (p[G_LDIST + 2] + rn * (p[G_LDIST + 3] + rn * p[G_LDIST + 4])));
            rx *= f; ry *= f;
        }
        float r2 = (rx * rx + ry * ry) / (dh * dh * 0.25f + dw * dw * 0.25f);
        float k = 1.f + p[G_GEO2 + 2] * r2;
        float bx = rx * k / dw + 0.5f, by = ry * k / dh + 0.5f;
        if (p[G_GEO + 1] > 0.5f) bx = 1.f - bx;
        if (p[G_GEO + 2] > 0.5f) by = 1.f - by;
        if (rot == 1) { u = by; v = 1.f - bx; } else if (rot == 2) { u = 1.f - bx; v = 1.f - by; }
        else if (rot == 3) { u = 1.f - by; v = bx; } else { u = bx; v = by; }
    }
    bool valid(float x, float y) const {
        float u, v; map(x, y, u, v);
        return u >= kEdgeMargin && u <= 1.f - kEdgeMargin && v >= kEdgeMargin && v <= 1.f - kEdgeMargin;
    }
    bool rectValid(float x, float y, float w, float h) const {
        for (int i = 0; i <= 64; i++) {
            float t = i / 64.f;
            if (!valid(x + t * w, y) || !valid(x + t * w, y + h) || !valid(x, y + t * h) || !valid(x + w, y + t * h)) return false;
        }
        return true;
    }
};

static void fitCrop(float *p, float srcW, float srcH) {
    FitCtx ctx{p, srcW, srcH};
    float w = std::clamp(p[G_CROP + 2], 0.001f, 1.f), h = std::clamp(p[G_CROP + 3], 0.001f, 1.f), x = p[G_CROP], y = p[G_CROP + 1];
    bool trivial = p[G_GEO] == 0.f && p[G_GEO2] == 0.f && p[G_GEO2 + 1] == 0.f && p[G_GEO2 + 2] == 0.f && p[G_LDIST_ON] < 0.5f;
    if (trivial) { p[G_CROP] = std::clamp(x, 0.f, 1.f - w); p[G_CROP + 1] = std::clamp(y, 0.f, 1.f - h); return; }
    if (ctx.rectValid(x, y, w, h) || !ctx.valid(0.5f, 0.5f)) return;
    float cx = x + w / 2, cy = y + h / 2;
    auto place = [&](float s) {
        for (int i = 0; i <= 16; i++) {
            float t = i / 16.f, mx = cx + t * (0.5f - cx), my = cy + t * (0.5f - cy);
            if (ctx.rectValid(mx - w * s / 2, my - h * s / 2, w * s, h * s)) return t;
        }
        return -1.f;
    };
    float lo = 0.f, hi = 1.f;
    for (int i = 0; i < 22; i++) { float mid = (lo + hi) / 2; if (place(mid) >= 0.f) lo = mid; else hi = mid; }
    float t = place(lo); if (t < 0.f) t = 1.f;
    float mx = cx + t * (0.5f - cx), my = cy + t * (0.5f - cy);
    p[G_CROP] = mx - w * lo / 2; p[G_CROP + 1] = my - h * lo / 2; p[G_CROP + 2] = w * lo; p[G_CROP + 3] = h * lo;
}

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
    std::string in = argv[1];
    if (in.size() > 4 && in.substr(in.size() - 4) == ".ppm") {   // synthetic 8 bit sRGB test picture
        FILE *pf = fopen(in.c_str(), "rb");
        int pw, ph, mx;
        if (!pf || fscanf(pf, "P6 %d %d %d", &pw, &ph, &mx) != 3) { fprintf(stderr, "bad ppm\n"); return 5; }
        fgetc(pf);
        std::vector<uint8_t> rgb(size_t(pw) * ph * 3), rgba(size_t(pw) * ph * 4);
        if (fread(rgb.data(), 1, rgb.size(), pf) != rgb.size()) return 5;
        fclose(pf);
        for (size_t i = 0; i < size_t(pw) * ph; i++) { rgba[i * 4] = rgb[i * 3]; rgba[i * 4 + 1] = rgb[i * 3 + 1]; rgba[i * 4 + 2] = rgb[i * 3 + 2]; rgba[i * 4 + 3] = 255; }
        rawFromSrgb8(rgba.data(), pw, ph, img);
    } else if (!decodeRaw(argv[1], half, img, err)) { fprintf(stderr, "decode: %s\n", err.c_str()); return 5; }
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
    bool autofit = false, mark = false;
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
        else if (k == "ksv") p[G_GEO2] = v;
        else if (k == "ksh") p[G_GEO2 + 1] = v;
        else if (k == "autofit") autofit = v > 0.5f;
        else if (k == "mark") mark = v > 0.5f;
        else if (k == "lensfill") {   // strong synthetic profile (poly3, k1 = 0.06) that samples beyond the frame edge without a crop fit
            p[G_LDIST] = 1.f - 0.06f; p[G_LDIST + 2] = 0.06f; p[G_LDIST_ON] = v;
        }
        else if (k == "lens") {   // synthetic Lumix S 20-60 @ 20 mm style profile: ptlens a b c, strong vignetting, TCA
            p[G_LDIST] = 1.f - 0.02161f + 0.03781f + 0.08584f; p[G_LDIST + 1] = -0.08584f; p[G_LDIST + 2] = -0.03781f; p[G_LDIST + 3] = 0.02161f; p[G_LDIST_ON] = v;
            p[G_LTCA] = 1.0005613f; p[G_LTCA + 2] = -0.0002213f; p[G_LTCA + 3] = 0.9996489f; p[G_LTCA + 5] = 0.0002051f; p[G_LTCA_ON] = v;
            p[G_LVIG] = -0.8703127f; p[G_LVIG + 1] = 0.1721043f; p[G_LVIG + 2] = -0.1557695f; p[G_LVIG_ON] = v;
        }
        else if (k == "curve") {   // master tone curve: an S (contrast up), switched on through the block flag exactly as the app does
            p[kOffBlocks + 60] = 1.f;
            for (int i = 0; i < kCurveSize; i++) { float x = i / 255.f; p[kOffCurves + i] = std::min(1.f, std::max(0.f, (getenv("CURVE_SQ") ? x * x : getenv("CURVE_LIN") ? x * v : x - 0.15f * v * float(std::sin(6.2831853 * x))))); }
        }
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
    fprintf(stderr, "SRC %d %d %d\n", img.width, img.height, ori);
    if (autofit) {
        fitCrop(p.data(), float(img.width), float(img.height));
        fprintf(stderr, "FITCROP %.5f %.5f %.5f %.5f\n", p[G_CROP], p[G_CROP + 1], p[G_CROP + 2], p[G_CROP + 3]);
    }
    eng.setDebugOutside(mark);
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
    if (getenv("GOLDEN_HALF")) {   // float render target: values must match the 8 bit render within rounding
        std::vector<uint16_t> hf(size_t(W) * H * 4);
        if (!eng.renderRegion(p.data(), W, H, {0, 0, 1, 1}, nullptr, true, hf.data())) { fprintf(stderr, "half render failed\n"); return 8; }
        double maxd = 0;
        for (size_t i = 0; i < size_t(W) * H; i++) for (int c = 0; c < 3; c++) maxd = std::max(maxd, std::abs(rl::halfToFloat(hf[i * 4 + c]) * 255.0 - rgba[i * 4 + c]));
        fprintf(stderr, "float target max diff vs 8 bit: %.2f levels\n", maxd);
    }
    if (mark) {
        size_t bad = 0;
        for (size_t i = 0; i < size_t(W) * H; i++) if (rgba[i * 4] == 255 && rgba[i * 4 + 1] == 0 && rgba[i * 4 + 2] == 255) bad++;
        fprintf(stderr, "OUTSIDE %zu of %zu\n", bad, size_t(W) * H);
    }
    FILE *f = fopen(argv[2], "wb");
    fprintf(f, "P6\n%d %d\n255\n", W, H);
    for (size_t i = 0; i < size_t(W) * H; i++) fwrite(&rgba[i * 4], 1, 3, f);
    fclose(f);
    fprintf(stderr, "wrote %s\n", argv[2]);
    return 0;
}
