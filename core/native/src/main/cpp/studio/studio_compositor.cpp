#include "studio/studio_compositor.h"

#include <GLES3/gl32.h>

#include <algorithm>

#include "shader_sources.h"

namespace rl::studio {

namespace {
GLuint compile(GLenum type, const char *src, std::string &err) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[4096]; glGetShaderInfoLog(s, sizeof log, nullptr, log); err += log; glDeleteShader(s); return 0; }
    return s;
}
}  // namespace

bool Compositor::init(std::string &err) {
    if (ready_) return true;
    GLuint v = compile(GL_VERTEX_SHADER, SH_vert_glsl, err);
    GLuint f = compile(GL_FRAGMENT_SHADER, SH_studio_composite_frag, err);
    if (!v || !f) { if (v) glDeleteShader(v); if (f) glDeleteShader(f); return false; }
    prog_ = glCreateProgram();
    glAttachShader(prog_, v); glAttachShader(prog_, f);
    glLinkProgram(prog_);
    glDeleteShader(v); glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(prog_, GL_LINK_STATUS, &ok);
    if (!ok) { char log[4096]; glGetProgramInfoLog(prog_, sizeof log, nullptr, log); err += log; glDeleteProgram(prog_); prog_ = 0; return false; }
    GLuint sv = compile(GL_VERTEX_SHADER, SH_studio_stamp_glsl, err);
    GLuint sf = compile(GL_FRAGMENT_SHADER, SH_studio_stamp_frag, err);
    if (!sv || !sf) { if (sv) glDeleteShader(sv); if (sf) glDeleteShader(sf); return false; }
    stampProg_ = glCreateProgram();
    glAttachShader(stampProg_, sv); glAttachShader(stampProg_, sf);
    glLinkProgram(stampProg_);
    glDeleteShader(sv); glDeleteShader(sf);
    glGetProgramiv(stampProg_, GL_LINK_STATUS, &ok);
    if (!ok) { char log[4096]; glGetProgramInfoLog(stampProg_, sizeof log, nullptr, log); err += log; glDeleteProgram(stampProg_); stampProg_ = 0; return false; }
    glGenVertexArrays(1, &vao_);
    ready_ = true;
    return true;
}

void Compositor::release() {
    if (!ready_) return;
    for (Slot &s : slots_) { if (s.tex) glDeleteTextures(1, &s.tex); s = Slot(); }
    for (Target &t : ping_) freeTarget(t);
    freeTarget(resolve_);
    freeTarget(strokeBuf_);
    stroke_ = Stroke();
    glDeleteProgram(prog_); glDeleteProgram(stampProg_); glDeleteVertexArrays(1, &vao_);
    prog_ = 0; stampProg_ = 0; vao_ = 0; ready_ = false;
}

void Compositor::freeTarget(Target &t) {
    if (t.fbo) glDeleteFramebuffers(1, &t.fbo);
    if (t.tex) glDeleteTextures(1, &t.tex);
    t = Target();
}

