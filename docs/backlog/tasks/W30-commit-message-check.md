# W30 Commit message check: no model name and no Co-Authored-By in commits after 0620e2a (DONE, commit 6bc276b)

Entry: BK-501. Merged on 6 Oct 2026. The files are in the repository: `tools/commit-msg-check.sh` (hook and `--stdin` checker), `tools/check-commit-messages.sh` (range check for CI), `tools/commit-message-base` (0620e2a), `tools/hooks/commit-msg` (hook template), `tools/test-commit-msg-check.sh` (31 checks), and the CI job `commit-messages` in `.github/workflows/build.yml`. This page keeps the decisions and the proof; the code is not repeated here so that these docs name no model themselves (the checker and its test must, because they hold the list of names to refuse).

## Decisions
- D1 Base: only commits after `tools/commit-message-base` are checked; 45 older commits carry a model trailer and history is not rewritten.
- D2 Any Co-Authored-By trailer is refused; `ALLOW_COAUTHORS=1` lets a real human co-author through for one commit.
- D3 A model or vendor product name anywhere in the message is refused (the regex is in `tools/commit-msg-check.sh`).
- D4 Allowed: the exact `Claude-Session` link line, the words "Claude Code" (the tool), and links to the vendor's site. A line that only starts with `Claude-Session:` does not hide a name.
- D5 The CI job needs `fetch-depth: 0`; a shallow clone exits 2 with a message. It is in no `needs`, so a bad message on main shows red but never blocks a release.
- D6 The hook is installed per clone with `git config core.hooksPath tools/hooks` (the PM or Jai runs it; workers do not change git config).

## Proof
31 checks in throw away repositories pass, including a real `git commit` rejected by the hook and a shallow clone reported with exit 2; three mutations of the checker made the test script fail (17 and 1 checks), so the tests can fail. On the real repository the 24 commits after the base pass.

## Open
- Install the hook on Jai's and the PM's clones (D6).
