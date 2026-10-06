#include "engine.h"

#include <GLES3/gl32.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <new>
#include <vector>

#include "halfs.h"
#include "base_curve.h"
#include "params.h"
#include "shader_sources.h"

namespace rl {
namespace {

constexpr int kMargin = 8;
constexpr int kAnalysisEdge = 512;
constexpr int kSmallEdge = 512;   // renders no larger than this on either side use the small, fixed size targets

std::string expandIncludes(const char *src) {
    std::string in(src), out;
    size_t pos = 0;
    while (pos < in.size()) {
        size_t nl = in.find('\n', pos);
        std::string line = in.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos);
        if (line.rfind("//@include ", 0) == 0) {
            std::string name = line.substr(11);
            while (!name.empty() && (name.back() == '\r' || name.back() == ' ')) name.pop_back();
            const char *inc = shaderByName(name.c_str());
            if (inc) out += inc;
        } else {
            out += line;
            out += '\n';
        }
        if (nl == std::string::npos) break;
        pos = nl + 1;
    }
    return out;
}

GLuint compile(GLenum type, const std::string &src, std::string &err) {
    GLuint s = glCreateShader(type);
    const char *c = src.c_str();
    glShaderSource(s, 1, &c, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        err += log;
        glDeleteShader(s);
        return 0;
    }
    return s;
}

// Row-major 3x3 multiply
void mul3(const double a[9], const double b[9], double o[9]) {
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
}

void proPhotoToSrgb(float out[9]) {
    // ProPhoto (D50) -> XYZ D50 -> linear sRGB (Bradford adapted)
    const double pro2xyz[9] = {0.7977604897, 0.1351858372, 0.0313493496, 0.2880711282, 0.7118432178, 0.0000856540, 0.0, 0.0, 0.8251046025};
    const double xyz2srgb[9] = {3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427};
    double m[9];
    mul3(xyz2srgb, pro2xyz, m);
    // GL expects column-major
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++) out[c * 3 + r] = float(m[r * 3 + c]);
}

struct ColourMats { float srgb[9], p3[9]; };

// Computed once (C++11 magic static: safe when the editor and an export thread render at the same time).
const ColourMats &colourMats() {
    static const ColourMats m = [] {
        ColourMats r{};
        proPhotoToSrgb(r.srgb);
        // ProPhoto (D50) -> linear Display P3, column major
        const double d[9] = {1.63277, -0.37961, -0.252809, -0.153699, 1.166619, -0.013002, 0.010388, -0.062789, 1.052053};
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r.p3[j * 3 + i] = float(d[i * 3 + j]);
        return r;
    }();
    return m;
}

}  // namespace

bool Engine::build(Prog &p, const char *vs, const char *fs, std::string &err) {
    GLuint v = compile(GL_VERTEX_SHADER, expandIncludes(vs), err);
    GLuint f = compile(GL_FRAGMENT_SHADER, expandIncludes(fs), err);
    if (!v || !f) {   // one compiled and its partner did not: do not leak the one that did
        if (v) glDeleteShader(v);
        if (f) glDeleteShader(f);
        return false;
    }
    p.id = glCreateProgram();
    p.loc.clear();
    glAttachShader(p.id, v);
    glAttachShader(p.id, f);
    glLinkProgram(p.id);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p.id, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetProgramInfoLog(p.id, sizeof(log), nullptr, log);
        err += log;
        glDeleteProgram(p.id);
        p.id = 0;
        return false;
    }
    return true;
}

static GLuint makeTex2D(GLenum target, GLint filter) {
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(target, t);
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, filter == GL_LINEAR_MIPMAP_LINEAR ? GL_LINEAR : filter);
    glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