bool Compositor::ensureTarget(Target &t, int w, int h, GLenum fmt) {
    if (t.tex && t.w == w && t.h == h && t.fmt == fmt) return true;
    freeTarget(t);
    glGenTextures(1, &t.tex);
    glBindTexture(GL_TEXTURE_2D, t.tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, fmt, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glGenFramebuffers(1, &t.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
    bool ok = glGetError() == GL_NO_ERROR && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (!ok) { freeTarget(t); return false; }
    t.w = w; t.h = h; t.fmt = fmt;
    return true;
}

bool Compositor::setLayerImage(int slot, const uint8_t *rgba, int w, int h) {
    if (!ready_ || slot < 0 || slot >= kMaxSlots || w <= 0 || h <= 0) return false;
    while (glGetError() != GL_NO_ERROR) {}
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    if (rgba) glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    if (glGetError() != GL_NO_ERROR) { glDeleteTextures(1, &t); while (glGetError() != GL_NO_ERROR) {} return false; }
    if (slots_[slot].tex) glDeleteTextures(1, &slots_[slot].tex);
    slots_[slot] = Slot{t, w, h};
    return true;
}

bool Compositor::updateLayerRegion(int slot, int x, int y, int w, int h, const uint8_t *rgba) {
    if (!ready_ || slot < 0 || slot >= kMaxSlots || !slots_[slot].tex) return false;
    if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > slots_[slot].w || y + h > slots_[slot].h) return false;
    glBindTexture(GL_TEXTURE_2D, slots_[slot].tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    return glGetError() == GL_NO_ERROR;
}

void Compositor::removeLayer(int slot) {
    if (slot < 0 || slot >= kMaxSlots || !slots_[slot].tex) return;
    glDeleteTextures(1, &slots_[slot].tex);
    slots_[slot] = Slot();
}

void Compositor::draw(const Target &dst, const Target *backdrop, const LayerDraw *l, int mode, float vx, float vy, float zoom) {
    glBindFramebuffer(GL_FRAMEBUFFER, dst.fbo);
    glViewport(0, 0, dst.w, dst.h);
    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST); glDisable(GL_CULL_FACE);
    glUseProgram(prog_);
    glUniform1f(glGetUniformLocation(prog_, "uFlipY"), 0.f);
    glUniform1i(glGetUniformLocation(prog_, "uMode"), mode);
    glUniform3f(glGetUniformLocation(prog_, "uView"), vx, vy, zoom);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, backdrop ? backdrop->tex : 0);
    glUniform1i(glGetUniformLocation(prog_, "uBackdrop"), 0);
    if (l) {
        const Slot &s = slots_[l->slot];
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, s.tex);
        glUniform1i(glGetUniformLocation(prog_, "uLayer"), 1);
        glUniform2i(glGetUniformLocation(prog_, "uLayerSize"), s.w, s.h);
        glUniform4f(glGetUniformLocation(prog_, "uRect"), l->x, l->y, s.w * l->scale, s.h * l->scale);
        glUniform1f(glGetUniformLocation(prog_, "uOpacity"), l->opacity);
        bool live = stroke_.slot == l->slot && strokeBuf_.tex;
        glUniform1i(glGetUniformLocation(prog_, "uStrokeMode"), live ? (stroke_.erase ? 2 : 1) : 0);
        if (live) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, strokeBuf_.tex);
            glUniform1i(glGetUniformLocation(prog_, "uStroke"), 2);
            glUniform3f(glGetUniformLocation(prog_, "uStrokeColor"), stroke_.rgb[0], stroke_.rgb[1], stroke_.rgb[2]);
            glUniform1f(glGetUniformLocation(prog_, "uStrokeOpacity"), stroke_.opacity);
        }
    } else {
        glUniform1i(glGetUniformLocation(prog_, "uStrokeMode"), 0);
    }
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

