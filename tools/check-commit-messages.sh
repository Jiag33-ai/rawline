#!/usr/bin/env bash
# CI step: every commit in the range must pass tools/commit-msg-check.sh.
#   tools/check-commit-messages.sh [range]       default: <base>..HEAD, with <base> read from tools/commit-message-base
# The base is the last commit that is allowed to carry a model name (45 older commits do; history is not rewritten). Only commits after it are checked.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=$(sed -e 's/#.*//' "$ROOT/tools/commit-message-base" | tr -d '[:space:]')
RANGE="${1:-$BASE..HEAD}"
if [ -z "${1:-}" ] && ! git -C "$ROOT" cat-file -e "$BASE^{commit}" 2>/dev/null; then
  echo "base commit $BASE not found: this is a shallow clone. Use fetch-depth: 0 in the checkout step." >&2; exit 2
fi
fail=0; n=0
for c in $(git -C "$ROOT" rev-list --reverse "$RANGE"); do
  n=$((n + 1))
  out=$(git -C "$ROOT" log -1 --format=%B "$c" | "$ROOT/tools/commit-msg-check.sh" --stdin 2>&1) || { fail=1; echo "FAIL $(git -C "$ROOT" log -1 --format='%h %s' "$c")"; echo "$out" | sed 's/^/     /'; }
done
if [ "$fail" = 0 ]; then echo "ok: $n commit message(s) in $RANGE follow the rules"; fi
exit $fail