bool Engine::init(std::string &error) {
    if (ready_) return true;
    drainErrors();
    if (!build(lowres_, SH_vert_glsl, SH_lowres_frag, error) || !build(blur_, SH_vert_glsl, SH_blur_frag, error) ||
        !build(main_, SH_vert_glsl, SH_main_frag, error) || !build(out_, SH_vert_glsl, SH_out_frag, error)) {
        for (Prog *pr : {&lowres_, &blur_, &main_, &out_}) { if (pr->id) glDeleteProgram(pr->id); pr->id = 0; }   // release() skips an engine that never became ready
        return false;
    }
    glGenVertexArrays(1, &vao_);

    blocksTex_ = makeTex2D(GL_TEXTURE_2D, GL_NEAREST);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, kBlockTexels, kMaxBlocks);
    masksTex_ = makeTex2D(GL_TEXTURE_2D, GL_NEAREST);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, kMaskTexels, kMaxMasks);
    curvesTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16F, kCurveSize, kCurveRows);
    baseTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16F, kCurveSize, 1);
    {
        std::vector<uint16_t> id(kCurveSize);
        for (int i = 0; i < kCurveSize; i++) id[i] = floatToHalf(i / 255.0f);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kCurveSize, 1, GL_RED, GL_HALF_FLOAT, id.data());
    }
    // The same table at full float precision for the 16 bit export. NEAREST so it stays complete on drivers without
    // float32 filtering; the shader interpolates between two texelFetch reads itself.
    baseTex32_ = makeTex2D(GL_TEXTURE_2D, GL_NEAREST);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, kCurveSize, 1);
    // 1x1 array texture until a real layer arrives.
    layersTex_ = makeTex2D(GL_TEXTURE_2D_ARRAY, GL_LINEAR);
    glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R8, kLayerTex, kLayerTex, kMaxLayers);
    overlayTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1);
    srcTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR_MIPMAP_LINEAR);
    setBaseCurve(kBaseCurve);
    ready_ = true;
    return true;
}

void Engine::freeTarget(Target &t) {
    if (t.fbo) glDeleteFramebuffers(1, &t.fbo);
    if (t.tex) glDeleteTextures(1, &t.tex);
    t = Target();
}

void Engine::ensureTarget(Target &t, int w, int h, GLenum fmt) {
    if (t.tex && t.w == w && t.h == h && t.fmt == fmt) return;
    freeTarget(t);
    targetAllocs_++;
    t.w = w; t.h = h; t.fmt = fmt;
    // float32 textures are only filterable with an extension; targets are read with texelFetch or exact texel coordinates there
    t.tex = makeTex2D(GL_TEXTURE_2D, fmt == GL_RGBA32F ? GL_NEAREST : GL_LINEAR);
    glTexStorage2D(GL_TEXTURE_2D, 1, fmt, w, h);
    glGenFramebuffers(1, &t.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) targetsOk_ = false;   // renders then report failure instead of a silent black frame
}

void Engine::release() {
    if (!ready_) return;
    for (Target *t : {&l0_, &bs_, &bl_, &bd_, &tmp_, &e_, &outT_, &eS_, &outS_}) freeTarget(*t);
    GLuint texs[] = {srcTex_, blocksTex_, masksTex_, curvesTex_, layersTex_, overlayTex_, baseTex_, baseTex32_};
    glDeleteTextures(8, texs);
    glDeleteProgram(lowres_.id); glDeleteProgram(blur_.id); glDeleteProgram(main_.id); glDeleteProgram(out_.id);
    glDeleteVertexArrays(1, &vao_);
    srcTex_ = 0; srcW_ = srcH_ = 0;
    ready_ = false;
}

// Builds the new source texture first and swaps it in only when it is complete, so a failed upload (out of GPU memory on a 45 MP
// file, a size over the driver limit) leaves the previous source, its size and the analysis cache exactly as they were.
// The GL context this engine belonged to is gone (lost surface, new context). Its object names mean nothing in the current context:
// deleting them there raises GL_INVALID_VALUE (or worse, frees someone else's objects), so forget them without any GL call.
void Engine::abandon() {
    for (Target *t : {&l0_, &bs_, &bl_, &bd_, &tmp_, &e_, &outT_, &eS_, &outS_}) *t = Target();
    for (Prog *pr : {&lowres_, &blur_, &main_, &out_}) pr->loc.clear();
    srcTex_ = blocksTex_ = masksTex_ = curvesTex_ = layersTex_ = overlayTex_ = baseTex_ = baseTex32_ = 0;
    lowres_.id = blur_.id = main_.id = out_.id = 0;
    vao_ = 0;
    srcW_ = srcH_ = 0;
    overlayW_ = 0;
    lastCurves_.clear();
    ready_ = false;
}

