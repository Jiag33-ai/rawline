// Host test for the embedded preview finder (rw2_preview.cpp): TIFF containers built in memory, plus the real sample RAW.
//   AE-039  an IFD that ends exactly at the end of the file (no next-IFD offset) still yields its preview
//   AE-053  oversized claims and ranges that do not end like a JPEG are refused
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "rw2_preview.h"

static int fails = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("FAIL " __VA_ARGS__); std::printf("\n"); fails++; } } while (0)

static void le16(std::vector<uint8_t> &b, uint16_t v) { b.push_back(v & 255); b.push_back(v >> 8); }
static void le32(std::vector<uint8_t> &b, uint32_t v) { for (int i = 0; i < 4; i++) b.push_back((v >> (8 * i)) & 255); }

// Little endian TIFF: header, a JPEG blob, then IFD0 with a Panasonic style JpgFromRaw entry (tag 0x2e). withNext: write the 4 byte next-IFD offset.
static std::string build(size_t jpegLen, bool eoi, bool withNext, size_t padAfterJpeg = 0, uint32_t claimedLen = 0) {
    std::vector<uint8_t> f = {'I', 'I', 42, 0};
    le32(f, uint32_t(8 + jpegLen + padAfterJpeg));   // IFD0 offset
    std::vector<uint8_t> jpg(jpegLen, 0x11);
    jpg[0] = 0xFF; jpg[1] = 0xD8;
    if (eoi) { jpg[jpegLen - 2] = 0xFF; jpg[jpegLen - 1] = 0xD9; }
    f.insert(f.end(), jpg.begin(), jpg.end());
    f.insert(f.end(), padAfterJpeg, 0);
    le16(f, 1);                                      // one entry
    le16(f, 0x2e); le16(f, 7); le32(f, claimedLen ? claimedLen : uint32_t(jpegLen)); le32(f, 8);
    if (withNext) le32(f, 0);
    char path[] = "/tmp/preview_test_XXXXXX";
    int fd = mkstemp(path);
    if (fd < 0) { std::perror("mkstemp"); std::exit(2); }
    if (write(fd, f.data(), f.size()) != ssize_t(f.size())) std::exit(2);
    close(fd);
    return path;
}

static bool find(const std::string &path, PreviewInfo &out) {
    int fd = open(path.c_str(), O_RDONLY);
    bool ok = findEmbeddedPreview(fd, out);
    close(fd);
    unlink(path.c_str());
    return ok;
}

int main(int argc, char **argv) {
    PreviewInfo p;
    CHECK(find(build(4096, true, true), p) && p.offset == 8 && p.length == 4096, "ordinary file with a next-IFD offset");
    CHECK(find(build(4096, true, false), p) && p.offset == 8 && p.length == 4096, "IFD ending exactly at the end of the file (AE-039)");
    CHECK(find(build(4096, true, true, 600), p) && p.length == 4096, "padding after the JPEG is fine");
    CHECK(!find(build(4096, false, true), p), "a range without an end of image marker is refused (AE-053)");
    CHECK(find(build(3000, true, true), p) && p.length == 3000, "EOI at the very end");
    // a claim of 40 MB inside a sparse 41 MB file: the old limit was 128 MB, the preview is refused now
    {
        std::string path = build(2048, true, true, 0, 40u << 20);
        CHECK(truncate(path.c_str(), 41 << 20) == 0, "truncate");
        CHECK(!find(path, p), "40 MB preview claim accepted");
    }
    // a 31 MB range is within the limit when it really is a JPEG
    {
        size_t len = 31u << 20;
        std::string path = build(len, true, true);
        CHECK(find(path, p) && p.length == int64_t(len), "31 MB preview refused");
    }
    // the real sample file must still give its preview (megabytes, JPEG framed)
    if (argc > 1) {
        int fd = open(argv[1], O_RDONLY);
        bool ok = fd >= 0 && findEmbeddedPreview(fd, p);
        CHECK(ok && p.length > (256 << 10) && p.length <= (32 << 20), "sample RAW preview: ok=%d length=%lld", ok, (long long)p.length);
        if (ok) {
            uint8_t b[2] = {0, 0};
            CHECK(pread(fd, b, 2, p.offset) == 2 && b[0] == 0xFF && b[1] == 0xD8, "sample preview does not start with a JPEG marker");
        }
        if (fd >= 0) close(fd);
    }
    if (fails == 0) std::printf("ok   preview finder: IFD at end of file, size cap, JPEG framing, sample RAW\n");
    return fails ? 1 : 0;
}
