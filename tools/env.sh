# Source this file. The SDK lives next to the repo, so no machine specific path is stored here.
_rawline_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export ANDROID_HOME="$_rawline_root/.android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
unset _rawline_root
