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
#include <vector>

#include "engine/base_curve.h"
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

// ---- Scene keys: the key=value arguments of the harness, as tables ----
// One table per topic. A key is either a plain parameter slot ({"name", index}: the value is written to p[index]) or a handler
// ({"name", function}) for anything that needs more than one write. Tasks add their keys to the table of their own topic, or add a
// new topic table and list it in kTopics (the one place marked NEW TOPIC below), so two independent tasks never edit the same lines.
// A key that appears in two tables is an error at start up. An unknown key on the command line is ignored.
struct Scene {                         // what a key handler may change
    std::vector<float> &p;
    Engine &eng;
    bool autofit = false, mark = false, useMaskExposure = false;
    int look = 2;                      // look version (docs/COLOUR.md): 1 reproduces every edit saved before looks existed
    float maskExposure = 0.f;
};
using KeyFn = void (*)(Scene &s, const char *key, float v, const char *text);
struct KeyDef {
    const char *name; int index; KeyFn fn;
    KeyDef(const char *n, int i) : name(n), index(i), fn(nullptr) {}
    KeyDef(const char *n, KeyFn f) : name(n), index(-1), fn(f) {}
};

// Mask 0's header, the way the app writes it: one component, amount 1
static float *beginMask(Scene &s) { float *m = s.p.data() + kOffMasks; m[0] = 1; m[1] = 1; m[2] = 0; m[3] = 0; return m; }

// maskcolor and maskluma: the same handler, told apart by the key
static void maskRange(Scene &s, const char *key, float, const char *text) {
    float a0 = 0, a1 = 0, a2 = 0, a3 = 0, a4 = 0;
    sscanf(text, "%f,%f,%f,%f,%f", &a0, &a1, &a2, &a3, &a4);
    float *m = beginMask(s);
    if (!strcmp(key, "maskcolor")) { m[4] = 4; m[8] = a0; m[9] = a1; m[10] = a2; m[11] = a3; m[12] = a4; }
    else { m[4] = 5; m[8] = a0; m[9] = a1; m[10] = a2; }
    s.p[G_NUM_MASKS] = 1;
    s.useMaskExposure = true;
}

// -- tone --
static const std::vector<KeyDef> kToneKeys = {
    {"exposure", kOffBlocks + S_EXPOSURE}, {"contrast", kOffBlocks + S_CONTRAST}, {"highlights", kOffBlocks + S_HIGHLIGHTS},
    {"shadows", kOffBlocks + S_SHADOWS}, {"whites", kOffBlocks + S_WHITES}, {"blacks", kOffBlocks + S_BLACKS},
    {"curve", [](Scene &s, const char *, float v, const char *) {   // master tone curve: an S (contrast up), switched on through the block flag exactly as the app does
        s.p[kOffBlocks + 60] = 1.f;
        for (int i = 0; i < kCurveSize; i++) { float x = i / 255.f; s.p[kOffCurves + i] = std::min(1.f, std::max(0.f, (getenv("CURVE_SQ") ? x * x : getenv("CURVE_LIN") ? x * v : x - 0.15f * v * float(std::sin(6.2831853 * x))))); }
    }},
};

// -- colour --
static const std::vector<KeyDef> kColourKeys = {
    {"temp", kOffBlocks + S_TEMP}, {"tint", kOffBlocks + S_TINT}, {"vibrance", kOffBlocks + S_VIBRANCE}, {"saturation", kOffBlocks + S_SATURATION},
    {"ghue", kOffBlocks + S_GRADE_GLOBAL},         // global colour grade hue (0..1)
    {"gsat", kOffBlocks + S_GRADE_GLOBAL + 1},     // global colour grade saturation (0..1)
    {"gshadows", [](Scene &s, const char *, float, const char *text) {   // shadows grade: hue,saturation
        float h0 = 0, s0 = 0; sscanf(text, "%f,%f", &h0, &s0);
        s.p[kOffBlocks + S_GRADE_SHADOWS] = h0; s.p[kOffBlocks + S_GRADE_SHADOWS + 1] = s0;
    }},
    {"hslsat", [](Scene &s, const char *, float v, const char *) { for (int b = 0; b < 8; b++) s.p[kOffBlocks + S_MIX_SAT + b] = v; }},   // all eight HSL saturation sliders
    {"hsllum", [](Scene &s, const char *, float v, const char *) { for (int b = 0; b < 8; b++) s.p[kOffBlocks + S_MIX_LUM + b] = v; }},
    {"hslband", [](Scene &s, const char *, float v, const char *) {   // band * 1000 + 500 + amount: one HSL saturation slider
        int b = int(v) / 1000; float amt = float(int(v) % 1000) - 500.f; s.p[kOffBlocks + S_MIX_SAT + b] = amt;
    }},
};

