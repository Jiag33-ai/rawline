#pragma once
#include <vector>
#include <cstring>
// Flat parameter array layout shared with Kotlin (core/render/RenderParams.kt). Keep both in sync.
namespace rl {

constexpr int kBlockTexels = 20;                 // vec4 texels per adjustment block
constexpr int kBlockFloats = kBlockTexels * 4;
constexpr int kMaxMasks = 8;
constexpr int kMaxBlocks = 1 + kMaxMasks;        // block 0 is global
constexpr int kMaskTexels = 32;
constexpr int kMaskFloats = kMaskTexels * 4;
constexpr int kMaxLayers = 16;
constexpr int kLayerSize = 2048;
constexpr int kCurveSize = 256;
constexpr int kCurveRows = kMaxBlocks * 4;

// Global float slots
enum Global {
    G_CROP = 0,        // 4: x y w h (normalised, oriented base image)
    G_GEO = 4,         // 4: straighten (rad), flipH, flipV, rot90
    G_GEO2 = 8,        // 4: keystone v, keystone h, distortion, manual vignette
    G_DETAIL = 12,     // 4: sharpen amount, radius, detail, masking
    G_NR = 16,         // 2: luminance, colour  (+2 spare)
    G_FX = 20,         // 4: vignette amount, midpoint, roundness, feather
    G_FX2 = 24,        // 4: grain amount, size, roughness, seed
    G_NUM_MASKS = 28,  // 1
    G_OVERLAY = 29,    // 1
    G_SHOWMASK = 30,   // 1: mask index to tint red for editing, -1 off
    G_LDIST = 32,      // 5: lens distortion polynomial p0..p4 (r_src = r * (p0 + p1 r + p2 r^2 + p3 r^3 + p4 r^4))
    G_LDIST_ON = 37,   // 1
    G_LTCA = 38,       // 6: red (v, c, b), blue (v, c, b) scale polynomials about the source centre
    G_LTCA_ON = 44,    // 1
    G_LVIG = 45,       // 3: vignetting k1 k2 k3 (lensfun 'pa')
    G_LVIG_ON = 48,    // 1
    G_COUNT = 56
};

constexpr int kOffBlocks = G_COUNT;
constexpr int kOffMasks = kOffBlocks + kMaxBlocks * kBlockFloats;
constexpr int kOffCurves = kOffMasks + kMaxMasks * kMaskFloats;
constexpr int kParamFloats = kOffCurves + kCurveRows * kCurveSize;

// Float offsets inside one adjustment block
enum BlockSlot {
    S_EXPOSURE = 0, S_CONTRAST = 1, S_HIGHLIGHTS = 2, S_SHADOWS = 3,
    S_WHITES = 4, S_BLACKS = 5, S_TEMP = 6, S_TINT = 7,
    S_VIBRANCE = 8, S_SATURATION = 9, S_TEXTURE = 10, S_CLARITY = 11,
    S_DEHAZE = 12,
    S_MIX_HUE = 16, S_MIX_SAT = 24, S_MIX_LUM = 32,      // 8 each
    S_GRADE_SHADOWS = 40, S_GRADE_MID = 44, S_GRADE_HIGH = 48, S_GRADE_GLOBAL = 52,  // hue 0..1, sat, lum
    S_GRADE_BLEND = 56, S_GRADE_BALANCE = 57,
    S_CURVE_FLAGS = 60                                    // master, r, g, b
};


inline void initDefaultParams(float *p) {
    std::memset(p, 0, sizeof(float) * kParamFloats);
    p[G_CROP + 2] = 1.f; p[G_CROP + 3] = 1.f;
    for (int b = 0; b < kMaxBlocks; b++) p[kOffBlocks + b * kBlockFloats + S_GRADE_BLEND] = 0.5f;
    for (int r = 0; r < kCurveRows; r++)
        for (int i = 0; i < kCurveSize; i++) p[kOffCurves + r * kCurveSize + i] = i / 255.f;
    p[G_DETAIL + 1] = 1.f;
    p[G_SHOWMASK] = -1.f;
}

}  // namespace rl
