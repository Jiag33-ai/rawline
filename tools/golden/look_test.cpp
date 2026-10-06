// Host test for the two source gains the decoder reports (look versions, docs/COLOUR.md). Usage: look_test <sample.RW2>
//  - a finished picture (rawFromSrgb8) has no gain: both numbers are 1, so JPEG, HEIC and PNG draw the same under every look
//  - the real S5IIX sample: cam_mul 515, 256, 445 gives a white balance gain of 515 / 256 = 2.0117, and the frame's brightest pixel is not in the
//    (0.75, 1) band LibRaw's old rule rescales for, so the look 1 factor is 1 (which is why look 2 draws the sample as look 1 does)
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "raw_decode.h"

static int fails = 0;
static void near(const char *what, double got, double want, double tol) {
    if (std::fabs(got - want) > tol) { std::printf("FAIL %s: got %.5f want %.5f\n", what, got, want); fails++; }
    else std::printf("ok   %s %.5f\n", what, got);
}

int main(int argc, char **argv) {
    {
        std::vector<uint8_t> px(16 * 16 * 4, 200);
        RawImage img;
        rawFromSrgb8(px.data(), 16, 16, img);
        near("finished picture look 1 factor", img.v1Scale, 1.0, 0.0);
        near("finished picture look 2 gain", img.wbGain, 1.0, 0.0);
    }
    if (argc > 1) {
        for (bool half : {true, false}) {
            RawImage img; std::string err;
            if (!decodeRaw(argv[1], half, img, err)) { std::printf("FAIL decode (%s): %s\n", half ? "half" : "full", err.c_str()); fails++; continue; }
            std::string tag = half ? "sample half size " : "sample full size ";
            near((tag + "white balance gain").c_str(), img.wbGain, double(img.wbMul[0]) / double(img.wbMul[1]), 1e-4);
            near((tag + "white balance gain against 515/256").c_str(), img.wbGain, 515.0 / 256.0, 2e-3);
            near((tag + "look 1 factor").c_str(), img.v1Scale, 1.0, 1e-6);
        }
    }
    std::printf(fails ? "look_test FAILED\n" : "look_test passed\n");
    return fails ? 1 : 0;
}
