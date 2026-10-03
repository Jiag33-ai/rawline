import sys
from PIL import Image, ImageChops, ImageStat

MEAN_TOL = 1.5     # mean absolute difference, 0-255
MAX_TOL = 40       # worst single pixel channel

if sys.argv[1] == "--save":
    Image.open(sys.argv[2]).convert("RGB").save(sys.argv[3], optimize=True)
    print("saved", sys.argv[3])
    sys.exit(0)
a = Image.open(sys.argv[1]).convert("RGB")
b = Image.open(sys.argv[2]).convert("RGB")
if a.size != b.size:
    print("size differs", a.size, b.size); sys.exit(1)
d = ImageChops.difference(a, b)
mean = sum(ImageStat.Stat(d).mean) / 3
worst = max(x[1] for x in d.getextrema())
ok = mean <= MEAN_TOL and worst <= MAX_TOL
print(("ok  " if ok else "FAIL"), sys.argv[2].split("/")[-1], "mean %.2f max %d" % (mean, worst))
sys.exit(0 if ok else 1)
