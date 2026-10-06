"""Numeric checks on golden renders (PPM files). Each command prints 'ok ...' or 'FAIL ...' and sets the exit status.
These do not depend on a stored reference: they test a property of the render (or a difference between two renders)."""
import sys
from PIL import Image


def load(path):
    return Image.open(path).convert("RGB")


def pixels(im):
    return im.get_flattened_data() if hasattr(im, "get_flattened_data") else pixels(im)


def luma_values(im):
    return [(54 * r + 183 * g + 19 * b) / 256.0 for r, g, b in pixels(im)]


def percentile(vals, q):
    s = sorted(vals)
    return s[min(len(s) - 1, int(q * len(s)))]


def stddev(vals):
    m = sum(vals) / len(vals)
    return (sum((v - m) ** 2 for v in vals) / len(vals)) ** 0.5


def fail(msg):
    print("FAIL", msg)
    sys.exit(1)


def cmd_dehaze(base, neg):
    """Negative dehaze adds haze: blacks lift towards the airlight and contrast falls. (The old formula crushed blacks and raised contrast.)"""
    a, b = luma_values(load(base)), luma_values(load(neg))
    p1a, p1b = percentile(a, 0.01), percentile(b, 0.01)
    sa, sb = stddev(a), stddev(b)
    msg = "dehaze -60: 1st percentile luma %.1f -> %.1f, contrast (std) %.1f -> %.1f" % (p1a, p1b, sa, sb)
    if not (p1b >= p1a + 4.0 and sb < sa * 0.98):
        fail(msg + " (blacks must lift by 4+ levels and contrast must fall)")
    print("ok  ", msg)


def cmd_maxdiff(a, b, limit, what):
    ia, ib = load(a), load(b)
    if ia.size != ib.size:
        fail("%s: size differs" % what)
    worst = max(abs(x - y) for pa, pb in zip(pixels(ia), pixels(ib)) for x, y in zip(pa, pb))
    msg = "%s: largest difference %d levels (limit %s)" % (what, worst, limit)
    if worst > float(limit):
        fail(msg)
    print("ok  ", msg)


def cmd_pixel(path, x, y):
    """Prints the display colour of one pixel as 'r,g,b' (0..1)."""
    r, g, b = load(path).getpixel((int(x), int(y)))
    print("%.5f,%.5f,%.5f" % (r / 255.0, g / 255.0, b / 255.0))


def cmd_lumapixel(path, x, y):
    r, g, b = load(path).getpixel((int(x), int(y)))
    print("%.5f" % ((0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0))


def cmd_pixelmatch(masked, global_, base, x, y, what):
    """With a mask that selects the picked pixel fully, that pixel must equal the same edit applied globally, and differ from the unedited one."""
    m, g, o = (load(p).getpixel((int(x), int(y))) for p in (masked, global_, base))
    dm = max(abs(i - j) for i, j in zip(m, g))
    dglobal = max(abs(i - j) for i, j in zip(g, o))
    msg = "%s: pixel %s, same edit applied globally %s, unedited %s" % (what, m, g, o)
    if dglobal < 20:
        fail(msg + " (test pixel barely changes with the edit, the check is blind)")
    if dm > 3:
        fail(msg + " (mask does not select its own picked value)")
    print("ok  ", msg)


def cmd_gradeluma(base, graded):
    """A colour grade tint must not change brightness: mean luma stays within 6 levels (of 255; the old hue offset moved it by about 47)."""
    a, b = luma_values(load(base)), luma_values(load(graded))
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    msg = "grade tint: mean luma %.1f -> %.1f" % (ma, mb)
    # the tint must be visible (colour changes), or the check is blind
    ia, ib = load(base), load(graded)
    chroma = sum(abs(p[0] - p[2]) - abs(q[0] - q[2]) for p, q in zip(pixels(ia), pixels(ib))) / (ia.size[0] * ia.size[1])
    if abs(chroma) < 5:
        fail(msg + " (tint barely changes colour, the check is blind)")
    if abs(mb - ma) > 6.0:
        fail(msg + " (tint shifted brightness)")
    print("ok  ", msg + ", colour shift %.1f" % chroma)


def cmd_edges(path, expect):
    """Mean luma of the leftmost and rightmost 6 columns. expect = 'left' (left edge brighter) or 'symmetric'."""
    im = load(path)
    w, h = im.size
    def col(x0, x1):
        vals = [(54 * r + 183 * g + 19 * b) / 256.0 for x in range(x0, x1) for y in range(h) for r, g, b in [im.getpixel((x, y))]]
        return sum(vals) / len(vals)
    left, right = col(0, 6), col(w - 6, w)
    msg = "manual vignette on a cropped flat picture: left edge luma %.1f, right edge luma %.1f" % (left, right)
    if expect == "left" and not (left > right + 8):
        fail(msg + " (the correction must be centred on the frame: the crop's left edge is the frame corner, its right edge the frame centre)")
    print("ok  ", msg)


def cmd_flat(path, w, h, grey):
    Image.new("RGB", (int(w), int(h)), (int(grey),) * 3).save(path)


if __name__ == "__main__":
    c, a = sys.argv[1], sys.argv[2:]
    {"dehaze": cmd_dehaze, "maxdiff": cmd_maxdiff, "pixel": cmd_pixel, "lumapixel": cmd_lumapixel, "pixelmatch": cmd_pixelmatch,
     "gradeluma": cmd_gradeluma, "flat": cmd_flat, "edges": cmd_edges}[c](*a)