bool Compositor::render(const std::vector<LayerDraw> &layers, float vx, float vy, float zoom, int outW, int outH, uint8_t *out) {
    if (!ready_ || outW <= 0 || outH <= 0 || zoom <= 0) return false;
    while (glGetError() != GL_NO_ERROR) {}
    GLint prevFbo = 0, prevVp[4];
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glGetIntegerv(GL_VIEWPORT, prevVp);
    bool ok = ensureTarget(ping_[0], outW, outH, GL_RGBA16F) && ensureTarget(ping_[1], outW, outH, GL_RGBA16F) && ensureTarget(resolve_, outW, outH, GL_RGBA8);
    if (ok) {
        int cur = 0;
        draw(ping_[cur], nullptr, nullptr, -2, vx, vy, zoom);   // transparent canvas
        for (const LayerDraw &l : layers) {
            if (l.slot < 0 || l.slot >= kMaxSlots || !slots_[l.slot].tex) continue;
            draw(ping_[1 - cur], &ping_[cur], &l, l.mode, vx, vy, zoom);
            cur = 1 - cur;
        }
        draw(resolve_, &ping_[cur], nullptr, -1, vx, vy, zoom);
        glBindFramebuffer(GL_FRAMEBUFFER, resolve_.fbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, outW, outH, GL_RGBA, GL_UNSIGNED_BYTE, out);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
    return ok && glGetError() == GL_NO_ERROR;
}

bool Compositor::beginStroke(int slot, float r, float g, float b, float opacity, bool erase, float hardness, float flow) {
    if (!ready_ || slot < 0 || slot >= kMaxSlots || !slots_[slot].tex) return false;
    while (glGetError() != GL_NO_ERROR) {}
    const Slot &sl = slots_[slot];
    if (!ensureTarget(strokeBuf_, sl.w, sl.h, GL_R16F)) { stroke_ = Stroke(); return false; }
    glBindFramebuffer(GL_FRAMEBUFFER, strokeBuf_.fbo);
    glViewport(0, 0, sl.w, sl.h);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    stroke_ = Stroke{slot, {r, g, b}, opacity, erase, hardness, flow};
    return glGetError() == GL_NO_ERROR;
}

bool Compositor::addStamps(const float *xyr, int count) {
    if (!ready_ || stroke_.slot < 0 || !strokeBuf_.tex || count <= 0) return false;
    GLint prevFbo = 0, prevVp[4];
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glGetIntegerv(GL_VIEWPORT, prevVp);
    glBindFramebuffer(GL_FRAMEBUFFER, strokeBuf_.fbo);
    glViewport(0, 0, strokeBuf_.w, strokeBuf_.h);
    glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST); glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);   // a = s + a (1 - s)
    glUseProgram(stampProg_);
    glUniform2f(glGetUniformLocation(stampProg_, "uSize"), float(strokeBuf_.w), float(strokeBuf_.h));
    glUniform1f(glGetUniformLocation(stampProg_, "uHardness"), stroke_.hardness);
    glUniform1f(glGetUniformLocation(stampProg_, "uFlow"), stroke_.flow);
    glBindVertexArray(vao_);
    const GLint loc = glGetUniformLocation(stampProg_, "uStamps");
    for (int i = 0; i < count; i += 64) {
        int n = std::min(64, count - i);
        float buf[64 * 4];
        for (int k = 0; k < n; k++) { buf[k * 4] = xyr[(i + k) * 3]; buf[k * 4 + 1] = xyr[(i + k) * 3 + 1]; buf[k * 4 + 2] = xyr[(i + k) * 3 + 2]; buf[k * 4 + 3] = 0.f; }
        glUniform4fv(loc, n, buf);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, n);
    }
    glDisable(GL_BLEND);
    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
    return glGetError() == GL_NO_ERROR;
}

bool Compositor::readStroke(int x, int y, int w, int h, float *coverage) {
    if (!ready_ || !strokeBuf_.tex || x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > strokeBuf_.w || y + h > strokeBuf_.h) return false;
    GLint prevFbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, strokeBuf_.fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    std::vector<float> rgba(size_t(w) * h * 4);   // RGBA with FLOAT is the combination every driver accepts for a float attachment
    glReadPixels(x, y, w, h, GL_RGBA, GL_FLOAT, rgba.data());
    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    for (size_t i = 0; i < size_t(w) * h; i++) coverage[i] = rgba[i * 4];
    return glGetError() == GL_NO_ERROR;
}

void Compositor::endStroke() {
    freeTarget(strokeBuf_);
    stroke_ = Stroke();
}

int64_t Compositor::textureBytes() const {
    int64_t n = 0;
    for (const Slot &s : slots_) n += int64_t(s.w) * s.h * 4;
    for (const Target &t : ping_) n += int64_t(t.w) * t.h * 8;
    n += int64_t(resolve_.w) * resolve_.h * 4;
    n += int64_t(strokeBuf_.w) * strokeBuf_.h * 2;
    return n;
}

}  // namespace rl::studio