bool Engine::setSource(int w, int h, const uint16_t *rgbaHalf) {
    drainErrors();
    if (w <= 0 || h <= 0 || !rgbaHalf) return false;
    int levels = 1;
    for (int m = std::max(w, h); m > 1; m >>= 1) levels++;
    GLuint tex = makeTex2D(GL_TEXTURE_2D, GL_LINEAR_MIPMAP_LINEAR);
    glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA16F, w, h);
    bool ok = glGetError() == GL_NO_ERROR;
    if (ok) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        // strips of about 8 MB: the driver stages one strip at a time instead of a second copy of the whole picture
        const int rowsPer = std::max(1, int((size_t(8) << 20) / (size_t(w) * 8)));
        for (int y = 0; y < h && ok; y += rowsPer) {
            int rows = std::min(rowsPer, h - y);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y, w, rows, GL_RGBA, GL_HALF_FLOAT, rgbaHalf + size_t(y) * w * 4);
            ok = glGetError() == GL_NO_ERROR;
        }
        if (ok) { glGenerateMipmap(GL_TEXTURE_2D); ok = glGetError() == GL_NO_ERROR; }
    }
    if (!ok) {
        glDeleteTextures(1, &tex);
        drainErrors();
        return false;
    }
    GLuint old = srcTex_;
    srcTex_ = tex;
    if (old) glDeleteTextures(1, &old);
    srcW_ = w; srcH_ = h; srcLevels_ = levels;
    invalidateAnalysis();
    return true;
}

// Bilinear resample of an 8 bit mask (pixel centre mapping). Masks cover the whole frame, so stretching is exact in meaning.
static void resampleMask(const uint8_t *src, int sw, int sh, uint8_t *dst, int dw, int dh) {
    for (int y = 0; y < dh; y++) {
        float fy = std::min(std::max((y + 0.5f) * sh / dh - 0.5f, 0.f), float(sh - 1));
        int y0 = int(fy), y1 = std::min(y0 + 1, sh - 1); float ty = fy - y0;
        for (int x = 0; x < dw; x++) {
            float fx = std::min(std::max((x + 0.5f) * sw / dw - 0.5f, 0.f), float(sw - 1));
            int x0 = int(fx), x1 = std::min(x0 + 1, sw - 1); float tx = fx - x0;
            float top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx;
            float bot = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx;
            dst[y * dw + x] = uint8_t(top * (1 - ty) + bot * ty + 0.5f);
        }
    }
}

// All layers share one fixed size array texture (kLayerTex squared), so a layer of any size never disturbs the others.
void Engine::setLayer(int index, const uint8_t *alpha, int w, int h) {
    if (index < 0 || index >= kMaxLayers || !alpha || w <= 0 || h <= 0) return;
    glBindTexture(GL_TEXTURE_2D_ARRAY, layersTex_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    if (w == kLayerTex && h == kLayerTex) {
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, index, w, h, 1, GL_RED, GL_UNSIGNED_BYTE, alpha);
        return;
    }
    std::vector<uint8_t> tmp;
    try { tmp.resize(size_t(kLayerTex) * kLayerTex); } catch (const std::bad_alloc &) { return; }
    resampleMask(alpha, w, h, tmp.data(), kLayerTex, kLayerTex);
    glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, index, kLayerTex, kLayerTex, 1, GL_RED, GL_UNSIGNED_BYTE, tmp.data());
}

void Engine::setOverlay(const uint8_t *rgbaHalfBytes, int w, int h) {
    glDeleteTextures(1, &overlayTex_);
    overlayTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR);
    if (!rgbaHalfBytes) { glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1); overlayW_ = 0; return; }
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, w, h);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_HALF_FLOAT, rgbaHalfBytes);
    overlayW_ = w;
}

