# W30 Commit message check: no model name and no Co-Authored-By in commits after 0620e2a

Status at writing: main c270b1e. Entry: BK-501. Small task (about 1 hour): scripts, a hook template and a CI job. Everything in section 3 was run on the host, in throw away git repositories, with `bash` and `git` on this machine: 31 checks pass, including a real `git commit` through the hook. I also mutated the checker three ways (rules switched off, comment filter removed) and the test script failed 17 checks and 1 check, so the tests can fail. Run against the real repository: the 20 commits after the base pass, and all 73 commits of history give the expected 45 failures (the older ones, not checked in CI). NOT run: the CI job itself (written as an exact snippet) and `git config core.hooksPath` on anyone's clone.

## 1. What and why
CLAUDE.md says "Do not name any model in commits, code or docs". 45 of 73 commits carry a `Co-Authored-By: Claude Sonnet 5.5` trailer, all up to 0620e2a, because a tool prompt asks workers for one. History is published and is not rewritten. From now on a commit that has a Co-Authored-By trailer, or names a model or vendor product, fails a CI job, and a hook template stops it at commit time.

## 2. Decisions (no questions left)
- D1 Base: `tools/commit-message-base` holds `0620e2a`. Only commits after it (`0620e2a..HEAD`) are checked. The file can be moved forward later in a one line commit.
- D2 Any `Co-Authored-By` trailer is refused (this is a one person project). `ALLOW_COAUTHORS=1` lets a real human co-author through for a single commit; CI never sets it.
- D3 Names refused (case insensitive, whole words): Claude Opus, Claude Sonnet, Claude Haiku, Claude Instant, Claude followed by a version number, claude-ids such as `claude-sonnet-5-5`, Opus, Sonnet, Haiku, GPT with a number, ChatGPT, Gemini, Gemma, Llama, Mistral, Copilot, Codex.
- D4 Allowed on purpose: the exact line `Claude-Session: https://claude.ai/code/session_<id>`, the words "Claude Code" (the tool, not a model) and links to claude.ai or claude.com. A line that merely starts with `Claude-Session:` but is not that link does not hide a name (tested).
- D5 The CI job is not in the `needs` of the release job: a message problem on main cannot be fixed by rewriting history, so it must show red but must not block a release. It needs the full history (`fetch-depth: 0`); on a shallow clone the script exits 2 with a message instead of passing silently (tested).
- D6 The hook is a template at `tools/hooks/commit-msg`. It is installed per clone with `git config core.hooksPath tools/hooks` (a local setting; the PM or Jai runs it, workers do not change git config).
- D7 Executable bits must be committed: `git update-index --chmod=+x tools/commit-msg-check.sh tools/check-commit-messages.sh tools/test-commit-msg-check.sh tools/hooks/commit-msg`.

## 3. Files (all run and tested)
### tools/commit-msg-check.sh
```bash
#!/usr/bin/env bash
# Commit message style check (CLAUDE.md: "Do not name any model in commits, code or docs").
#   tools/commit-msg-check.sh <message-file>     as a commit-msg hook (git passes the file)
#   tools/commit-msg-check.sh --stdin            for a message on standard input (used by check-commit-messages.sh)
# Fails (exit 1) when the message
#   1. has a Co-Authored-By trailer (this is a one person project; set ALLOW_COAUTHORS=1 to allow a real human co-author), or
#   2. names an AI model or vendor product (Claude Opus, Sonnet, Haiku, GPT, ChatGPT, Gemini, Gemma, Llama, Mistral, Copilot, Codex).
# Allowed on purpose: the `Claude-Session: https://claude.ai/code/session_...` link, the words "Claude Code" (the tool, not a model), and the claude.ai and claude.com links.
set -u
if [ "${1:-}" = "--stdin" ]; then msg=$(cat); else [ -f "${1:-}" ] || { echo "usage: $0 <message-file> | --stdin" >&2; exit 2; }; msg=$(cat "$1"); fi
# git's editor template comments (lines starting with #) are not part of the message
msg=$(printf '%s\n' "$msg" | grep -v '^#')
bad=""
while IFS= read -r line; do
  [ -z "$line" ] && continue
  if printf '%s' "$line" | grep -qiE '^[[:space:]]*co-authored-by[[:space:]]*:'; then
    [ "${ALLOW_COAUTHORS:-0}" = "1" ] || bad+="  Co-Authored-By trailer: $line"$'\n'
    continue
  fi
  # take out what is allowed, then look for names
  rest=$(printf '%s' "$line" | sed -E 's#^[[:space:]]*Claude-Session:[[:space:]]*https://claude\.ai/code/session_[A-Za-z0-9_-]+[[:space:]]*$##; s#https?://(www\.)?claude\.(ai|com)[^[:space:])]*##g; s#[Cc]laude [Cc]ode##g')
  if printf '%s' "$rest" | grep -qiE '(^|[^[:alnum:]])(claude[ -]?(opus|sonnet|haiku|instant|[0-9])|opus|sonnet|haiku|gpt-?[0-9]|chatgpt|gemini|gemma|llama|mistral|copilot|codex)([^[:alnum:]]|$)'; then
    bad+="  names a model or vendor product: $line"$'\n'
  fi
