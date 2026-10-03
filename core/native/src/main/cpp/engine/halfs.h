#pragma once
#include <cstdint>
#include <cstring>

namespace rl {

inline uint16_t floatToHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = int32_t((x >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = x & 0x7FFFFFu;
    if (exp >= 31) return uint16_t(sign | 0x7C00u);
    if (exp <= 0) {
        if (exp < -10) return uint16_t(sign);
        mant |= 0x800000u;
        uint32_t shift = uint32_t(14 - exp);
        uint32_t h = mant >> shift;
        if ((mant >> (shift - 1)) & 1u) h++;
        return uint16_t(sign | h);
    }
    uint32_t h = sign | (uint32_t(exp) << 10) | (mant >> 13);
    if (mant & 0x1000u) h++;   // round to nearest
    return uint16_t(h);
}

inline float halfToFloat(uint16_t h) {
    uint32_t sign = (h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x3FFu;
    uint32_t x;
    if (exp == 0) {
        if (mant == 0) x = sign;
        else {
            exp = 1;
            while (!(mant & 0x400u)) { mant <<= 1; exp--; }
            mant &= 0x3FFu;
            x = sign | ((exp + 127 - 15) << 23) | (mant << 13);
        }
    } else if (exp == 31) x = sign | 0x7F800000u | (mant << 13);
    else x = sign | ((exp + 127 - 15) << 23) | (mant << 13);
    float f;
    std::memcpy(&f, &x, 4);
    return f;
}

}  // namespace rl