void Engine::updateOverlayRegion(int x, int y, int w, int h, const uint8_t *data) {
    if (overlayW_ <= 0) return;
    glBindTexture(GL_TEXTURE_2D, overlayTex_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RGBA, GL_HALF_FLOAT, data);
}

void Engine::setBaseCurve(const float *lut) {
    std::vector<uint16_t> h(kCurveSize);
    for (int i = 0; i < kCurveSize; i++) h[i] = floatToHalf(lut[i]);
    glBindTexture(GL_TEXTURE_2D, baseTex_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kCurveSize, 1, GL_RED, GL_HALF_FLOAT, h.data());
    glBindTexture(GL_TEXTURE_2D, baseTex32_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kCurveSize, 1, GL_RED, GL_FLOAT, lut);
}

void Engine::outputSize(const float *p, int &w, int &h) const {
    bool odd = int(p[G_GEO + 3] + 0.5f) % 2 == 1;
    float bw = odd ? srcH_ : srcW_, bh = odd ? srcW_ : srcH_;
    w = std::max(1, int(std::lround(p[G_CROP + 2] * bw)));
    h = std::max(1, int(std::lround(p[G_CROP + 3] * bh)));
}

void Engine::draw() {
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

void Engine::uploadTables(const float *p) {
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_2D, blocksTex_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kBlockTexels, kMaxBlocks, GL_RGBA, GL_FLOAT, p + kOffBlocks);
    glBindTexture(GL_TEXTURE_2D, masksTex_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kMaskTexels, kMaxMasks, GL_RGBA, GL_FLOAT, p + kOffMasks);
    const float *cur = p + kOffCurves;
    size_t n = size_t(kCurveRows) * kCurveSize;
    if (lastCurves_.size() != n || std::memcmp(lastCurves_.data(), cur, n * sizeof(float)) != 0) {
        lastCurves_.assign(cur, cur + n);
        std::vector<uint16_t> h(n);
        for (size_t i = 0; i < n; i++) h[i] = floatToHalf(cur[i]);
        glBindTexture(GL_TEXTURE_2D, curvesTex_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kCurveSize, kCurveRows, GL_RED, GL_HALF_FLOAT, h.data());
    }
}

void Engine::setGeometryUniforms(Prog &prog, const float *p) {
    glUniform2f(prog.u("uSrcSize"), float(srcW_), float(srcH_));
    glUniform4fv(prog.u("uCrop"), 1, p + G_CROP);
    glUniform4fv(prog.u("uGeo"), 1, p + G_GEO);
    glUniform4fv(prog.u("uGeo2"), 1, p + G_GEO2);
    glUniform4fv(prog.u("uLensDist"), 1, p + G_LDIST);
    glUniform2f(prog.u("uLensDist2"), p[G_LDIST + 4], p[G_LDIST_ON]);
}