// -- local contrast, detail and effects --
static const std::vector<KeyDef> kDetailKeys = {
    {"texture", kOffBlocks + S_TEXTURE}, {"clarity", kOffBlocks + S_CLARITY}, {"dehaze", kOffBlocks + S_DEHAZE},
    {"sharpen", G_DETAIL}, {"nrl", G_NR}, {"nrc", G_NR + 1}, {"vig", G_FX}, {"grain", G_FX2},
};

// -- masks and layers (each mask key defines mask 0) --
static const std::vector<KeyDef> kMaskKeys = {
    {"maskexp", [](Scene &s, const char *, float v, const char *) {   // linear gradient mask 0 with exposure delta
        float *m = beginMask(s);                                          // header: 1 component, amount 1
        m[4] = 1; m[5] = 0; m[6] = 0; m[7] = 0;                         // comp: type linear, op add
        m[8] = 0.2f; m[9] = 0.5f; m[10] = 0.8f; m[11] = 0.5f;           // start / end
        s.p[G_NUM_MASKS] = 1;
        s.p[kOffBlocks + kBlockFloats + S_EXPOSURE] = v;
    }},
    {"masklayer", [](Scene &s, const char *, float v, const char *) {   // bitmap mask: disc near the top left of the full frame
        const int lw = 512, lh = 341;
        std::vector<uint8_t> a(lw * lh, 0);
        for (int y = 0; y < lh; y++) for (int x = 0; x < lw; x++) { float dx = x - 130.f, dy = y - 90.f; if (dx * dx + dy * dy < 80.f * 80.f) a[y * lw + x] = 255; }
        s.eng.setLayer(0, a.data(), lw, lh);
        float *m = beginMask(s);
        m[4] = 3; m[5] = 0; m[6] = 0; m[7] = 0;   // type bitmap, layer 0
        s.p[G_NUM_MASKS] = 1;
        s.p[kOffBlocks + kBlockFloats + S_EXPOSURE] = v;
    }},
    {"masklayers", [](Scene &s, const char *, float v, const char *) {   // two bitmap layers of different sizes in one mask: both discs must show (upload of the second must not wipe the first)
        const int aw = 512, ah = 341, bw = 200, bh = 300;
        std::vector<uint8_t> a(aw * ah, 0), b(bw * bh, 0);
        for (int y = 0; y < ah; y++) for (int x = 0; x < aw; x++) { float dx = x - 130.f, dy = y - 90.f; if (dx * dx + dy * dy < 80.f * 80.f) a[y * aw + x] = 255; }
        for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) { float dx = x - 130.f, dy = y - 200.f; if (dx * dx + dy * dy < 60.f * 60.f) b[y * bw + x] = 255; }
        s.eng.setLayer(0, a.data(), aw, ah);
        s.eng.setLayer(1, b.data(), bw, bh);
        float *m = s.p.data() + kOffMasks;
        m[0] = 2; m[1] = 1; m[2] = 0; m[3] = 0;
        m[4] = 3; m[5] = 0; m[6] = 0; m[7] = 0;     // component 0: bitmap, layer 0
        m[16] = 3; m[17] = 0; m[18] = 0; m[19] = 1; // component 1: bitmap, layer 1, add
        s.p[G_NUM_MASKS] = 1;
        s.p[kOffBlocks + kBlockFloats + S_EXPOSURE] = v;
    }},
    {"maskclarity", kOffBlocks + kBlockFloats + S_CLARITY},   // clarity inside mask 0 (any mask key defines the mask)
    {"maskexposure", [](Scene &s, const char *, float v, const char *) { s.maskExposure = v; }},   // exposure of mask 0 for the colour and luminance range masks
    {"maskcolor", maskRange},   // colour range (r,g,b,range,softness in display terms)
    {"maskluma", maskRange},    // luminance range (lo,hi,falloff)
    {"maskrad", [](Scene &s, const char *, float v, const char *) {
        float *m = beginMask(s);
        m[4] = 2; m[5] = 0; m[6] = 0; m[7] = 0;
        m[8] = 0.5f; m[9] = 0.5f; m[10] = 0.35f; m[11] = 0.35f;
        m[12] = 0; m[13] = 0.5f; m[14] = 0; m[15] = 0;
        s.p[G_NUM_MASKS] = 1;
        s.p[kOffBlocks + kBlockFloats + S_EXPOSURE] = v;
    }},
    {"overlay", [](Scene &s, const char *, float v, const char *) {   // flat heal patch (linear grey value v, premultiplied, alpha 1) over the left half of the source
        const int ow = 64, oh = 64;
        std::vector<uint16_t> ov(size_t(ow) * oh * 4, 0);
        for (int y = 0; y < oh; y++) for (int x = 0; x < ow / 2; x++) {
            uint16_t *d = &ov[(size_t(y) * ow + x) * 4];
            d[0] = d[1] = d[2] = floatToHalf(v); d[3] = floatToHalf(1.f);
        }
        s.eng.setOverlay(reinterpret_cast<const uint8_t *>(ov.data()), ow, oh);
        s.p[G_OVERLAY] = 1.f;
    }},
};

