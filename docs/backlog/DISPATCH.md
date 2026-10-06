# DISPATCH: one worker at a time on main

Written 6 Oct 2026, updated 12:15Z against main c270b1e (Studio S1c merged: home with project grid and studio.db, the Develop and Studio switch, flatten export, flag and CI). Everything in the table below that follows S1c starts from that commit. All paths below are in `docs/backlog/` unless they start with `scratchpad/`. Durations are estimates of one worker's working time, taken from how much is already written and tested in each file and what is only specified. None is measured.

## How to read this
- One worker on main at a time. Each worker: new branch for the task, merge to main when CI is green, tag milestones where the file says so (tag pushes are rejected by the sandbox proxy, so a milestone tag is Jai's or the PM's to push).
- Before starting, the worker runs `git apply --check` on every patch named in its file. I ran those checks on 6 Oct 2026 at 7cd1b06 plus the working tree: W22 `docs/backlog/patches/w22-look2_engine.patch` and `editrecipe.patch`, W23 `docs/backlog/patches/w23-look2_shader.patch`, W28 `docs/backlog/patches/w28-model.patch` and `native.patch`, and both diff blocks inside `W09b-engine-residue.md` all apply (W09b and the others that only add new files have nothing to clash with). W23 over W22 does not apply as written (a `golden.cpp` hunk clashes); `docs/backlog/patches/w23-look2_shader_after_w22.patch` is the rebased one and is the one to use in this order.
- Commit messages: no `Co-Authored-By` line naming a model (CLAUDE.md rule, which takes precedence over a tool prompt that asks for one); the `Claude-Session` link is fine. 45 older commits carry such a line; history is not rewritten (BK-501).
- Honesty rule for every worker: the PR text says what was run (host tests, Mesa goldens) and what was only written. No speed or look claim without the phone's Copy report. Australian English, no em dashes, no model names in code, commits or docs.