void Engine::runAnalysis(const float *p) {
    // Key: geometry only (analysis is independent of the adjustment sliders).
    uint64_t key = 1469598103934665603ull;
    for (int i = G_CROP; i < G_GEO2 + 4; i++) {
        uint32_t b;
        std::memcpy(&b, &p[i], 4);
        key = (key ^ b) * 1099511628211ull;
    }
    for (int i = G_LDIST; i <= G_LDIST_ON; i++) {
        uint32_t b;
        std::memcpy(&b, &p[i], 4);
        key = (key ^ b) * 1099511628211ull;
    }
    key ^= uint64_t(srcW_) << 20 ^ uint64_t(srcH_) << 40 ^ uint64_t(srcTex_);
    if (key == analysisKey_) return;
    analysisKey_ = key;

    int ow, oh;
    outputSize(p, ow, oh);
    int lw = ow >= oh ? kAnalysisEdge : std::max(8, int(std::lround(kAnalysisEdge * double(ow) / oh)));
    int lh = ow >= oh ? std::max(8, int(std::lround(kAnalysisEdge * double(oh) / ow))) : kAnalysisEdge;
    for (Target *t : {&l0_, &bs_, &bl_, &bd_, &tmp_}) ensureTarget(*t, lw, lh, GL_RGBA16F);

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glViewport(0, 0, lw, lh);
    glUseProgram(lowres_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, srcTex_);
    glUniform1i(lowres_.u("uSrc"), 0);
    glUniform1f(lowres_.u("uFlipY"), 0.f);
    setGeometryUniforms(lowres_, p);
    float ratio = std::max(float(ow) / lw, float(oh) / lh);
    glUniform1f(lowres_.u("uSrcGain"), srcGain_);
    glUniform1f(lowres_.u("uLod"), std::clamp(std::log2(std::max(1.f, ratio)), 0.f, float(srcLevels_ - 1)));
    glBindFramebuffer(GL_FRAMEBUFFER, l0_.fbo);
    draw();

    auto blurPass = [&](Target &from, Target &to, bool horizontal, float step) {
        glBindFramebuffer(GL_FRAMEBUFFER, to.fbo);
        glUseProgram(blur_.id);
        glUniform1f(blur_.u("uFlipY"), 0.f);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, from.tex);
        glUniform1i(blur_.u("uTex"), 0);
        glUniform2f(blur_.u("uStep"), horizontal ? step / lw : 0.f, horizontal ? 0.f : step / lh);
        draw();
    };
    // bs = blur(l0), bl = blur(blur(bs)), bd = blur(bl)
    blurPass(l0_, tmp_, true, 1.f);  blurPass(tmp_, bs_, false, 1.f);
    blurPass(bs_, tmp_, true, 3.f);  blurPass(tmp_, bl_, false, 3.f);
    blurPass(bl_, tmp_, true, 3.f);  blurPass(tmp_, bl_, false, 3.f);   // bl: wide
    // Dark channel needs the unblurred-ish min channel: rebuild from l0 with a wide blur
    blurPass(l0_, tmp_, true, 4.f);  blurPass(tmp_, bd_, false, 4.f);
    blurPass(bd_, tmp_, true, 4.f);  blurPass(tmp_, bd_, false, 4.f);
}

