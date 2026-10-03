#include "engine.h"

#include <GLES3/gl32.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>

#include "halfs.h"
#include "base_curve.h"
#include "params.h"
#include "shader_sources.h"

namespace rl {
namespace {

constexpr int kMargin = 8;
constexpr int kAnalysisEdge = 512;

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

}  // namespace

bool Engine::build(Prog &p, const char *vs, const char *fs, std::string &err) {
    GLuint v = compile(GL_VERTEX_SHADER, expandIncludes(vs), err);
    GLuint f = compile(GL_FRAGMENT_SHADER, expandIncludes(fs), err);
    if (!v || !f) return false;
    p.id = glCreateProgram();
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
    if (!build(lowres_, SH_vert_glsl, SH_lowres_frag, error)) return false;
    if (!build(blur_, SH_vert_glsl, SH_blur_frag, error)) return false;
    if (!build(main_, SH_vert_glsl, SH_main_frag, error)) return false;
    if (!build(out_, SH_vert_glsl, SH_out_frag, error)) return false;
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
    // 1x1 array texture until a real layer arrives.
    layersTex_ = makeTex2D(GL_TEXTURE_2D_ARRAY, GL_LINEAR);
    glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R8, 1, 1, kMaxLayers);
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
    t.w = w; t.h = h; t.fmt = fmt;
    t.tex = makeTex2D(GL_TEXTURE_2D, GL_LINEAR);
    glTexStorage2D(GL_TEXTURE_2D, 1, fmt, w, h);
    glGenFramebuffers(1, &t.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
}

void Engine::release() {
    if (!ready_) return;
    for (Target *t : {&l0_, &bs_, &bl_, &bd_, &tmp_, &e_, &outT_}) freeTarget(*t);
    GLuint texs[] = {srcTex_, blocksTex_, masksTex_, curvesTex_, layersTex_, overlayTex_, baseTex_};
    glDeleteTextures(7, texs);
    glDeleteProgram(lowres_.id); glDeleteProgram(blur_.id); glDeleteProgram(main_.id); glDeleteProgram(out_.id);
    glDeleteVertexArrays(1, &vao_);
    srcTex_ = 0; srcW_ = srcH_ = 0;
    ready_ = false;
}

bool Engine::setSource(int w, int h, const uint16_t *rgbaHalf) {
    glBindTexture(GL_TEXTURE_2D, srcTex_);
    int levels = 1;
    for (int m = std::max(w, h); m > 1; m >>= 1) levels++;
    glDeleteTextures(1, &srcTex_);
    srcTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR_MIPMAP_LINEAR);
    glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA16F, w, h);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_HALF_FLOAT, rgbaHalf);
    glGenerateMipmap(GL_TEXTURE_2D);
    srcW_ = w; srcH_ = h; srcLevels_ = levels;
    invalidateAnalysis();
    return glGetError() == GL_NO_ERROR;
}

void Engine::setLayer(int index, const uint8_t *alpha, int w, int h) {
    if (index < 0 || index >= kMaxLayers) return;
    glBindTexture(GL_TEXTURE_2D_ARRAY, layersTex_);
    GLint cw = 0, ch = 0;
    glGetTexLevelParameteriv(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_WIDTH, &cw);
    glGetTexLevelParameteriv(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_HEIGHT, &ch);
    if (cw != w || ch != h) {
        glDeleteTextures(1, &layersTex_);
        layersTex_ = makeTex2D(GL_TEXTURE_2D_ARRAY, GL_LINEAR);
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R8, w, h, kMaxLayers);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, index, w, h, 1, GL_RED, GL_UNSIGNED_BYTE, alpha);
}

void Engine::setOverlay(const uint8_t *rgbaHalfBytes, int w, int h) {
    glDeleteTextures(1, &overlayTex_);
    overlayTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR);
    if (!rgbaHalfBytes) { glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1); overlayW_ = 0; return; }
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, w, h);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_HALF_FLOAT, rgbaHalfBytes);
    overlayW_ = w;
}

