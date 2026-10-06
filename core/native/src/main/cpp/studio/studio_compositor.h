#pragma once
#include <GLES3/gl3.h>

#include <cstdint>
#include <string>
#include <vector>

namespace rl::studio {

/** One visible layer to draw: which texture slot, where it sits on the canvas, and how it blends. Layers are given bottom to top. */
struct LayerDraw {
    int slot = 0;
    float x = 0, y = 0;       // document position of the layer's top left corner
    float scale = 1;          // 1 = one layer pixel per document pixel
    float opacity = 1;        // 0..1
    int mode = 0;             // BlendMode.id: 0 normal, 1 multiply, 2 screen
};

/**
 * GPU compositor of Studio S1 (spec 3.3, the S1 subset: one full-canvas RGBA8 texture per layer, RGBA16F ping-pong, no tile cache).
 * Everything must be called on the GL thread that owns the context. Owns no GL context itself.
 */
class Compositor {
public:
    static constexpr int kMaxSlots = 16;
    ~Compositor() { release(); }

    bool init(std::string &err);
    void release();
    /** The GL context is gone: forget every GL name without calling GL, so the new context is untouched. */
    void abandon() { ready_ = false; }

    /** Straight RGBA8, row 0 at the top. Replaces the slot's texture; on a GL error the previous image stays and false is returned. */
    bool setLayerImage(int slot, const uint8_t *rgba, int w, int h);
    /** Replaces a rectangle of an existing slot (a brush stroke's dirty rectangle). */
    bool updateLayerRegion(int slot, int x, int y, int w, int h, const uint8_t *rgba);
    void removeLayer(int slot);

    /**
     * Live stroke on one layer (spec 3.3: the stroke buffer is blended in at the layer's place in the stack). beginStroke clears an R16F
     * coverage buffer the size of the layer; addStamps draws stamps (x, y, radius triples, layer pixels) into it with `over` accumulation;
     * render() shows the layer with the stroke applied; readStroke gives the coverage of a rectangle back for the commit; endStroke frees it.
     */
    bool beginStroke(int slot, float r, float g, float b, float opacity, bool erase, float hardness, float flow);
    bool addStamps(const float *xyr, int count);
    bool readStroke(int x, int y, int w, int h, float *coverage, int bandRows = 256);
    void endStroke();
    bool stroking() const { return stroke_.slot >= 0; }

    /** Renders the view (top left vx, vy in document pixels, zoom screen pixels per document pixel) into out: outW * outH * 4 straight RGBA8, row 0 top. */
    bool render(const std::vector<LayerDraw> &layers, float vx, float vy, float zoom, int outW, int outH, uint8_t *out);

    /** Bytes of GPU memory this compositor holds (layer textures plus the ping-pong pair), for the Copy report. */
    int64_t textureBytes() const;

private:
    struct Slot { GLuint tex = 0; int w = 0, h = 0; };
    struct Target { GLuint tex = 0, fbo = 0; int w = 0, h = 0; GLenum fmt = 0; };
    struct Stroke { int slot = -1; float rgb[3] = {0, 0, 0}; float opacity = 1; bool erase = false; float hardness = 1, flow = 1; };
    bool ensureTarget(Target &t, int w, int h, GLenum fmt);
    void freeTarget(Target &t);
    void draw(const Target &dst, const Target *backdrop, const LayerDraw *l, int mode, float vx, float vy, float zoom);

    GLuint prog_ = 0, stampProg_ = 0, vert_ = 0, vao_ = 0;
    Target strokeBuf_;
    Stroke stroke_;
    Slot slots_[kMaxSlots];
    Target ping_[2], resolve_;
    bool ready_ = false;
};

}  // namespace rl::studio