void Engine::runMain(const float *p, Rect vis, int pw, int ph, Target &e, int margin, GLenum fmt, bool fixedCapacity) {
    int ow, oh;
    outputSize(p, ow, oh);
    // The region is pw x ph plus the margin. A fixed capacity target is made once and only a part of it is drawn (viewport), so
    // renders of different small sizes (histogram, picker, statistics) alternate without reallocating anything.
    const int ew = pw + 2 * margin, eh = ph + 2 * margin;
    if (fixedCapacity) ensureTarget(e, kSmallEdge + 2 * margin, kSmallEdge + 2 * margin, fmt);
    else ensureTarget(e, ew, eh, fmt);
    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST); glDisable(GL_CULL_FACE);
    glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
    glViewport(0, 0, ew, eh);
    glUseProgram(main_.id);
    Prog &pr = main_;
    struct B { GLenum target; GLuint tex; const char *name; };
    B binds[] = {
        {GL_TEXTURE_2D, srcTex_, "uSrc"}, {GL_TEXTURE_2D, bs_.tex, "uBs"}, {GL_TEXTURE_2D, bl_.tex, "uBl"},
        {GL_TEXTURE_2D, bd_.tex, "uBd"}, {GL_TEXTURE_2D, blocksTex_, "uBlocks"}, {GL_TEXTURE_2D, masksTex_, "uMasks"},
        {GL_TEXTURE_2D, curvesTex_, "uCurves"}, {GL_TEXTURE_2D_ARRAY, layersTex_, "uLayers"}, {GL_TEXTURE_2D, overlayTex_, "uOverlay"},
        {GL_TEXTURE_2D, baseTex_, "uBase"},
    };
    for (int i = 0; i < 10; i++) {
        glActiveTexture(GL_TEXTURE0 + i);
        glBindTexture(binds[i].target, binds[i].tex);
        glUniform1i(pr.u(binds[i].name), i);
    }
    float mx = vis.w * margin / pw, my = vis.h * margin / ph;
    glUniform4f(pr.u("uView"), vis.x - mx, vis.y - my, vis.w + 2 * mx, vis.h + 2 * my);
    glUniform1f(pr.u("uFlipY"), 0.f);
    glUniform2f(pr.u("uOutPx"), float(ew), float(eh));
    float hx = vis.w * ow / pw, hy = vis.h * oh / ph;
    float bw = std::max(float(srcW_), 1.f);
    // How many source pixels land on one output pixel (oriented crop size is in source pixels already).
    float lod = std::log2(std::max(1.f, std::max(hx, hy)));
    glUniform1f(pr.u("uSrcGain"), srcGain_);
    glUniform1f(pr.u("uLod"), std::clamp(lod, 0.f, float(srcLevels_ - 1)));
    glUniform1i(pr.u("uNumMasks"), int(p[G_NUM_MASKS] + 0.5f));
    glUniform1i(pr.u("uShowMask"), int(std::lround(p[G_SHOWMASK])));
    glUniform1f(pr.u("uAspect"), float(ow) / float(oh));
    glUniform3fv(pr.u("uTcaR"), 1, p + G_LTCA);
    glUniform3fv(pr.u("uTcaB"), 1, p + G_LTCA + 3);
    glUniform3fv(pr.u("uLensVig"), 1, p + G_LVIG);
    glUniform3f(pr.u("uLensFlags"), p[G_LTCA_ON], p[G_LVIG_ON], 0.f);
    glUniform1f(pr.u("uOverlayOn"), overlayW_ > 0 ? p[G_OVERLAY] : 0.f);
    glUniformMatrix3fv(pr.u("uToSrgb"), 1, GL_FALSE, colourMats().srgb);   // masks compare in sRGB display terms whatever the export space
    (void)bw;
    setGeometryUniforms(pr, p);
    draw();
}

template <class ProgT> static void setOutUniforms(ProgT &pr, const float *p, int pw, int ph, Rect vis, float aspect, float flip, float hiPrec, int margin, int space) {
    glUniform1i(pr.u("uE"), 0);
    glUniform1i(pr.u("uBase"), 1);
    glUniform1i(pr.u("uBase32"), 2);
    glUniform2i(pr.u("uMargin"), margin, margin);
    glUniform2f(pr.u("uPx"), float(pw), float(ph));
    glUniform4fv(pr.u("uDetail"), 1, p + G_DETAIL);
    glUniform2fv(pr.u("uNr"), 1, p + G_NR);
    glUniform4fv(pr.u("uFx"), 1, p + G_FX);
    glUniform4fv(pr.u("uFx2"), 1, p + G_FX2);
    glUniform4f(pr.u("uView"), vis.x, vis.y, vis.w, vis.h);
    glUniform1f(pr.u("uAspect"), aspect);
    float fullPx = pw / std::max(vis.w, 1e-6f);
    glUniform1f(pr.u("uPxScale"), fullPx / 1920.f);
    glUniformMatrix3fv(pr.u("uToSrgb"), 1, GL_FALSE, space == 1 ? colourMats().p3 : colourMats().srgb);
    glUniform1f(pr.u("uHiPrec"), hiPrec);
    glUniform1f(pr.u("uChecker"), flip > 0.5f ? 1.f : 0.f);
    glUniform1f(pr.u("uFlipY"), flip);
}

bool Engine::renderToScreen(const float *p, int vx, int vy, int vw, int vh, Rect vis) {
    if (!hasSource() || vw < 1 || vh < 1) return true;   // nothing to draw is not a failure
    drainErrors();
    targetsOk_ = true;
    GLint prevFbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    uploadTables(p);
    runAnalysis(p);
    runMain(p, vis, vw, vh, e_, kMargin, GL_RGBA16F, false);
    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    glViewport(vx, vy, vw, vh);
    glUseProgram(out_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, e_.tex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, baseTex_);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, baseTex32_);
    int ow, oh;
    outputSize(p, ow, oh);
    setOutUniforms(out_, p, vw, vh, vis, float(ow) / oh, 1.f, 0.f, kMargin, 0);
    draw();
    return targetsOk_ && glGetError() == GL_NO_ERROR;   // false: the frame is black or stale, the caller can say so
}