// -- geometry (crop, rotation, keystone) --
static const std::vector<KeyDef> kGeometryKeys = {
    {"angle", G_GEO}, {"cropx", G_CROP}, {"cropw", G_CROP + 2}, {"ksv", G_GEO2}, {"ksh", G_GEO2 + 1},
    {"autofit", [](Scene &s, const char *, float v, const char *) { s.autofit = v > 0.5f; }},
    {"orient", [](Scene &s, const char *, float v, const char *) {   // EXIF orientation 1..8 as RenderParams.orientationToRotFlip maps it: (rot90 clockwise, flipH)
        static const int rotOf[9] = {0, 0, 0, 2, 2, 1, 1, 3, 3}, flipOf[9] = {0, 0, 1, 0, 1, 1, 0, 1, 0};
        int o = int(v); if (o < 1 || o > 8) o = 1;
        s.p[G_GEO + 3] = float(rotOf[o]); s.p[G_GEO + 1] = float(flipOf[o]);
    }},
};

// -- lens profile (optics) --
static const std::vector<KeyDef> kOpticsKeys = {
    {"optvig", G_GEO2 + 3},   // manual lens vignetting correction slider (already in shader units)
    {"lensfill", [](Scene &s, const char *, float v, const char *) {   // strong synthetic profile (poly3, k1 = 0.06) that samples beyond the frame edge without a crop fit
        s.p[G_LDIST] = 1.f - 0.06f; s.p[G_LDIST + 2] = 0.06f; s.p[G_LDIST_ON] = v;
    }},
    {"lens", [](Scene &s, const char *, float v, const char *) {   // synthetic Lumix S 20-60 @ 20 mm style profile: ptlens a b c, strong vignetting, TCA
        auto &p = s.p;
        p[G_LDIST] = 1.f - 0.02161f + 0.03781f + 0.08584f; p[G_LDIST + 1] = -0.08584f; p[G_LDIST + 2] = -0.03781f; p[G_LDIST + 3] = 0.02161f; p[G_LDIST_ON] = v;
        p[G_LTCA] = 1.0005613f; p[G_LTCA + 2] = -0.0002213f; p[G_LTCA + 3] = 0.9996489f; p[G_LTCA + 5] = 0.0002051f; p[G_LTCA_ON] = v;
        p[G_LVIG] = -0.8703127f; p[G_LVIG + 1] = 0.1721043f; p[G_LVIG + 2] = -0.1557695f; p[G_LVIG_ON] = v;
    }},
};