## The order
| # | Task | File to give the worker | Entries | Est. | Why here | Exit check |
|---|---|---|---|---|---|---|
| 0 | Studio S1c | `W20-studio-s1c.md` | BK-398, BK-403 | DONE (c270b1e) | Merged | CI matrix with the flag off and on is green |
| 0b | Commit message check | `W30-commit-message-check.md` | BK-501 | 1 h | Tools and one CI job only, no overlap; protects every commit after it | 31 test checks pass (run on the host already); CI job green on main and red on a throw away branch with a trailer |
| 1 | S1b fixes (task id W28) | `W13-s1b-fixes.md` | BK-479, 480, 481, 483, 484, 487 | 5 h | Before anyone relies on the canvas; F1 to F3 of `review-s1b.md` bite on the first long stroke and the first palm. Re-run `final-check.sh` item for the BackHandler list after it (S1c added `StudioRoot.kt`, already in the W16 table) | 104 or more model tests plus `:core:studio-render:test` green; Studio goldens and the banded readback loop identical; Jai: pen with the hand on the glass, rotate with five layers |
| 2 | Platform compliance | `W16-platform.md` | BK-246, 426, 285, 120 | 4 h | PM decision: right after W28. Owns MainActivity, which W29 and W27b need | The two guard tests red before and green after the manifest line; 9 tests green; Jai: back gesture on every screen per the table in the file, insets on both navigation modes |
| 3 | Library first impression | `W29-library-first-impression.md` | BK-497, 498 | 5 h | PM decision: right after W16 (same ViewModel and screen) | 23 core/model tests green; Jai: first run opens on RAW photos with an All photos chip, a copied card does not make the grid jump, `grid_resort_count` in the Copy report |
| 4 | Colour verification | `W21-colour-verification.md` | BK-435, 436, 437, 441, 421, 423, 422, 021 (plan), 470 (measurement) | 3 h | Tools, docs and tests only; the red baseline that W22 turns green | `run-colour.sh` prints the table with every XFAIL listed; CI colour step green |
| 5 | Colour contract (look 2) | `W22-colour-contract.md` | BK-470, 438, 440, 437, 023 (first step) | 6 h | Owns the decode and curve files; adds `EditRecipe.lookVersion` that W23 needs | Look 1 numbers unchanged from main, look 2 all PASS; existing 19 scenes byte identical on `look=1`; Jai: three old edits look the same, then Update look |
| 6 | Golden harness key table | none; BK-500 is the spec | BK-500 | 1 h | W22 and W23 both edit the same lines of `tools/golden/golden.cpp` | 19 scenes still byte identical (`cmp`), no functional change |
| 7 | Automatic backups | `W06-auto-backups.md` | BK-142, 143, 478, 473 | 6 h | Touches `app/build.gradle.kts` and the version catalogue; nothing else is editing them now. The restore offer on first launch waits for W05 (not in this list) | 29 host tests green; Jai: backup visible in Files, uninstall, reinstall, Restore, says which target worked |
| 8 | Card import engine (no UI) | `W15-card-import.md` | BK-096 (engine), 344, 364, 474, 475 | 4 h | Own package, no overlap; the DNG probe feeds the Copy report | 14 host tests green; DNG fixtures committed; Jai: the Expert RAW spike |
| 9 | Engine residue | `W09b-engine-residue.md` | AE-051, AE-041 checks | 3 h | Touches `lowres.frag` and `engine.cpp` that W22 also changed, so after it | the check scripts pass, 19 scenes unchanged |
| 10 | Shader correctness, look 2 | `W23-shader-correctness.md` (section 4b, the after-W22 patch) | BK-459 remainder, 443, 032, 485 | 3 h | Needs `lookVersion` from W22 and the table from step 6 | 19 scenes identical on look 1; `look2_checks.py --key look --look 2` passes and `--look 1` fails; Jai: one HSL edit before and after Update look |
| 11a | Onboarding, copy rules, strings (pure part) | `W27-onboarding-help.md`, sections 3 to 5 and 8 | BK-392 to 396, 486 | 3 h | Adds `docs/COPY.md` and the test every later text must pass (W29's new text already passes it) | 32 tests green; the repository copy scan green |
| 11b | Onboarding screens and wiring | `W27-onboarding-help.md`, sections 6 and 7 | same | 4 h | W16 has merged by now, so MainActivity is free | Jai taps through a fresh install |
| 12 | Studio S2 | `W26-studio-s2.md` | BK-376, 377 (hand-off), 400, 414 | 10 to 12 h (two PRs) | After S1c and W28 | Eleven selection tests, schema v2 and kill tests, five mask goldens; Jai: Copy report for selection and mask timings |
| 13 | Lint and static analysis | none; BK-206 is the spec | BK-206 | 3 h | Writes a lint baseline over the whole tree, so it goes when nothing else is open | `./gradlew lintDebug` and detekt green with a baseline; new issues fail |

Total on this list: about 60 hours of worker time, one at a time (S1c is done).

## Decisions to write into the repo (by the worker that touches each file)
- Studio blend space (BK-488, decided by the PM on 6 Oct 2026): into `docs/STUDIO_STATUS.md` and `docs/DECISIONS.md`, by the S1c worker or W28 (whoever is next to edit them): "Studio blends in gamma encoded display space by default, as Photoshop does. A linear light option is stored per document (`BlendSpace.LINEAR`) and is off. The S3 reference and every S3 golden run in both spaces." The new project dialog gets the switch "Blend in linear light" (off) in S3, not before.
- Look 2 base curve knee 0.55 is provisional and flagged for Jai's phone review (W22).
- D1 of S1: layers are stored as a lossless deflate container; WebP is revisited in S2 with a device test (W26 D2).

## Decision before the next release
- BK-502 (P0): the CI release build has the Studio switch on (`-PstudioEnabled=true`). Until W28 and W16 have merged, either keep the switch hidden behind a Settings opt in (recommended, small) or build the release with the flag off. Jai's baseline test of what is out now is `PHONE-TEST-S1.md` (10 minutes); run it once now and again after steps 1 and 2.

## What Jai is asked to do, in order (so it can be batched)
0. After steps 2 and 3 (W16, W29): back gesture on each screen (table in W16 section 6); first run state opens on RAW photos; copy a card with a file manager and scroll while it indexes.
1. After step 1 (W28): S Pen with the hand resting on the glass; rotate with five layers open; paste the Copy report.
2. After step 3: open three old edits (nothing may change), then one bright sky photo and one warm indoor RW2 on look 2 (Update look, then Undo).
3. After step 5: make a backup, uninstall, reinstall, Restore; say which folder it found.
4. After step 6: three Samsung Expert RAW photos (day, night, 50 MP) in DCIM/Rawline and the Copy report with their compression numbers.
5. After step 8: one green landscape, one skin tone, one blue sky: HSL edit before and after Update look.
6. After 9b: tap through a fresh install.

## Not scheduled by the PM, with their dependencies (so nothing is forgotten)
- BK-499 (export time zone and sub-second tags) is small and unscheduled; BK-497 and BK-498 are now W29 (step 3).
- W01 CI and APK hygiene (about 2 h), W02, W03, W04 (catalogue off the main thread), W05 (restore preview), W07 (speed test), W08, W11 (memory plan), W12 to W14, W17 (card import UI, after W15 and W16), W24 (key rotation, after W06), W25 (AI quality): see `parts/next25.md` for their owns lists and order; W01 and W06 share `app/build.gradle.kts`, which one at a time already handles.
- Studio S3 to S5 risks to decide before those tasks are written: BK-488 (decided), BK-489 to BK-492 (P1), BK-493 to BK-496 (P2).
