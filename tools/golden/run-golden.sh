#!/usr/bin/env bash
# Renders sample RAWs through the real GLSL pipeline on Mesa llvmpipe and compares with stored reference images.
#   ./run-golden.sh            compare
#   ./run-golden.sh --update   rewrite the references
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
W=${GOLDEN_WORK:-/tmp/golden-work}
mkdir -p "$W" "$ROOT/tools/golden/ref"
LR_VER=0.22.2
LR_SHA=de86b035655accff8d4010f1a221fdf50d353cb7b1422ba26f14a0db92612cfa

if [ ! -f "$W/libraw_host.a" ]; then
  curl -fsSL -o "$W/libraw.tgz" "https://www.libraw.org/data/LibRaw-$LR_VER.tar.gz"
  echo "$LR_SHA  $W/libraw.tgz" | sha256sum -c -
  tar xzf "$W/libraw.tgz" -C "$W"
  mkdir -p "$W/obj"
  ( cd "$W/LibRaw-$LR_VER" && find src -name '*.cpp' ! -path '*/integration/*' ! -name '*_ph.cpp' | \
      xargs -P"$(nproc)" -I{} sh -c 'g++ -O2 -w -c -DLIBRAW_BUILDLIB -I. {} -o "'"$W"'/obj/$(echo {} | tr / _).o"' )
  ar rcs "$W/libraw_host.a" "$W"/obj/*.o
fi
LIBRAW_SRC="$W/LibRaw-$LR_VER" LIBRAW_A="$W/libraw_host.a" "$ROOT/tools/golden/build.sh" >/dev/null

SAMPLE="$W/P1055415.RW2"
[ -f "$SAMPLE" ] || curl -fsSL -o "$SAMPLE" https://raw.pixls.us/data/Panasonic/DC-S5M2X/P1055415.RW2

# name | extra arguments
SCENES=(
  "base|"
  "tone|exposure=0.8 shadows=60 highlights=-40 contrast=15"
  "colour|temp=25 tint=-10 saturation=20 vibrance=30"
  "local|clarity=40 texture=30 dehaze=25"
  "mask|maskexp=-1.5"
  "radial|maskrad=1.2 vig=-40"
  "layer|masklayer=1.5"
  "crop|cropw=0.6 angle=0.05"
)
fail=0
for s in "${SCENES[@]}"; do
  name="${s%%|*}"; args="${s#*|}"
  /tmp/golden/golden "$SAMPLE" "$W/$name.ppm" 320 half $args 2>/dev/null
  if [ "${1:-}" = "--update" ]; then
    python3 "$ROOT/tools/golden/compare.py" --save "$W/$name.ppm" "$ROOT/tools/golden/ref/$name.png"
  else
    python3 "$ROOT/tools/golden/compare.py" "$W/$name.ppm" "$ROOT/tools/golden/ref/$name.png" || fail=1
  fi
done
exit $fail