done <<< "$msg"
if [ -n "$bad" ]; then
  printf 'commit message rejected (CLAUDE.md: do not name any model in commits):\n%s' "$bad" >&2
  echo "Fix: remove those lines. The Claude-Session link and the words Claude Code are fine." >&2
  exit 1
fi
exit 0
```
### tools/check-commit-messages.sh
```bash
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
```
### tools/commit-message-base
```
0620e2a   # last commit allowed to name a model; only commits after it are checked (BK-501)
```
### tools/hooks/commit-msg
```sh
#!/bin/sh
# Template commit-msg hook. Install once per clone:  git config core.hooksPath tools/hooks
exec "$(git rev-parse --show-toplevel)/tools/commit-msg-check.sh" "$1"
```
### tools/test-commit-msg-check.sh
```bash
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
```

## 4. CI (not run): a new job in .github/workflows/build.yml
Add next to the `lint` job (same runner and pinned checkout version as the other jobs):
```yaml
  commit-messages:
    # CLAUDE.md: no model name and no Co-Authored-By in commits. Only commits after tools/commit-message-base are checked.
    # Not in any "needs": a bad message on main cannot be fixed by rewriting history, so it shows red but never blocks a release.
    if: github.event_name != 'schedule'
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v7.0.1
        with:
          fetch-depth: 0     # the base commit must be present
      - name: Commit message tests
        run: ./tools/test-commit-msg-check.sh
      - name: No model name or Co-Authored-By after the base
        run: ./tools/check-commit-messages.sh
```
On a pull request the checked-out commit is GitHub's merge commit; its message ("Merge ... into ...") passes, and every branch commit after the base is checked.

## 5. Order of work
1. Add the five files; set the executable bits (D7); run `./tools/test-commit-msg-check.sh` (all passed) and `./tools/check-commit-messages.sh` on the branch.
2. Add the CI job; push; the job is green on a branch whose commits are clean.
3. Make it fail once on purpose in a throw away branch (a commit with a Co-Authored-By line) and paste the CI log; delete that branch.
4. The PM or Jai runs `git config core.hooksPath tools/hooks` on their clone.
5. Update `docs/backlog/tools/final-check.sh` item 6 to call `tools/check-commit-messages.sh`, and tell workers in DISPATCH.md that the check exists (the sentence is already there).

## 6. Acceptance
1. The test script prints `all passed` (31 checks) and exits 0; mutate `commit-msg-check.sh` (remove the model name `if`) and it fails.
2. `tools/check-commit-messages.sh` on main prints `ok: N commit message(s)` and exits 0.
3. The CI job is green on main and red on a throw away branch with a trailer.
4. `git commit -m x -m "Co-Authored-By: ..."` is refused on a clone with the hook installed.

## 7. Risks
- A tool that appends a trailer after the hook runs (some wrappers edit the message afterwards) is only caught by CI. That is why CI exists.
- A genuine word such as "opus" or "codex" in a subject would be refused; the list is short and `ALLOW_COAUTHORS` does not cover it, so reword the subject. The list is one regex in `commit-msg-check.sh`.
- GitHub's squash merge adds `Co-authored-by` lines when a pull request has several authors; with one author it adds none. If the PM ever merges a multi-author pull request the job will flag the squash commit; that is the intended behaviour.
