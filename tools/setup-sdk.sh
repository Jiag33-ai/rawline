#!/usr/bin/env bash
# Installs Android cmdline-tools, platform, build-tools, NDK and CMake into .android-sdk (git-ignored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$ROOT/.android-sdk"
CLT_ZIP="commandlinetools-linux-15859902_latest.zip"
PLATFORM="${PLATFORM:-android-37.0}"
BUILD_TOOLS="${BUILD_TOOLS:-37.0.0}"
NDK="${NDK:-28.2.13676358}"
CMAKE="${CMAKE:-3.31.6}"
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o /tmp/clt.zip "https://dl.google.com/android/repository/$CLT_ZIP"
  unzip -q -o /tmp/clt.zip -d "$SDK/cmdline-tools"
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
