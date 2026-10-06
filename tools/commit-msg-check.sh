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
