#pragma once
// LibRaw's sizes.flip (dcraw's bit field: 1 mirror left to right, 2 mirror top to bottom, 4 transpose) to the EXIF orientation 1..8 the engine
// understands. dcraw builds flip from EXIF with flip = "50132467"[exif & 7] - '0'; this is its inverse.
inline int exifOrientationFromLibrawFlip(int flip) {
    switch (flip) {
        case 0: return 1;
        case 1: return 2;   // mirror horizontal
        case 3: return 3;   // rotate 180
        case 2: return 4;   // mirror vertical
        case 4: return 5;   // transpose
        case 6: return 6;   // rotate 90 clockwise
        case 7: return 7;   // transverse
        case 5: return 8;   // rotate 90 counter clockwise
        default: return 1;
    }
}
