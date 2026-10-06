#pragma once
#include <GLES3/gl3.h>
#include <cstdint>
#include <string>
#include <vector>

namespace rl {

struct Rect { float x, y, w, h; };

/** GPU pipeline. All methods must be called with the owning GL context current. */
class Engine {
public:
    ~Engine() { release(); }
    bool init(std::string &error);
    void release();
    /** The owning GL context is already gone: forget every GL name without calling GL (see engine.cpp). */
    void abandon();

    /** RGBA half float pixels, row 0 = image top. Linear ProPhoto. Generates mipmaps. On failure the previous source stays in place. */
    bool setSource(int w, int h, const uint16_t *rgbaHalf);
    bool hasSource() const { return srcW_ > 0; }
    int sourceW() const { return srcW_; }
    int sourceH() const { return srcH_; }

    void setLayer(int index, const uint8_t *alpha, int w, int h);
    void setOverlay(const uint8_t *rgbaHalf, int w, int h);   // whole overlay, null clears
    void updateOverlayRegion(int x, int y, int w, int h, const uint8_t *rgbaHalf);   // premultiplied RGBA half float
    void setBaseCurve(const float *lut256);

    /** Size in pixels of the final image for these params at full source resolution. */
    void outputSize(const float *params, int &w, int &h) const;

    /** Draws the visible region into the current framebuffer inside the given viewport. False when a target could not be built or GL reported an error. */
    bool renderToScreen(const float *params, int vx, int vy, int vw, int vh, Rect vis);

    /** Renders a region into an RGBA8 buffer (top row first). Used for exports and tests. Not available in high precision mode. */
    bool renderRegion(const float *params, int pw, int ph, Rect vis, uint8_t *rgba);

    /** High precision mode only: renders a region into 16 bit RGB (pw * ph * 3 values, top row first), display encoded, quantised once from float32. */
    bool renderRegion16(const float *params, int pw, int ph, Rect vis, uint16_t *rgb);

    /** Float32 intermediate and output targets plus a float32 base curve (16 bit TIFF export). Set before the first render. */
    void setHighPrecision(bool on) { hiPrec_ = on; }

    /** 0 = sRGB, 1 = Display P3. */
    void setOutputSpace(int space) { outputSpace_ = space; }

    /** Tests only: renderRegion paints pure magenta wherever a pixel falls outside the source image. */
    void setDebugOutside(bool on) { debugOutside_ = on; }

    void invalidateAnalysis() { analysisKey_ = ~0ull; }

private:
    struct Prog { GLuint id = 0; };
    struct Target { GLuint tex = 0, fbo = 0; int w = 0, h = 0; GLenum fmt = 0; };

    bool build(Prog &p, const char *vs, const char *fs, std::string &err);
    void ensureTarget(Target &t, int w, int h, GLenum internalFmt);
    void freeTarget(Target &t);
    void uploadTables(const float *params);
    void runAnalysis(const float *params);
    void runMain(const float *params, Rect vis, int pw, int ph, Target &e, int margin, GLenum fmt);
    bool drawOutput(const float *params, int pw, int ph, Rect vis);
    static void drainErrors() { int guard = 0; while (glGetError() != GL_NO_ERROR && ++guard < 16) {} }
    void setGeometryUniforms(GLuint prog, const float *params);
    void draw();

    Prog lowres_, blur_, main_, out_;
    GLuint vao_ = 0;
    GLuint srcTex_ = 0, blocksTex_ = 0, masksTex_ = 0, curvesTex_ = 0, layersTex_ = 0, overlayTex_ = 0, baseTex_ = 0, baseTex32_ = 0;
    Target l0_, bs_, bl_, bd_, tmp_, e_, outT_;
    int srcW_ = 0, srcH_ = 0, srcLevels_ = 1;
    int overlayW_ = 0;
    uint64_t analysisKey_ = ~0ull;
    std::vector<float> lastCurves_;
    bool ready_ = false;
    int outputSpace_ = 0;
    bool debugOutside_ = false;
    bool hiPrec_ = false;
    bool targetsOk_ = true;
};

}  // namespace rl
