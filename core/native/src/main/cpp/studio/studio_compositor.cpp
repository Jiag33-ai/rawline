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
    glGenVertexArrays(1, &vao_);
    ready_ = true;
    return true;
}

void Compositor::release() {
    if (!ready_) return;
    for (Slot &s : slots_) { if (s.tex) glDeleteTextures(1, &s.tex); s = Slot(); }
    for (Target &t : ping_) freeTarget(t);
    freeTarget(resolve_);
    glDeleteProgram(prog_); glDeleteVertexArrays(1, &vao_);
    prog_ = 0; vao_ = 0; ready_ = false;
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

int64_t Compositor::textureBytes() const {
    int64_t n = 0;
    for (const Slot &s : slots_) n += int64_t(s.w) * s.h * 4;
    for (const Target &t : ping_) n += int64_t(t.w) * t.h * 8;
    n += int64_t(resolve_.w) * resolve_.h * 4;
    return n;
}

}  // namespace rl::studio
