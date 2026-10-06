#!/usr/bin/env bash
# Installs Android cmdline-tools, platform, build-tools, NDK and CMake into .android-sdk (git-ignored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$ROOT/.android-sdk"
CLT_ZIP="commandlinetools-linux-15859902_latest.zip"
# SHA-256 of that zip as downloaded from dl.google.com on 6 Oct 2026 (trust on first use: not compared with the value on the Android Studio page).
CLT_SHA="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
PLATFORM="${PLATFORM:-android-37.0}"
BUILD_TOOLS="${BUILD_TOOLS:-37.0.0}"
NDK="${NDK:-28.2.13676358}"
CMAKE="${CMAKE:-3.31.6}"
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  CLT_TMP="$(mktemp -d)"
  curl -fsSL --retry 5 --retry-all-errors -o "$CLT_TMP/clt.zip" "https://dl.google.com/android/repository/$CLT_ZIP"
  echo "$CLT_SHA  $CLT_TMP/clt.zip" | sha256sum -c -
  unzip -q -o "$CLT_TMP/clt.zip" -d "$SDK/cmdline-tools"
  rm -rf "$CLT_TMP"
  rm -rf "$SDK/cmdline-tools/latest"; mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" "ndk;$NDK" "cmake;$CMAKE"
# tools/env.sh is tracked and path independent; only recreate it if it is missing, and never rewrite it when it exists.
if [ ! -f "$ROOT/tools/env.sh" ]; then
  cat > "$ROOT/tools/env.sh" <<'ENV'
_rawline_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export ANDROID_HOME="$_rawline_root/.android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
unset _rawline_root
ENV
fi
echo "SDK ready at $SDK"