// -- look version (docs/COLOUR.md): 1 or 2 (default 2) --
static const std::vector<KeyDef> kLookKeys = {
    {"look", [](Scene &s, const char *, float v, const char *) { s.look = int(v); }},
};

// -- debug --
static const std::vector<KeyDef> kDebugKeys = {
    {"mark", [](Scene &s, const char *, float v, const char *) { s.mark = v > 0.5f; }},   // paint pixels outside the image magenta
};

// NEW TOPIC: add its table above and list it here. (Studio has its own harness, tools/golden/studio_golden.cpp.)
static const std::vector<const std::vector<KeyDef> *> kTopics = {
    &kToneKeys, &kColourKeys, &kDetailKeys, &kMaskKeys, &kGeometryKeys, &kOpticsKeys, &kLookKeys, &kDebugKeys,
};

static std::map<std::string, const KeyDef *> buildKeyIndex() {
    std::map<std::string, const KeyDef *> index;
    for (const auto *topic : kTopics) for (const KeyDef &k : *topic) {
        if (!index.emplace(k.name, &k).second) { fprintf(stderr, "scene key '%s' is defined in two tables\n", k.name); exit(2); }
    }
    return index;
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
    Scene sc{p, eng};
    const std::map<std::string, const KeyDef *> keys = buildKeyIndex();
    for (int i = 5; i < argc; i++) {
        std::string a = argv[i];
        size_t eq = a.find('=');
        if (eq == std::string::npos) continue;
        std::string k = a.substr(0, eq);
        auto it = keys.find(k);
        if (it == keys.end()) continue;   // an unknown key is ignored, as it always was
        const char *text = a.c_str() + eq + 1;
        float v = float(atof(text));
        const KeyDef &kd = *it->second;
        if (kd.fn) kd.fn(sc, k.c_str(), v, text); else p[kd.index] = v;
    }
    bool autofit = sc.autofit, mark = sc.mark, useMaskExposure = sc.useMaskExposure;
    int look = sc.look;
    p[G_LOOK] = float(look);   // one key switches the curve, the source gain and the shader maths
    float maskExposure = sc.maskExposure;
    if (useMaskExposure) p[kOffBlocks + kBlockFloats + S_EXPOSURE] = maskExposure;
    eng.setBaseCurve(look == 1 ? kBaseCurve : kBaseCurve2);
    eng.setSrcGain(look == 1 ? img.v1Scale : img.wbGain);
    fprintf(stderr, "SRC %d %d %d\n", img.width, img.height, ori);   // read by run-golden.sh and fitcheck.py: keep this line as it is
    fprintf(stderr, "LOOK %d gain %.4f\n", look, look == 1 ? img.v1Scale : img.wbGain);
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
    if (getenv("GOLDEN_HALF")) {   // 16 bit path (float32 targets, float32 curve): must match the 8 bit render within rounding
        eng.setHighPrecision(true);
        std::vector<uint16_t> hf(size_t(W) * H * 3);
        if (!eng.renderRegion16(p.data(), W, H, {0, 0, 1, 1}, hf.data())) { fprintf(stderr, "16 bit render failed\n"); return 8; }
        double maxd = 0;
        for (size_t i = 0; i < size_t(W) * H; i++) for (int c = 0; c < 3; c++) maxd = std::max(maxd, std::abs(hf[i * 3 + c] / 257.0 - rgba[i * 4 + c]));
        fprintf(stderr, "16 bit target max diff vs 8 bit: %.2f levels\n", maxd);
        if (maxd > 1.01) { fprintf(stderr, "16 bit path disagrees with the 8 bit path\n"); return 8; }
        std::vector<uint8_t> seen(65536, 0);
        size_t distinct = 0;
        for (size_t i = 0; i < size_t(W) * H * 3; i++) if (!seen[hf[i]]) { seen[hf[i]] = 1; distinct++; }
        fprintf(stderr, "16 bit distinct values: %zu\n", distinct);
        if (distinct < 1000) { fprintf(stderr, "16 bit output has only %zu distinct values: not 16 bit\n", distinct); return 9; }
        eng.setHighPrecision(false);
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
