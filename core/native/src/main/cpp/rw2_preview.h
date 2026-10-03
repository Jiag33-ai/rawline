#pragma once
#include <cstdint>

struct PreviewInfo {
    int64_t offset = 0;
    int64_t length = 0;
    int orientation = 1;  // TIFF orientation from IFD0
};

bool findEmbeddedPreview(int fd, PreviewInfo &out);
int64_t readFully(int fd, int64_t off, uint8_t *dst, int64_t len);
