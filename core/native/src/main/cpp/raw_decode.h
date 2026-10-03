#pragma once
#include <cstdint>
#include <string>
#include <vector>

struct RawImage {
    int width = 0, height = 0;
    int orientation = 1;          // TIFF orientation of the stored data
    std::vector<uint16_t> half;   // RGBA half float, linear ProPhoto, row 0 = top
    std::string camera;
    float wbMul[4] = {1, 1, 1, 1};
};

/** Decodes with LibRaw: camera white balance, linear, ProPhoto primaries. halfSize skips demosaic (2x2 binning). */
bool decodeRaw(const std::string &path, bool halfSize, RawImage &out, std::string &err);

/** Converts an 8 bit sRGB RGBA bitmap (JPEG, HEIC, PNG) into the working space so it can be edited like a raw. */
void rawFromSrgb8(const uint8_t *rgba, int w, int h, RawImage &out);
