#!/usr/bin/env bash
# Host-side check of the embedded-preview parser against files in testdata/ (sandbox only, not a phone timing).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
C="$ROOT/core/native/src/main/cpp"
cat > /tmp/rw2_parse_test.cpp <<'CPP'
#include "rw2_preview.h"
#include <fcntl.h>
#include <cstdio>
int main(int c, char **v) {
  int bad = 0;
  for (int i = 1; i < c; i++) {
    int fd = open(v[i], O_RDONLY); PreviewInfo p;
    bool ok = findEmbeddedPreview(fd, p);
    printf("%s ok=%d off=%lld len=%lld orientation=%d\n", v[i], ok, (long long)p.offset, (long long)p.length, p.orientation);
    if (!ok) bad++;
  }
  return bad;
}
CPP
g++ -O2 -std=c++17 -I"$C" /tmp/rw2_parse_test.cpp "$C/rw2_preview.cpp" -o /tmp/rw2_parse_test
/tmp/rw2_parse_test "$ROOT"/testdata/*.RW2
