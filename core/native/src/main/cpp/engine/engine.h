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

    /** RGBA half float pixels, row 0 = image top. Linear ProPhoto. Generates mipmaps. */
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

    /** Draws the visible region into the current framebuffer inside the given viewport. */
    void renderToScreen(const float *params, int vx, int vy, int vw, int vh, Rect vis);

    /** Renders a region into an RGBA8 buffer (top row first). Used for exports and tests. */
    bool renderRegion(const float *params, int pw, int ph, Rect vis, uint8_t *rgba, bool linearHalfOut = false, uint16_t *halfOut = nullptr);

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
    void runMain(const float *params, Rect vis, int pw, int ph, Target &e, int margin);
    void setGeometryUniforms(GLuint prog, const float *params);
    void draw();

    Prog lowres_, blur_, main_, out_;
    GLuint vao_ = 0;
    GLuint srcTex_ = 0, blocksTex_ = 0, masksTex_ = 0, curvesTex_ = 0, layersTex_ = 0, overlayTex_ = 0, baseTex_ = 0;
    Target l0_, bs_, bl_, bd_, tmp_, e_, outT_;
    int srcW_ = 0, srcH_ = 0, srcLevels_ = 1;
    int overlayW_ = 0;
    uint64_t analysisKey_ = ~0ull;
    std::vector<float> lastCurves_;
    bool ready_ = false;
    int outputSpace_ = 0;
    bool debugOutside_ = false;
};

}  // namespace rl
