#!/usr/bin/env bash
# Builds the host golden harness. Needs Mesa (libgles2-mesa-dev, libegl1-mesa-dev) and the host LibRaw archive from tools/golden/hostlibraw.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
C="$ROOT/core/native/src/main/cpp"
OUT=/tmp/golden
mkdir -p $OUT
cmake -DSHADER_DIR="$C/shaders" -DOUT="$OUT/shader_sources.h" -P "$C/gen_shaders.cmake"
LR=${LIBRAW_SRC:-/tmp/lrbuild/LibRaw-0.22.2}
g++ -O2 -std=c++17 -I"$OUT" -I"$C" -I"$C/engine" -I"$LR" "$ROOT/tools/golden/golden.cpp" "$C/engine/engine.cpp" "$C/raw_decode.cpp" \
  ${LIBRAW_A:-/tmp/lrbuild/libraw_host.a} -fopenmp -lEGL -lGLESv2 -lpthread -o $OUT/golden
echo built $OUT/golden
