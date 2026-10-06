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
  "curve|curve=0.3"
  "colour|temp=25 tint=-10 saturation=20 vibrance=30"
  "local|clarity=40 texture=30 dehaze=25"
  "mask|maskexp=-1.5"
  "radial|maskrad=1.2 vig=-40"
  "layer|masklayer=1.5"
  "layers2|masklayers=1.5"
  "crop|cropw=0.6 angle=0.05"
  "rotcrop|angle=0.1 autofit=1"
  "cropmove|angle=0.05 cropw=0.6 autofit=1"
  "lensfit|lens=1 autofit=1"
  "lensfill|lensfill=1 autofit=1"
  "warpfit|ksv=0.3 ksh=0.1 angle=-0.04 lens=1 autofit=1"
  "dehazeneg|dehaze=-60"
  "grade|ghue=0.1667 gsat=1"
  "lenslocal|lens=1 texture=60 clarity=60"
)
# Outside-image check. Scenes with autofit=1 are rendered a second time with mark=1 (outside pixels painted magenta): none may
# remain, and the crop the harness solved must equal the one Kotlin's Geo.fitCrop computes (tools/golden/fitcrop.expected is read
# by core/render GeoFitTest). The same inputs without autofit are the control: they must show outside pixels, or the check is blind.
checkOutside() {
  local name="$1" args="$2" out outside crop src ctl
  out=$(/tmp/golden/golden "$SAMPLE" "$W/$name.mark.ppm" 320 half mark=1 $args 2>&1 >/dev/null) || { echo "FAIL $name outside check did not run"; return 1; }
  outside=$(echo "$out" | sed -n 's/^OUTSIDE \([0-9]*\) of.*/\1/p')
  crop=$(echo "$out" | sed -n 's/^FITCROP //p')
  src=$(echo "$out" | sed -n 's/^SRC //p')
  if [ "$outside" != "0" ]; then echo "FAIL $name: $outside outside-image pixels"; return 1; fi
  python3 "$ROOT/tools/golden/fitcheck.py" "$ROOT/tools/golden/fitcrop.expected" "$name" "$src" "$crop" || return 1
  # control: same inputs, crop not fitted. The Lumix style profile alone stays inside the frame (only the safety margin applies).
  if [ "$name" != "lensfit" ]; then
    ctl=$(/tmp/golden/golden "$SAMPLE" "$W/$name.ctl.ppm" 320 half mark=1 ${args// autofit=1/} 2>&1 >/dev/null | sed -n 's/^OUTSIDE \([0-9]*\) of.*/\1/p')
    if [ "${ctl:-0}" = "0" ]; then echo "FAIL $name: control without autofit shows no outside pixels, so the check could not detect them"; return 1; fi
    echo "ok   $name control (no fit) shows $ctl outside pixels"
  fi
}
fail=0
for s in "${SCENES[@]}"; do
  name="${s%%|*}"; args="${s#*|}"
  /tmp/golden/golden "$SAMPLE" "$W/$name.ppm" 320 half $args 2>/dev/null
  if [ "${1:-}" = "--update" ]; then
    python3 "$ROOT/tools/golden/compare.py" --save "$W/$name.ppm" "$ROOT/tools/golden/ref/$name.png"
  else
    python3 "$ROOT/tools/golden/compare.py" "$W/$name.ppm" "$ROOT/tools/golden/ref/$name.png" || fail=1
  fi
  case "$args" in *autofit=1*) checkOutside "$name" "$args" || fail=1 ;; esac
done

# Property checks (no stored reference): each one fails on the pre-fix shader and passes now.
N="python3 $ROOT/tools/golden/numcheck.py"
G=/tmp/golden/golden
numericChecks() {
  local rc=0
  # AE-002: negative Dehaze adds haze (lifts blacks, lowers contrast)
  $N dehaze "$W/base.ppm" "$W/dehazeneg.ppm" || rc=1
  # AE-014: a grading tint changes colour, not brightness
  $N gradeluma "$W/base.ppm" "$W/grade.ppm" || rc=1
  # AE-003: Texture and Clarity do nothing to a flat picture, also in the corners where the lens profile lifts the vignetting.
  $N flat "$W/flat.ppm" 600 400 128
  $G "$W/flat.ppm" "$W/flat_l0.ppm" 320 half lens=1 2>/dev/null
  $G "$W/flat.ppm" "$W/flat_l1.ppm" 320 half lens=1 texture=60 clarity=60 2>/dev/null
  $N maxdiff "$W/flat_l0.ppm" "$W/flat_l1.ppm" 1 "AE-003 lens vignetting and local contrast on a flat picture" || rc=1
  # AE-005: a heal patch (flat, same colour as the source) laid over the left half must be invisible next to the corrected source.
  $G "$W/flat.ppm" "$W/flat_ov.ppm" 320 half lens=1 overlay=0.2158605 2>/dev/null
  $N maxdiff "$W/flat_l0.ppm" "$W/flat_ov.ppm" 2 "AE-005 heal patch under lens vignetting" || rc=1
  # AE-012: local contrast inside a mask is measured against the globally exposed picture (+1 EV global, flat picture, clarity in the mask)
  $G "$W/flat.ppm" "$W/flat_g.ppm" 320 half exposure=1 2>/dev/null
  $G "$W/flat.ppm" "$W/flat_gm.ppm" 320 half exposure=1 maskrad=0 maskclarity=50 2>/dev/null
  $N maxdiff "$W/flat_g.ppm" "$W/flat_gm.ppm" 1 "AE-012 mask clarity on a flat picture under +1 EV global exposure" || rc=1
  # AE-015: colour range and luminance range masks select the value the picker read from the screen
  local x=160 y=107 px ld
  px=$($N pixel "$W/base.ppm" $x $y)
  ld=$($N lumapixel "$W/base.ppm" $x $y)
  $G "$SAMPLE" "$W/m_col.ppm" 320 half maskcolor=$px,0.2,0.5 maskexposure=-2 2>/dev/null
  $G "$SAMPLE" "$W/m_glob.ppm" 320 half exposure=-2 2>/dev/null
  $N pixelmatch "$W/m_col.ppm" "$W/m_glob.ppm" "$W/base.ppm" $x $y "AE-015 colour range picks its own colour" || rc=1
  local lo hi
  lo=$(python3 -c "print(max(0.0, $ld - 0.03))"); hi=$(python3 -c "print(min(1.0, $ld + 0.03))")
  $G "$SAMPLE" "$W/m_lum.ppm" 320 half maskluma=$lo,$hi,0.05 maskexposure=-2 2>/dev/null
  $N pixelmatch "$W/m_lum.ppm" "$W/m_glob.ppm" "$W/base.ppm" $x $y "AE-015 luminance range picks its own luminance" || rc=1
  # AE-018: the 16 bit path (float32 targets and curve) agrees with the 8 bit path and really has more than 8 bits
  local out
  out=$(GOLDEN_HALF=1 $G "$SAMPLE" "$W/hp.ppm" 320 half exposure=0.3 shadows=40 2>&1 >/dev/null) || { echo "FAIL 16 bit path: $out" | tail -3; rc=1; }
  echo "$out" | grep -E '^16 bit' | sed 's/^/ok   /'
  return $rc
}
numericChecks || fail=1
# Host unit test for the half float conversion (AE-028)
g++ -O1 -std=c++17 -I"$ROOT/core/native/src/main/cpp" "$ROOT/tools/golden/halfs_test.cpp" -o /tmp/golden/halfs_test && /tmp/golden/halfs_test || fail=1
exit $fail
