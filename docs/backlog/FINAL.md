# FINAL: end of campaign checklist

Campaign start 08:27Z on 6 Oct 2026, so the four hour mark is 12:27Z. Run this list in order when the PM calls the end, or when that time is reached. It is short on purpose: every step has a command or a named file. The first item is automated (`final-check.sh`); the rest are checks a person or a worker does with the files named.

## 0. The rule for work in flight
- A task that is merged and green stays. A task that is not merged by the mark is not rushed: it stays on its branch with its task file, and nothing half-finished is left on main.
- No new worker is started after the mark except to fix something this checklist finds.
- `git status --short` must be empty on main when the campaign ends (it shows 4 to 6 changed paths while a worker is mid-task; those belong to that worker).

## 1. Final adversarial re-check (about 30 minutes)
1. Run `docs/backlog/tools/final-check.sh` (read only; it compiles the live sources into a scratch folder). It must print `ok` for: studio-model host tests, core/model host tests, the copy rules over the whole repository, the platform rules (manifest and BackHandler list; this one is red until W16 has merged, by design), no em dash in tracked text, no model name in commit messages since 0620e2a, no model name in tracked files. Lines starting `info` are for reading: patches that no longer apply are fine if the task merged; the count of old commits with a model trailer is expected (45, BK-501).
2. Run the Mesa golden suites on the live tree: `tools/golden/run-golden.sh` and `tools/golden/studio-golden.sh` (and, if W22 and W23 have merged, `tools/colour/run-colour.sh` and the `look2_checks.py` lines inside `run-golden.sh`). All PASS; every XFAIL listed in docs/COLOUR.md is still an XFAIL and not silently a pass or a fail.
3. Re-read what merged since the last review: `git log --oneline c270b1e..HEAD`. For each commit: does it match its task file (same files owned, no edits outside them)? Spot check three things in each: a thread rule (nothing blocking the main or GL thread), a memory rule (no whole-image array added in a hot path), a Develop rule (the golden scenes unchanged unless the commit says why). Findings go to `review-<name>.md` in the same format as `review-s1b.md`.
4. Re-check the status table against git: for every entry in `assemble.py` STATUS marked DESIGNED or PARTLY DESIGNED, `git log --grep` for its task id or file; flip merged ones to DONE with the commit id, then `python3 assemble.py`.
5. One adversarial pass on honesty: grep the merged commit messages and docs for the words "faster", "smoother", "looks better", "fixed" and check each has a Copy report row or a golden number behind it (CLAUDE.md: never claim speed or look results without the phone's Copy report). Anything without one is reworded to "specified" or "tested on the host".

## 2. Release verification (about 45 minutes, needs the phone for the last four)
1. CI green on main at the release commit, all jobs (golden, unit tests with `STUDIO_ENABLED` off and on, lint, models).
2. The release APK from CI: `tools/check-studio-apk.sh <apk> true` while the release workflow builds with `-PstudioEnabled=true` (it does today; use `false` if BK-502 turns it off), so the check matches what the PM decided; `unzip -l` shows only `lib/arm64-v8a`; `zipalign -c -P 16 4` passes; the APK size and its ten biggest entries are in the run log (W01) and not larger than the 5 Oct build (61.6 MB) without a reason; the signing key is the sideload key and the certificate hash equals the one on the phone.
3. `versionName` and `versionCode` are the ones the PM expects; the release notes list only what merged. CLAUDE.md asks that the latest release version name is reported after every push or release: put it at the top of the final report.
4. Phone, update install over the previous build (not a fresh install): the library, edits, ratings and presets are all still there (Room migration), the photo permission is not asked again.
5. Phone, the golden path: open an RW2, edit (exposure, one mask), leave, come back (edit is there), export (one JPEG), see it in the Gallery with the right orientation and colour; Copy report pasted into the thread.
6. Phone: run `PHONE-TEST-S1.md` (10 minutes) on the release APK; every step must be ok or a known, logged problem.
7. Phone, the things this campaign changed that CI cannot see: back gesture on each screen (W16 table), pen with the hand on the glass if the debug Studio build is installed (W28), first run opens on RAW photos (W29), a backup file exists outside the app (W06).
8. Tag: tag pushes are rejected by the sandbox proxy, so the tag command is for the PM or Jai: `git tag -a v<version> <commit> -m "<one line>"` then push from a machine that can.

## 3. Docs updated (the worker who merged each item does its line; the PM checks the list)
| File | Must say |
|---|---|
| docs/STUDIO_STATUS.md | S1a, S1b, S1c merged and what is measured and what is not; the S1b fixes state; BK-488: Studio blends in gamma encoded display space by default, linear stored and off |
| docs/DECISIONS.md | Look versions and the look 2 knee 0.55 (provisional, pending Jai); BK-488; the layer container (deflate) and the WebP re-test in S2; the default RAW photos view (W29); no model names in commits |
| docs/PERF.md | Rows filled only from Copy reports; every other row says "not measured" |
| docs/COLOUR.md and docs/QUALITY.md (W21) | The stage table, domains, tolerances and the XFAIL list |
| docs/PLATFORM.md (W16) | The API 37 checklist and the back order table |
| docs/COPY.md (W27) | The copy rules; `docs/copy-allow.txt` has a reason for every entry |
| docs/AUDIT.md | Items closed by merged work moved to the fixed list, open items trimmed |
| docs/UI_SPEC.md | The chip row and What's New banner (W29), the onboarding screens (W27) |
| docs/backlog/ | This folder, committed on the PM decision. Re-stage (`stage_docs_backlog.py` in the session audit folder) and copy again whenever the sources change. |

## 4. CLAUDE.md lines (draft, exact; the PM decides at the end)
The current file has the sections Rules, Commands, Layout, Gotchas. Add these lines, each under the section named, in the same short style. Block A is true on main today or after W30; block B only after the task named in brackets has merged (leave a line out until then).

Block A
- Rules: `- Commit messages carry no Co-Authored-By line and no model name (CI checks commits after 0620e2a). The Claude-Session link is fine.`   [after W30]
- Rules: `- No em dashes anywhere in the repo, Kotlin sources included (a test string that needs one uses the unicode escape).`
- Layout: `Backlog, task files and the dispatch order: docs/backlog/ (start at DISPATCH.md). BACKLOG.md is generated by docs/backlog/src/assemble.py, never edited by hand.`
- Gotchas: `- A saved edit carries lookVersion; anything that changes how an old edit looks goes behind it.`   [true once W22 has merged; before that leave it out]
- Gotchas: `- Studio is compiled only with the studio flag; a release without it must hold no Studio classes (tools/check-studio-apk.sh).`
- Gotchas: `- Edits to tools/golden/golden.cpp go in the block for their topic, so two tasks do not collide.`   [after the BK-500 step]

Block B
- Commands: `- Colour pipeline checks: tools/colour/run-colour.sh (synthetic DNG fixtures, both looks).`   [after W21 and W22]
- Commands: `- Studio goldens: tools/golden/studio-golden.sh; look 2 shader checks: tools/golden/look2_checks.py (run inside run-golden.sh).`   [after W23]
- Gotchas: `- Guard tests (copy rules, manifest, back handlers) read the repository through the repo.root system property set in the Gradle test options.`   [after W16 and W27]
- Rules: `- Text the user sees follows docs/COPY.md (Australian English, sentence case, buttons start with a verb); the copy test enforces it.`   [after W27]
- Gotchas: `- The library keeps its grid order steady while the user is busy (OrderGate) and opens on RAW photos until the user taps All photos.`   [after W29]

## 5. Clean up
1. Branches and worktrees: do NOT delete any (PM decision, an outward action). `git branch --list` shows `claude/new-session-c5k3x9` and three `worktree-agent-*` branches with worktrees under `.claude/worktrees/` (old agent runs at bceb4ab, df01b5c, 2d1ce3a, all older than main). Leave them and mention them in the report so the PM can decide.
2. Remove committed scratch files if any: `git ls-files | grep -e '\.pyc$' -e __pycache__` must print nothing (BK-471 was fixed in bfee342).
3. The stray directory with a code fence in its name next to `Blend.kt` (BK-472) must not exist: `git status --short --untracked-files=all | grep '`'` prints nothing.
4. Nothing under `/tmp/golden` or `/tmp/golden-work` is committed or relied on by a task file other than through the scripts.

## 6. The report to Jai (dots, Australian English, no em dashes)
- Done: one line per merged task with its commit id and what was proved (host tests, goldens) and what was not.
- You need to do: the phone list from DISPATCH.md, in order, each as one tap-level step; the Expert RAW spike; the backup reinstall test; approve the look 2 knee; approve the CLAUDE.md lines.
- Still to do: the rest of DISPATCH.md in order with its estimates; the S3 to S5 decisions (BK-489 to BK-492).
- Issues: anything the final check printed as FAIL, anything marked "not compiled" that merged, any speed or look claim waiting for a Copy report.
