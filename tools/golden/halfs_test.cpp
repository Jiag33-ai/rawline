// Host test for engine/halfs.h (the float <-> half conversion every upload goes through).
// Cases come from the engine audit (AE-028): 65520..65535 used to round up to Infinity.
#include <cmath>
#include <cstdio>
#include <cstring>

#include "engine/halfs.h"

static int fails = 0;
static void expect(const char *what, unsigned got, unsigned want) {
    if (got != want) { std::printf("FAIL %s: got 0x%04X want 0x%04X\n", what, got, want); fails++; }
}

int main() {
    using rl::floatToHalf;
    using rl::halfToFloat;
    // Largest finite half is 65504 (0x7BFF). Everything that would round to 65536 or more must clamp there, never become Inf.
    expect("65504", floatToHalf(65504.f), 0x7BFF);
    expect("65519", floatToHalf(65519.f), 0x7BFF);
    expect("65520", floatToHalf(65520.f), 0x7BFF);
    expect("65535", floatToHalf(65535.f), 0x7BFF);
    expect("65536", floatToHalf(65536.f), 0x7BFF);
    expect("-65520", floatToHalf(-65520.f), 0xFBFF);
    expect("-65535", floatToHalf(-65535.f), 0xFBFF);
    expect("1e30", floatToHalf(1e30f), 0x7BFF);
    expect("+inf", floatToHalf(INFINITY), 0x7BFF);
    expect("-inf", floatToHalf(-INFINITY), 0xFBFF);
    expect("nan", floatToHalf(NAN), 0);
    // ordinary values
    expect("0", floatToHalf(0.f), 0);
    expect("1", floatToHalf(1.f), 0x3C00);
    expect("-2", floatToHalf(-2.f), 0xC000);
    expect("0.5", floatToHalf(0.5f), 0x3800);
    expect("smallest normal", floatToHalf(6.103515625e-05f), 0x0400);
    expect("subnormal", floatToHalf(5.9604645e-08f), 0x0001);
    expect("underflow", floatToHalf(1e-9f), 0);
    // Every finite half survives a round trip exactly, and no float result is ever Inf or NaN for a finite input.
    for (unsigned h = 0; h < 0x10000; h++) {
        unsigned e = (h >> 10) & 0x1F;
        if (e == 31) continue;
        float f = halfToFloat(uint16_t(h));
        unsigned back = floatToHalf(f);
        if (h == 0x8000) { if (back != 0x8000 && back != 0) { std::printf("FAIL -0 round trip 0x%04X\n", back); fails++; } continue; }
        if (back != h) { std::printf("FAIL round trip 0x%04X -> %g -> 0x%04X\n", h, f, back); fails++; if (fails > 20) return 1; }
    }
    // Sweep across the top binade and a few others: result must be finite and within half a step of the input (or clamped).
    for (float f = 32768.f; f <= 70000.f; f += 7.f) {
        unsigned h = floatToHalf(f);
        if (((h >> 10) & 0x1F) == 31) { std::printf("FAIL %g became Inf/NaN (0x%04X)\n", f, h); fails++; break; }
        float back = halfToFloat(uint16_t(h));
        if (f <= 65504.f && std::fabs(back - f) > 16.f) { std::printf("FAIL %g -> %g\n", f, back); fails++; break; }
    }
    if (fails == 0) std::printf("ok   halfs: all cases pass\n");
    return fails ? 1 : 0;
}
