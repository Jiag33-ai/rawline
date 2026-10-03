#include "raw_decode.h"

#include <libraw/libraw.h>

#include <algorithm>
#include <cmath>
#include <thread>

#include "engine/halfs.h"

bool decodeRaw(const std::string &path, bool halfSize, RawImage &out, std::string &err) {
    LibRaw lr;
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

    int r = lr.open_file(path.c_str());
    if (r != LIBRAW_SUCCESS) { err = std::string("open: ") + libraw_strerror(r); return false; }
    r = lr.unpack();
    if (r != LIBRAW_SUCCESS) { err = std::string("unpack: ") + libraw_strerror(r); return false; }
    r = lr.dcraw_process();
    if (r != LIBRAW_SUCCESS) { err = std::string("process: ") + libraw_strerror(r); return false; }

    int w = lr.imgdata.sizes.iwidth, h = lr.imgdata.sizes.iheight;
    libraw_processed_image_t *dummy = nullptr; (void)dummy;
    const ushort(*img)[4] = lr.imgdata.image;
    if (!img || w <= 0 || h <= 0) { err = "no image"; return false; }

    out.width = w;
    out.height = h;
    out.half.assign(size_t(w) * h * 4, 0);
    const float inv = 1.0f / 65535.0f;
    uint16_t one = rl::floatToHalf(1.0f);
    unsigned nt = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    std::vector<std::thread> pool;
    for (unsigned t = 0; t < nt; t++) {
        pool.emplace_back([&, t] {
            size_t begin = size_t(h) * t / nt, end = size_t(h) * (t + 1) / nt;
            for (size_t y = begin; y < end; y++) {
                const ushort(*src)[4] = img + y * w;
                uint16_t *dst = out.half.data() + y * w * 4;
                for (int x = 0; x < w; x++) {
                    dst[x * 4 + 0] = rl::floatToHalf(src[x][0] * inv);
                    dst[x * 4 + 1] = rl::floatToHalf(src[x][1] * inv);
                    dst[x * 4 + 2] = rl::floatToHalf(src[x][2] * inv);
                    dst[x * 4 + 3] = one;
                }
            }
        });
    }
    for (auto &th : pool) th.join();

    int flip = lr.imgdata.sizes.flip;
    out.orientation = flip == 3 ? 3 : flip == 5 ? 8 : flip == 6 ? 6 : 1;
    out.camera = std::string(lr.imgdata.idata.make) + " " + lr.imgdata.idata.model;
    for (int i = 0; i < 4; i++) out.wbMul[i] = lr.imgdata.color.cam_mul[i];
    return true;
}

void rawFromSrgb8(const uint8_t *rgba, int w, int h, RawImage &out) {
    // sRGB (D65) -> linear -> XYZ D50 (Bradford) -> ProPhoto
    static const float m[9] = {0.5292f, 0.3301f, 0.1407f, 0.0985f, 0.8734f, 0.0281f, 0.0166f, 0.1182f, 0.8652f};
    static float lut[256];
    static bool init = false;
    if (!init) {
        for (int i = 0; i < 256; i++) {
            float v = i / 255.0f;
            lut[i] = v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
        }
        init = true;
    }
    out.width = w; out.height = h; out.orientation = 1;
    out.half.assign(size_t(w) * h * 4, 0);
    uint16_t one = rl::floatToHalf(1.0f);
    for (size_t i = 0; i < size_t(w) * h; i++) {
        float r = lut[rgba[i * 4]], g = lut[rgba[i * 4 + 1]], b = lut[rgba[i * 4 + 2]];
        out.half[i * 4 + 0] = rl::floatToHalf(m[0] * r + m[1] * g + m[2] * b);
        out.half[i * 4 + 1] = rl::floatToHalf(m[3] * r + m[4] * g + m[5] * b);
        out.half[i * 4 + 2] = rl::floatToHalf(m[6] * r + m[7] * g + m[8] * b);
        out.half[i * 4 + 3] = one;
    }
}
