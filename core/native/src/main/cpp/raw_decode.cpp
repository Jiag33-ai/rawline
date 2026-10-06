#include "raw_decode.h"

#include <libraw/libraw.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <thread>
#include <vector>

#include "engine/halfs.h"

namespace {
// Runs fn(beginRow, endRow) on up to four threads.
template <class F> void parallelRows(int h, F fn) {
    unsigned nt = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    std::vector<std::thread> pool;
    for (unsigned t = 0; t < nt; t++) {
        size_t begin = size_t(h) * t / nt, end = size_t(h) * (t + 1) / nt;
        pool.emplace_back([=] { fn(begin, end); });
    }
    for (auto &th : pool) th.join();
}
}  // namespace

bool decodeRaw(const std::string &path, bool halfSize, RawImage &out, std::string &err) {
    // The LibRaw instance is kept alive inside the result: its pixel block is converted in place and 'out.half' refers to it. (LibRaw
    // tracks that block in its own allocator, so it cannot be taken out of the instance, and it is freed with it.)
    std::shared_ptr<LibRaw> keep(new LibRaw());
    LibRaw &lr = *keep;
    auto &P = lr.imgdata.params;
    P.use_camera_wb = 1;
    P.use_auto_wb = 0;
    P.no_auto_bright = 1;
    P.output_bps = 16;
    P.gamm[0] = P.gamm[1] = 1.0;
    P.output_color = 4;       // ProPhoto
    P.highlight = 2;          // blend clipped highlights
    P.half_size = halfSize ? 1 : 0;
    P.user_qual = 3;          // AHD
    P.med_passes = 0;
    P.fbdd_noiserd = 0;
    P.adjust_maximum_thr = 0.f;   // look version 2: the frame's brightest pixel must not move the white point (the v1 factor is reported in out.v1Scale)

    int r = lr.open_file(path.c_str());
    if (r != LIBRAW_SUCCESS) { err = std::string("open: ") + libraw_strerror(r); return false; }
    r = lr.unpack();
    if (r != LIBRAW_SUCCESS) { err = std::string("unpack: ") + libraw_strerror(r); return false; }
    r = lr.dcraw_process();
    if (r != LIBRAW_SUCCESS) { err = std::string("process: ") + libraw_strerror(r); return false; }

    int w = lr.imgdata.sizes.iwidth, h = lr.imgdata.sizes.iheight;
    ushort(*img)[4] = lr.imgdata.image;
    if (!img || w <= 0 || h <= 0) { err = "no image"; return false; }

    int flip = lr.imgdata.sizes.flip;
    out.orientation = flip == 3 ? 3 : flip == 5 ? 8 : flip == 6 ? 6 : 1;
    out.camera = std::string(lr.imgdata.idata.make) + " " + lr.imgdata.idata.model;
    for (int i = 0; i < 4; i++) out.wbMul[i] = lr.imgdata.color.cam_mul[i];
    {   // LibRaw's own rule for the white point it would have lowered (adjust_maximum, default threshold 0.75), evaluated on the unscaled decode
        const auto &C = lr.imgdata.color;
        float mx = float(C.maximum), dm = float(C.data_maximum);
        out.v1Scale = (dm > 0.f && dm < mx && dm > mx * 0.75f) ? mx / dm : 1.f;
        float lo = std::min(C.cam_mul[0], std::min(C.cam_mul[1], C.cam_mul[2])), hi = std::max(C.cam_mul[0], std::max(C.cam_mul[1], C.cam_mul[2]));
        out.wbGain = (lo > 0.f) ? hi / lo : 1.f;   // pre_mul is normalised by its largest member, so a neutral lands at min/max of cam_mul: this brings it back to 1
    }
    out.width = w;
    out.height = h;
    out.owner = keep;
    out.half.view(reinterpret_cast<uint16_t *>(img), size_t(w) * h * 4);

    const float inv = 1.0f / 65535.0f;
    const uint16_t one = rl::floatToHalf(1.0f);
    uint16_t *px = out.half.data();
    parallelRows(h, [&](size_t begin, size_t end) {
        for (size_t y = begin; y < end; y++) {
            uint16_t *p = px + y * size_t(w) * 4;
            for (int x = 0; x < w; x++, p += 4) {
                // each 16 bit value becomes a half in the same two bytes; the fourth value (LibRaw's second green) becomes alpha 1
                p[0] = rl::floatToHalf(p[0] * inv);
                p[1] = rl::floatToHalf(p[1] * inv);
                p[2] = rl::floatToHalf(p[2] * inv);
                p[3] = one;
            }
        }
    });
    return true;
}

void rawFromSrgb8(const uint8_t *rgba, int w, int h, RawImage &out, size_t strideBytes) {
    // sRGB (D65) -> linear -> XYZ D50 (Bradford) -> ProPhoto
    static const float m[9] = {0.5292f, 0.3301f, 0.1407f, 0.0985f, 0.8734f, 0.0281f, 0.0166f, 0.1182f, 0.8652f};
    static const std::array<float, 256> lut = [] {   // built once, safe from any thread
        std::array<float, 256> t{};
        for (int i = 0; i < 256; i++) {
            float v = i / 255.0f;
            t[i] = v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
        }
        return t;
    }();
    if (strideBytes == 0) strideBytes = size_t(w) * 4;
    out.width = w; out.height = h; out.orientation = 1;
    out.half.allocate(size_t(w) * h * 4);
    const uint16_t one = rl::floatToHalf(1.0f);
    uint16_t *dst = out.half.data();
    parallelRows(h, [&](size_t begin, size_t end) {
        for (size_t y = begin; y < end; y++) {
            const uint8_t *src = rgba + y * strideBytes;
            uint16_t *d = dst + y * size_t(w) * 4;
            for (int x = 0; x < w; x++, src += 4, d += 4) {
                float r = lut[src[0]], g = lut[src[1]], b = lut[src[2]];
                d[0] = rl::floatToHalf(m[0] * r + m[1] * g + m[2] * b);
                d[1] = rl::floatToHalf(m[3] * r + m[4] * g + m[5] * b);
                d[2] = rl::floatToHalf(m[6] * r + m[7] * g + m[8] * b);
                d[3] = one;
            }
        }
    });
}
