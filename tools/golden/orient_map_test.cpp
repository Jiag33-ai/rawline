#include <cstdio>
#include <cstring>
#include "orient_map.h"
int main() {
    const char *dcraw = "50132467";   // flip for EXIF orientation (exif & 7): 0 -> 8, 1 -> 1, ..., 7 -> 7
    int fails = 0;
    for (int exif = 1; exif <= 8; exif++) {
        int flip = dcraw[exif & 7] - '0';
        int back = exifOrientationFromLibrawFlip(flip);
        if (back != exif) { std::printf("FAIL exif %d -> flip %d -> %d\n", exif, flip, back); fails++; }
    }
    if (exifOrientationFromLibrawFlip(99) != 1) { std::printf("FAIL unknown flip\n"); fails++; }
    std::printf(fails ? "FAILED\n" : "orientation map: all 8 round trip\n");
    return fails ? 1 : 0;
}