// Draws the output pass into outT_ (RGBA8, or RGBA32F in high precision mode) and leaves outT_ bound for reading.
bool Engine::drawOutput(const float *p, int pw, int ph, Rect vis) {
    targetsOk_ = true;
    uploadTables(p);
    runAnalysis(p);
    // Small renders (histogram, colour picker, auto statistics, heal patches) have their own targets, so they never resize the
    // targets the screen frame uses (each resize freed and re-made a screen sized RGBA16F texture, twice per slider tick).
    const bool small = pw <= kSmallEdge && ph <= kSmallEdge && !forceBigTargets_;
    Target &e = small ? eS_ : e_;
    Target &o = small ? outS_ : outT_;
    runMain(p, vis, pw, ph, e, kMargin, hiPrec_ ? GL_RGBA32F : GL_RGBA16F, small);
    if (small) ensureTarget(o, kSmallEdge, kSmallEdge, hiPrec_ ? GL_RGBA32F : GL_RGBA8);
    else ensureTarget(o, pw, ph, hiPrec_ ? GL_RGBA32F : GL_RGBA8);
    glBindFramebuffer(GL_FRAMEBUFFER, o.fbo);
    glViewport(0, 0, pw, ph);
    glUseProgram(out_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, e.tex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, baseTex_);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, baseTex32_);
    int ow, oh;
    outputSize(p, ow, oh);
    setOutUniforms(out_, p, pw, ph, vis, float(ow) / oh, 0.f, hiPrec_ ? 1.f : 0.f, kMargin, outputSpace_);
    glUniform1f(out_.u("uMark"), debugOutside_ ? 1.f : 0.f);
    draw();
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    return targetsOk_;
}

bool Engine::renderRegion(const float *p, int pw, int ph, Rect vis, uint8_t *rgba) {
    if (!hasSource() || hiPrec_) return false;
    drainErrors();
    GLint prevFbo = 0, prevVp[4];
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glGetIntegerv(GL_VIEWPORT, prevVp);
    bool ok = drawOutput(p, pw, ph, vis);
    if (ok) glReadPixels(0, 0, pw, ph, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
    return ok && glGetError() == GL_NO_ERROR;
}

// 16 bit export: float render targets end to end, quantised once here (no half float step, no second conversion in Kotlin).
bool Engine::renderRegion16(const float *p, int pw, int ph, Rect vis, uint16_t *rgb) {
    if (!hasSource() || !hiPrec_) return false;
    drainErrors();
    GLint prevFbo = 0, prevVp[4];
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glGetIntegerv(GL_VIEWPORT, prevVp);
    bool ok = drawOutput(p, pw, ph, vis);
    if (ok) {
        const int rowsPer = std::max(1, std::min(ph, (1 << 20) / std::max(pw, 1)));   // about 16 MB of floats at a time instead of the whole tile
        std::vector<float> f(size_t(pw) * rowsPer * 4);
        for (int y0 = 0; y0 < ph; y0 += rowsPer) {
            int rows = std::min(rowsPer, ph - y0);
            glReadPixels(0, y0, pw, rows, GL_RGBA, GL_FLOAT, f.data());
            for (size_t i = 0, n = size_t(pw) * rows; i < n; i++) {
                for (int c = 0; c < 3; c++) {
                    float v = f[i * 4 + c];
                    v = !(v >= 0.f) ? 0.f : v > 1.f ? 1.f : v;   // NaN and negatives to 0
                    rgb[(size_t(y0) * pw + i) * 3 + c] = uint16_t(v * 65535.f + 0.5f);
                }
            }
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
    return ok && glGetError() == GL_NO_ERROR;
}

}  // namespace rl
