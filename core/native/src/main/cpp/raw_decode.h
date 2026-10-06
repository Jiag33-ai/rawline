#pragma once
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <new>
#include <string>

/**
 * A block of half float pixels (RGBA, 4 values per pixel). It either owns a calloc block (pictures converted from a bitmap) or is a
 * view of memory somebody else keeps alive (the pixel block LibRaw allocated, converted in place: see RawImage::owner), so decoding
 * a raw never builds a second full size copy.
 */
class HalfBuffer {
public:
    HalfBuffer() = default;
    HalfBuffer(const HalfBuffer &) = delete;
    HalfBuffer &operator=(const HalfBuffer &) = delete;
    ~HalfBuffer() { if (owns_) std::free(p_); }

    /** Zeroed block of n values, owned. Throws std::bad_alloc. */
    void allocate(size_t n) {
        if (owns_) std::free(p_);
        p_ = nullptr; n_ = 0; owns_ = true;
        p_ = static_cast<uint16_t *>(std::calloc(n ? n : 1, sizeof(uint16_t)));
        if (!p_) throw std::bad_alloc();
        n_ = n;
    }
    /** Refers to n values somebody else owns and keeps alive for as long as this buffer is used. */
    void view(uint16_t *p, size_t n) { if (owns_) std::free(p_); p_ = p; n_ = n; owns_ = false; }

    uint16_t *data() { return p_; }
    const uint16_t *data() const { return p_; }
    size_t size() const { return n_; }
    uint16_t &operator[](size_t i) { return p_[i]; }
    const uint16_t &operator[](size_t i) const { return p_[i]; }

private:
    uint16_t *p_ = nullptr;
    size_t n_ = 0;
    bool owns_ = true;
};

struct RawImage {
    int width = 0, height = 0;
    int orientation = 1;          // TIFF orientation of the stored data
    std::shared_ptr<void> owner;  // keeps the memory 'half' refers to alive (the LibRaw instance for a decoded raw); declared before 'half'
    HalfBuffer half;              // RGBA half float, linear ProPhoto, row 0 = top
    std::string camera;
    float wbMul[4] = {1, 1, 1, 1};
    float v1Scale = 1.f;   // factor LibRaw's default white point rule would have applied (look version 1)
    float wbGain = 1.f;    // brings a neutral back to the level it has at unity white balance (look version 2)
};

/**
 * Decodes with LibRaw: camera white balance, linear, ProPhoto primaries. halfSize skips demosaic (2x2 binning).
 * LibRaw's own 16 bit pixel block is converted to half float in place (4 values of 2 bytes either way), so the peak is about
 * 10 bytes per pixel (pixels plus sensor data) instead of 18, and 'half' stays a view of it for as long as the RawImage lives.
 */
bool decodeRaw(const std::string &path, bool halfSize, RawImage &out, std::string &err);

/** Converts an 8 bit sRGB RGBA bitmap (JPEG, HEIC, PNG) into the working space so it can be edited like a raw. strideBytes 0 = tightly packed. */
void rawFromSrgb8(const uint8_t *rgba, int w, int h, RawImage &out, size_t strideBytes = 0);
