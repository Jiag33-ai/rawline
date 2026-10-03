// Finds the largest embedded JPEG preview in a RAW/TIFF container without decoding raw data.
// Works for Panasonic RW2 (tag 0x2e), DNG/TIFF (SubIFD JPEGs, strips) and ORF-style JPEGInterchange.
#include "rw2_preview.h"

#include <unistd.h>
#include <cstring>
#include <vector>

namespace {

struct Reader {
    int fd;
    bool le = true;

    bool read(int64_t off, void *buf, size_t len) const {
        auto *p = static_cast<uint8_t *>(buf);
        size_t got = 0;
        while (got < len) {
            ssize_t r = pread(fd, p + got, len - got, off + got);
            if (r <= 0) return false;
            got += r;
        }
        return true;
    }
    uint16_t u16(const uint8_t *p) const { return le ? (p[0] | p[1] << 8) : (p[1] | p[0] << 8); }
    uint32_t u32(const uint8_t *p) const {
        return le ? (uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24)
                  : (uint32_t(p[3]) | uint32_t(p[2]) << 8 | uint32_t(p[1]) << 16 | uint32_t(p[0]) << 24);
    }
};

struct Entry {
    uint16_t tag, type;
    uint32_t count, value;  // value is the raw 4 bytes interpreted as a number (offset or inline)
};

void consider(const Reader &r, int64_t off, int64_t len, PreviewInfo &best) {
    if (len < 1024 || len <= best.length) return;
    uint8_t m[2];
    if (!r.read(off, m, 2) || m[0] != 0xFF || m[1] != 0xD8) return;
    best.offset = off;
    best.length = len;
}

void walkIfd(const Reader &r, int64_t ifdOff, int depth, bool isIfd0, PreviewInfo &best) {
    if (depth > 3 || ifdOff <= 0) return;
    uint8_t cnt[2];
    if (!r.read(ifdOff, cnt, 2)) return;
    int n = r.u16(cnt);
    if (n <= 0 || n > 512) return;
    std::vector<uint8_t> buf(n * 12 + 4);
    if (!r.read(ifdOff + 2, buf.data(), buf.size())) return;

    int64_t jifOff = -1, jifLen = -1, stripOff = -1, stripLen = -1;
    int compression = 0;
    for (int i = 0; i < n; i++) {
        const uint8_t *e = &buf[i * 12];
        Entry en{r.u16(e), r.u16(e + 2), r.u32(e + 4), r.u32(e + 8)};
        // For SHORT values stored inline, the value is in the first two bytes.
        uint32_t shortVal = r.u16(e + 8);
        switch (en.tag) {
            case 0x112:
                if (isIfd0) best.orientation = shortVal;
                break;
            case 0x2e:  // Panasonic JpgFromRaw: count = byte length, value = file offset
                consider(r, en.value, en.count, best);
                break;
            case 0x103: compression = shortVal; break;
            case 0x111: if (en.count == 1) stripOff = en.type == 3 ? shortVal : en.value; break;
            case 0x117: if (en.count == 1) stripLen = en.type == 3 ? shortVal : en.value; break;
            case 0x201: jifOff = en.value; break;
            case 0x202: jifLen = en.value; break;
            case 0x14a: {  // SubIFDs
                std::vector<uint32_t> subs;
                if (en.count == 1) subs.push_back(en.value);
                else if (en.count < 16) {
                    std::vector<uint8_t> sb(en.count * 4);
                    if (r.read(en.value, sb.data(), sb.size()))
                        for (uint32_t k = 0; k < en.count; k++) subs.push_back(r.u32(&sb[k * 4]));
                }
                for (uint32_t s : subs) walkIfd(r, s, depth + 1, false, best);
                break;
            }
            default: break;
        }
    }
    if (jifOff > 0 && jifLen > 0) consider(r, jifOff, jifLen, best);
    if ((compression == 6 || compression == 7) && stripOff > 0 && stripLen > 0)
        consider(r, stripOff, stripLen, best);

    uint32_t next = r.u32(&buf[n * 12]);
    if (isIfd0 && next) walkIfd(r, next, depth + 1, false, best);  // IFD1 often holds a thumbnail
}

}  // namespace

bool findEmbeddedPreview(int fd, PreviewInfo &out) {
    Reader r{fd};
    uint8_t h[8];
    if (!r.read(0, h, 8)) return false;
    if (h[0] == 'I' && h[1] == 'I') r.le = true;
    else if (h[0] == 'M' && h[1] == 'M') r.le = false;
    else return false;
    out = PreviewInfo{};
    walkIfd(r, r.u32(h + 4), 0, true, out);
    return out.length > 0;
}

int64_t readFully(int fd, int64_t off, uint8_t *dst, int64_t len) {
    int64_t got = 0;
    while (got < len) {
        ssize_t r = pread(fd, dst + got, len - got, off + got);
        if (r <= 0) break;
        got += r;
    }
    return got;
}
