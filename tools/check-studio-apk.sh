#!/usr/bin/env bash
# Proves the Studio flag in an APK, in both directions (docs/STUDIO_STATUS.md).
#   tools/check-studio-apk.sh path/to/app.apk true    the dex files hold the Studio feature, model and compositor classes
#   tools/check-studio-apk.sh path/to/app.apk false   none of them is there (only the stub app.rawline.StudioEntry may be)
set -euo pipefail
apk="${1:?apk path}"; want="${2:?true or false}"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -qo "$apk" 'classes*.dex' -d "$tmp"
count() { cat "$tmp"/classes*.dex | grep -aFc -- "$1" || true; }
feature=$(count 'Lapp/rawline/feature/studio/')
model=$(count 'Lapp/rawline/core/studio/')
native=$(count 'Lapp/rawline/core/nativelib/StudioNative;')
echo "flag $want: feature/studio $feature, core/studio $model, StudioNative $native"
if [ "$want" = "true" ]; then
  test "$feature" != "0" && test "$model" != "0" && test "$native" != "0" || { echo "FAIL: Studio classes are missing from a build with the flag on"; exit 1; }
else
  test "$feature" = "0" && test "$model" = "0" && test "$native" = "0" || { echo "FAIL: Studio classes are in a build with the flag off"; exit 1; }
fi
echo "ok"
