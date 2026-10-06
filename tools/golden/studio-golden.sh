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
for k in a b c; do "$W/studio_golden" "$W/scene_$k.txt" "$W/out_$k.rgba"; done
python3 "$ROOT/tools/studio/studio_scene.py" compare "$W"