void Engine::setBaseCurve(const float *lut) {
    std::vector<uint16_t> h(kCurveSize);
    for (int i = 0; i < kCurveSize; i++) h[i] = floatToHalf(lut[i]);
    glBindTexture(GL_TEXTURE_2D, baseTex_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kCurveSize, 1, GL_RED, GL_HALF_FLOAT, h.data());
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

void Engine::setGeometryUniforms(GLuint prog, const float *p) {
    glUniform2f(glGetUniformLocation(prog, "uSrcSize"), float(srcW_), float(srcH_));
    glUniform4fv(glGetUniformLocation(prog, "uCrop"), 1, p + G_CROP);
    glUniform4fv(glGetUniformLocation(prog, "uGeo"), 1, p + G_GEO);
    glUniform4fv(glGetUniformLocation(prog, "uGeo2"), 1, p + G_GEO2);
}

void Engine::runAnalysis(const float *p) {
    // Key: geometry only (analysis is independent of the adjustment sliders).
    uint64_t key = 1469598103934665603ull;
    for (int i = G_CROP; i < G_GEO2 + 4; i++) {
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
    glUniform1i(glGetUniformLocation(lowres_.id, "uSrc"), 0);
    glUniform1f(glGetUniformLocation(lowres_.id, "uFlipY"), 0.f);
    setGeometryUniforms(lowres_.id, p);
    float ratio = std::max(float(ow) / lw, float(oh) / lh);
    glUniform1f(glGetUniformLocation(lowres_.id, "uLod"), std::clamp(std::log2(std::max(1.f, ratio)), 0.f, float(srcLevels_ - 1)));
    glBindFramebuffer(GL_FRAMEBUFFER, l0_.fbo);
    draw();

    auto blurPass = [&](Target &from, Target &to, bool horizontal, float step) {
        glBindFramebuffer(GL_FRAMEBUFFER, to.fbo);
        glUseProgram(blur_.id);
        glUniform1f(glGetUniformLocation(blur_.id, "uFlipY"), 0.f);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, from.tex);
        glUniform1i(glGetUniformLocation(blur_.id, "uTex"), 0);
        glUniform2f(glGetUniformLocation(blur_.id, "uStep"), horizontal ? step / lw : 0.f, horizontal ? 0.f : step / lh);
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

void Engine::runMain(const float *p, Rect vis, int pw, int ph, Target &e, int margin) {
    int ow, oh;
    outputSize(p, ow, oh);
    ensureTarget(e, pw + 2 * margin, ph + 2 * margin, GL_RGBA16F);
    glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
    glViewport(0, 0, e.w, e.h);
    glUseProgram(main_.id);
    GLuint pr = main_.id;
    struct B { GLenum target; GLuint tex; const char *name; };
    B binds[] = {
        {GL_TEXTURE_2D, srcTex_, "uSrc"}, {GL_TEXTURE_2D, bs_.tex, "uBs"}, {GL_TEXTURE_2D, bl_.tex, "uBl"},
        {GL_TEXTURE_2D, bd_.tex, "uBd"}, {GL_TEXTURE_2D, blocksTex_, "uBlocks"}, {GL_TEXTURE_2D, masksTex_, "uMasks"},
        {GL_TEXTURE_2D, curvesTex_, "uCurves"}, {GL_TEXTURE_2D_ARRAY, layersTex_, "uLayers"}, {GL_TEXTURE_2D, overlayTex_, "uOverlay"},
    };
    for (int i = 0; i < 9; i++) {
        glActiveTexture(GL_TEXTURE0 + i);
        glBindTexture(binds[i].target, binds[i].tex);
        glUniform1i(glGetUniformLocation(pr, binds[i].name), i);
    }
    float mx = vis.w * margin / pw, my = vis.h * margin / ph;
    glUniform4f(glGetUniformLocation(pr, "uView"), vis.x - mx, vis.y - my, vis.w + 2 * mx, vis.h + 2 * my);
    glUniform1f(glGetUniformLocation(pr, "uFlipY"), 0.f);
    glUniform2f(glGetUniformLocation(pr, "uOutPx"), float(e.w), float(e.h));
    float hx = vis.w * ow / pw, hy = vis.h * oh / ph;
    float bw = std::max(float(srcW_), 1.f);
    // How many source pixels land on one output pixel (oriented crop size is in source pixels already).
    float lod = std::log2(std::max(1.f, std::max(hx, hy)));
    glUniform1f(glGetUniformLocation(pr, "uLod"), std::clamp(lod, 0.f, float(srcLevels_ - 1)));
    glUniform1i(glGetUniformLocation(pr, "uNumMasks"), int(p[G_NUM_MASKS] + 0.5f));
    glUniform1i(glGetUniformLocation(pr, "uShowMask"), int(std::lround(p[G_SHOWMASK])));
    glUniform1f(glGetUniformLocation(pr, "uAspect"), float(ow) / float(oh));
    glUniform1f(glGetUniformLocation(pr, "uOverlayOn"), overlayW_ > 0 ? p[G_OVERLAY] : 0.f);
    (void)bw;
    setGeometryUniforms(pr, p);
    draw();
}

static void setOutUniforms(GLuint pr, const float *p, int pw, int ph, Rect vis, float aspect, float flip, float outLinear, int margin) {
    glUniform1i(glGetUniformLocation(pr, "uE"), 0);
    glUniform1i(glGetUniformLocation(pr, "uBase"), 1);
    glUniform2i(glGetUniformLocation(pr, "uMargin"), margin, margin);
    glUniform2f(glGetUniformLocation(pr, "uPx"), float(pw), float(ph));
    glUniform4fv(glGetUniformLocation(pr, "uDetail"), 1, p + G_DETAIL);
    glUniform2fv(glGetUniformLocation(pr, "uNr"), 1, p + G_NR);
    glUniform4fv(glGetUniformLocation(pr, "uFx"), 1, p + G_FX);
    glUniform4fv(glGetUniformLocation(pr, "uFx2"), 1, p + G_FX2);
    glUniform4f(glGetUniformLocation(pr, "uView"), vis.x, vis.y, vis.w, vis.h);
    glUniform1f(glGetUniformLocation(pr, "uAspect"), aspect);
    float fullPx = pw / std::max(vis.w, 1e-6f);
    glUniform1f(glGetUniformLocation(pr, "uPxScale"), fullPx / 1920.f);
    static float m[9];
    static bool init = false;
    if (!init) { proPhotoToSrgb(m); init = true; }
    glUniformMatrix3fv(glGetUniformLocation(pr, "uToSrgb"), 1, GL_FALSE, m);
    glUniform1f(glGetUniformLocation(pr, "uOutLinear"), outLinear);
    glUniform1f(glGetUniformLocation(pr, "uChecker"), 1.f);
    glUniform1f(glGetUniformLocation(pr, "uFlipY"), flip);
}

void Engine::renderToScreen(const float *p, int vx, int vy, int vw, int vh, Rect vis) {
    if (!hasSource() || vw < 1 || vh < 1) return;
    GLint prevFbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    uploadTables(p);
    runAnalysis(p);
    runMain(p, vis, vw, vh, e_, kMargin);
    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    glViewport(vx, vy, vw, vh);
    glUseProgram(out_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, e_.tex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, baseTex_);
    int ow, oh;
    outputSize(p, ow, oh);
    setOutUniforms(out_.id, p, vw, vh, vis, float(ow) / oh, 1.f, 0.f, kMargin);
    draw();
}

bool Engine::renderRegion(const float *p, int pw, int ph, Rect vis, uint8_t *rgba, bool linearHalfOut, uint16_t *halfOut) {
    if (!hasSource()) return false;
    uploadTables(p);
    runAnalysis(p);
    runMain(p, vis, pw, ph, e_, kMargin);
    ensureTarget(outT_, pw, ph, linearHalfOut ? GL_RGBA16F : GL_RGBA8);
    glBindFramebuffer(GL_FRAMEBUFFER, outT_.fbo);
    glViewport(0, 0, pw, ph);
    glUseProgram(out_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, e_.tex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, baseTex_);
    int ow, oh;
    outputSize(p, ow, oh);
    setOutUniforms(out_.id, p, pw, ph, vis, float(ow) / oh, 0.f, linearHalfOut ? 1.f : 0.f, kMargin);
    draw();
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    if (linearHalfOut) glReadPixels(0, 0, pw, ph, GL_RGBA, GL_HALF_FLOAT, halfOut);
    else glReadPixels(0, 0, pw, ph, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    return glGetError() == GL_NO_ERROR;
}

}  // namespace rl
