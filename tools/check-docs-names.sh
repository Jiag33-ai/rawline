#!/usr/bin/env bash
# CI step (CLAUDE.md: "Do not name any model in commits, code or docs"): fails when a tracked file names an AI model or vendor.
#   tools/check-docs-names.sh [repo-root]
# Allowed on purpose: the file name CLAUDE.md, the .claude folder, the Claude-Session trailer and link, and the words "Claude Code".
# The pattern is assembled from fragments so this script does not carry the names it looks for. Binary files and the
# SDK folder are skipped; tools/commit-msg-check.sh and its test list the names on purpose and are skipped too.
set -u
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PAT="(so""nnet|ha""iku|\bop""us\b|cla""ude|anthro""pic|\bg""pt|open""ai|gem""ini|gem""ma|co""pilot|ll""ama|mis""tral|co""dex)"
ALLOW="s#cla""ude\.(ai|com)[^[:space:])]*##g; s#CLA""UDE\.md##g; s#\.cla""ude##g; s#Cla""ude-Session##g; s#Cla""ude Code##g"
bad=""
while IFS= read -r f; do
  [ -f "$ROOT/$f" ] || continue
  case "$f" in .android-sdk/*|tools/commit-msg-check.sh|tools/test-commit-msg-check.sh|tools/check-docs-names.sh) continue;; esac
  grep -Iq . "$ROOT/$f" 2>/dev/null || continue
  if sed -E "$ALLOW" "$ROOT/$f" | grep -q -i -E "$PAT"; then bad="$bad$f"$'\n'; fi
done < <(git -C "$ROOT" ls-files)
if [ -n "$bad" ]; then
  echo "FAIL: these tracked files name a model or vendor (CLAUDE.md rule):" >&2; printf '%s' "$bad" | sed 's/^/  /' >&2; exit 1
fi
echo "ok: no model or vendor name in tracked text files"
