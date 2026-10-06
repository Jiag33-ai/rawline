"""Checks the crop the golden harness solved against tools/golden/fitcrop.expected (also read by core/render GeoFitTest).
usage: fitcheck.py <fixture> <name> "<srcW srcH orientation>" "<x y w h>"
"""
import sys

fixture, name, src, crop = sys.argv[1:5]
line = None
for raw in open(fixture):
    if raw.startswith(name + " "):
        line = raw.split()
if line is None:
    print("FAIL", name, "has no line in", fixture); sys.exit(1)
got = [float(x) for x in crop.split()]
want = [float(x) for x in line[line.index("->") + 1:]]
if [int(x) for x in line[1:4]] != [int(x) for x in src.split()]:
    print("FAIL", name, "source size/orientation differs from the fixture", line[1:4], src.split()); sys.exit(1)
if len(got) != 4 or max(abs(a - b) for a, b in zip(got, want)) > 2e-3:
    print("FAIL", name, "solved crop", got, "fixture", want); sys.exit(1)
print("ok   %s: no outside-image pixels, solved crop %s equals the Kotlin fixture" % (name, got))
