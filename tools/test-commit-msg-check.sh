#!/usr/bin/env bash
# Tests for tools/commit-msg-check.sh, tools/check-commit-messages.sh and the hook, in a throw away repository.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fails=0
ok()   { echo "ok    $1"; }
bad()  { echo "FAIL  $1"; fails=$((fails + 1)); }
expect_pass() { if printf '%s\n' "$2" | "$ROOT/tools/commit-msg-check.sh" --stdin >/dev/null 2>&1; then ok "$1"; else bad "$1 (should pass)"; fi; }
expect_fail() { if printf '%s\n' "$2" | "$ROOT/tools/commit-msg-check.sh" --stdin >/dev/null 2>&1; then bad "$1 (should fail)"; else ok "$1"; fi; }

# ---- the message rules
expect_pass "plain message" "Fix the grid order while indexing"
expect_pass "body with several lines" $'Subject\n\nLonger text about colours and Claude Code tooling.\nSecond line.'
expect_pass "session link trailer" $'Subject\n\nClaude-Session: https://claude.ai/code/session_013jKJuP55QTzVvZweey15cf'
expect_pass "pull request footer with the tool name and links" $'Subject\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\nhttps://claude.ai/code/session_abc123'
expect_pass "the word opus inside another word is not a name" "Reopus the ticket: opuscule"
expect_pass "editor template comments are ignored" $'Subject\n# Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>\n# Please enter the commit message'
expect_fail "Co-Authored-By naming a model" $'Subject\n\nCo-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>'
expect_fail "Co-Authored-By in lower case" $'Subject\n\nco-authored-by: Someone <a@b.c>'
expect_fail "a human co-author is refused by default" $'Subject\n\nCo-Authored-By: Jai <jaibevan33@gmail.com>'
if printf '%s\n' $'Subject\n\nCo-Authored-By: Jai <jaibevan33@gmail.com>' | ALLOW_COAUTHORS=1 "$ROOT/tools/commit-msg-check.sh" --stdin >/dev/null 2>&1; then ok "ALLOW_COAUTHORS=1 lets a human co-author through"; else bad "ALLOW_COAUTHORS=1 should allow a human co-author"; fi
expect_fail "model name in the subject" "Use Claude Opus 4 for the review"
expect_fail "model name in lower case" "refactor after sonnet suggestion"
expect_fail "model id" "pin claude-sonnet-5-5 in the script"
expect_fail "claude with a version number" "Claude 3 notes"
expect_fail "GPT" "compare with gpt-4 output"
expect_fail "ChatGPT" "ChatGPT said so"
expect_fail "Gemini" "ask Gemini"
expect_fail "model name next to a link line that is allowed" $'Subject\n\nHaiku wrote this\nClaude-Session: https://claude.ai/code/session_x'
expect_fail "a link line that is not the session link does not hide a name" "Claude-Session: sonnet"

# ---- the hook file mode
printf 'Fine message\n' > "$T/ok.txt"; printf 'Names Sonnet\n' > "$T/no.txt"
"$ROOT/tools/commit-msg-check.sh" "$T/ok.txt" >/dev/null 2>&1 && ok "file mode passes a clean file" || bad "file mode clean"
"$ROOT/tools/commit-msg-check.sh" "$T/no.txt" >/dev/null 2>&1 && bad "file mode should fail" || ok "file mode fails a bad file"
"$ROOT/tools/commit-msg-check.sh" "$T/missing.txt" >/dev/null 2>&1; [ $? -eq 2 ] && ok "missing file is exit 2" || bad "missing file exit code"

# ---- a throw away repository: range checks and the real hook
R="$T/repo"; mkdir -p "$R/tools/hooks"; cp "$ROOT/tools/commit-msg-check.sh" "$ROOT/tools/check-commit-messages.sh" "$R/tools/"; cp "$ROOT/tools/hooks/commit-msg" "$R/tools/hooks/"
cd "$R"; git init -q .; git config user.email t@example.com; git config user.name T; git config commit.gpgsign false
git commit -q --allow-empty -m "old commit" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
BASE=$(git rev-parse --short HEAD); printf '%s  # base\n' "$BASE" > tools/commit-message-base; git add tools; git commit -q -m "tools" 
git commit -q --allow-empty -m "clean one"
git commit -q --allow-empty -m "with link" -m "Claude-Session: https://claude.ai/code/session_abc"
if tools/check-commit-messages.sh >/dev/null 2>&1; then ok "range after the base passes; the old commit with a trailer is not checked"; else bad "range after base should pass"; fi
git commit -q --allow-empty -m "bad one" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
git commit -q --allow-empty -m "clean two"
out=$(tools/check-commit-messages.sh 2>&1); rc=$?
[ $rc -eq 1 ] && ok "a bad commit after the base fails the range" || bad "bad commit should fail (rc $rc)"
echo "$out" | grep -q "FAIL .* bad one" && ok "the failing commit is named" || bad "failing commit not named"
echo "$out" | grep -q "clean two" && bad "a clean commit was reported" || ok "clean commits are not reported"
tools/check-commit-messages.sh HEAD~1..HEAD >/dev/null 2>&1 && ok "an explicit range with only a clean commit passes" || bad "explicit clean range"
# shallow clone: the base is missing, which must be an error (exit 2) and not a silent pass
git clone -q --depth 1 "file://$R" "$T/shallow" 2>/dev/null && { (cd "$T/shallow" && tools/check-commit-messages.sh >/dev/null 2>&1; [ $? -eq 2 ]) && ok "a shallow clone is reported (exit 2)" || bad "shallow clone should exit 2"; }
# the real hook through git commit
git reset -q --hard HEAD~2; git config core.hooksPath tools/hooks
git commit -q --allow-empty -m "hook accepts this" && ok "git commit with a clean message passes the hook" || bad "hook rejected a clean commit"
if git commit -q --allow-empty -m "hook rejects" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" 2>/dev/null; then bad "hook accepted a model trailer"; else ok "git commit with a model trailer is rejected by the hook"; fi
[ "$(git log --format=%s | head -1)" = "hook accepts this" ] && ok "the rejected commit was not created" || bad "rejected commit exists"
echo; [ $fails -eq 0 ] && echo "all passed" || echo "$fails failed"
exit $fails
