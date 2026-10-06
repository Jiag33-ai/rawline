#!/usr/bin/env bash
# Builds and runs the Studio compositor golden (`studio_blend3`) on Mesa llvmpipe against the independent Python reference.
#   tools/golden/studio-golden.sh            run from the repo root (needs the shader header from tools/golden/build.sh's cmake step)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
C="$ROOT/core/native/src/main/cpp"
W=${STUDIO_GOLDEN_WORK:-/tmp/studio-golden}
mkdir -p "$W"
cmake -DSHADER_DIR="$C/shaders" -DOUT="$W/shader_sources.h" -P "$C/gen_shaders.cmake"
g++ -O1 -std=c++17 -I"$W" -I"$C" "$ROOT/tools/golden/studio_golden.cpp" "$C/studio/studio_compositor.cpp" -lEGL -lGLESv2 -o "$W/studio_golden"
python3 "$ROOT/tools/studio/studio_scene.py" make "$W"
for k in a b c band1 band2; do "$W/studio_golden" "$W/scene_$k.txt" "$W/out_$k.rgba"; done
python3 "$ROOT/tools/studio/studio_scene.py" compare "$W"

# S1b: the live stroke (stamps into the R16F stroke buffer, shown through the compositor) against the Python reference that bakes the stroke
python3 "$ROOT/tools/studio/studio_brush.py" make "$W"
for k in hard soft flow pressure erase; do "$W/studio_golden" "$W/scene_$k.txt" "$W/out_$k.rgba"; done
python3 "$ROOT/tools/studio/studio_brush.py" compare "$W"

# Banded stroke readback: the coverage read in bands of 1, 7, 64, 256 and 1000 rows must equal one single read (band 100000) byte for byte
for k in hard flow erase; do
  STUDIO_BAND=100000 STUDIO_READ_STROKE="$W/cov_ref_$k.bin" "$W/studio_golden" "$W/scene_$k.txt" "$W/o.rgba"
  for b in 1 7 64 256 1000; do
    STUDIO_BAND=$b STUDIO_READ_STROKE="$W/cov_$k.bin" "$W/studio_golden" "$W/scene_$k.txt" "$W/o.rgba"
    cmp "$W/cov_ref_$k.bin" "$W/cov_$k.bin" || { echo "banded readback differs: scene $k band $b" >&2; exit 1; }
  done
done
echo "studio banded readback: identical for 3 scenes x 5 band sizes"
