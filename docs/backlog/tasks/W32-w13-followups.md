# W32 W13 follow-ups: hover that lapses, leave that waits for the save, retry gap, half copied duplicates, GL view detach, export while backgrounded

Status at writing: main b5b06ac (W13 merged). Entries: BK-508 (R1), BK-509 (R2), BK-510 (R3), BK-511 (R4, R5, R6), BK-512 (R7 to R10 bundle, optional). Findings and proofs are in `review-w13.md`; read its sections R1 to R6 first. Runs after W31 (shares `CanvasScreen`, `StudioSession`) and before the Studio switch is shown to anyone beyond Jai. Estimate: 3 h for the parts below that have patches, plus 2 h for the specified edits.

What was run: the two patches below apply with `git apply --check` to a `git archive b5b06ac` tree. With them applied, the live studio-model and studio-render sources compile with Kotlin 2.4.10 on the host and 164 tests pass (the worker's 121 model tests and 27 session tests, plus 3 router tests and 4 session tests added here). NOT compiled: `StudioGl.kt` and `CanvasScreen.kt` (Android and Compose), so `w32-ui.patch` and the section 5 edits are written, not built. The worker compiles them and says so in the PR.

## 1. What each change does
- R3 (BK-510) `InputRouter.onHover`: near hover keeps the palm rule alive for `HOVER_STALE_MS` (2 s) from the last hover event instead of for ever; an exit still gives `PALM_GRACE_MS` (600 ms). Compose sends hover moves while the pen is near, so the rule stays on while it should and lapses by itself after a lost exit.
- R6 (BK-511) `StudioSession.lastFailureAt`: the retry gap (5, 10, 20, 40, 60 s) counts from the failure, not from the start of the failed save.
- R4 (BK-511) `StudioSession.flushAndWait(timeoutMs)`: saves now and returns the save state when the save finished or failed (or the state when time ran out). `CanvasScreen.leave(force)` calls it on `Dispatchers.IO`; if the result is not SAVED it stays on the canvas and shows the existing "Leave without saving?" dialog (its Leave button calls `leave(true)`, which only flushes).
- R5 (BK-511) `Catalog.duplicate`: a failure anywhere in the copy deletes the new folder and rethrows.
- R1(a) (BK-508) `StudioGl.viewDetached`: when not released, `glReady = false`, so jobs wait in `pending` and run when the next surface exists (otherwise they are queued on a dead GL thread and a `gpuCall` waits its whole timeout).

## 2. Decisions (no questions left, except the one marked PM)
- D1 The 5 s wait in `flushAndWait(5_000)` is real time; a save that takes longer than 5 s counts as unsaved and the dialog is shown (the person can still choose Leave).
- D2 No new strings: the existing dialog text is used for the leave-after-failed-flush case.
- D3 R1 is a measurement first: the counters (section 5.1) go in with this task and Jai reads them from the Copy report before anyone removes `movableContentOf`. The fix (b) of R1 is only done if `studio_gl_create` shows more than one creation across five rotations.
- D4 R2 (PM decision 6 Oct 2026): option (a) of the review, in this task: keep the screen on while an export runs, wait for the foreground inside the exporter, honest message. Option (b), export on its own EGL context in a service, stays with S9 (BK-410).

## 3. Order of work
1. `git apply --check` then apply `patches/w32-core.patch` (model, session, tests). Run `:core:studio-model:testDebugUnitTest` and `:core:studio-render:testDebugUnitTest`.
2. Apply `patches/w32-ui.patch` (StudioGl detach, CanvasScreen leave); build `assembleDebug` with `-PstudioEnabled=true` and without.
3. Section 5 edits (counters, export while backgrounded).
4. Phone checks (section 6).

## 4. Patches (in the scratchpad, copy into the worker's checkout)
- `docs/backlog/patches/w32-core.patch` (Catalog.kt, InputRouter.kt, StudioSession.kt, StudioSessionTest.kt hook `onWrite` in MemFs, new W32Test.kt and W32SessionTest.kt).
- `docs/backlog/patches/w32-ui.patch` (StudioGl.kt, CanvasScreen.kt).
- Re-check `git apply --check` on both at the worker's base (they apply at b5b06ac; W16 and W31 also edit CanvasScreen and StudioRoot, so expect the `leave` hunk to need a hand merge if W31 changed `leave`, see W31 section 5.5: W31 removes the thumbnail wait from `leave`; keep W31's thumbnail change and this task's flushAndWait part).
- Tests in the patch: `aMissedHoverExitLapsesAfterTwoSeconds`, `hoverMovesKeepTheRuleAlive`, `exitStillGivesTheShortGrace`, `theRetryGapCountsFromTheFailureNotFromTheStartOfTheSave`, `leavingReportsAFailedSaveInsteadOfSilentlyDroppingTheWork`, `leavingAfterASuccessfulSaveReportsSaved`, `aDuplicateThatFailsHalfWayLeavesNoCopy`.

## 5. Specified edits (not compiled)
### 5.1 R1 counters (BK-508)
- `StudioGlView`: `@Volatile var attachCount`, `detachCount` incremented in `onAttachedToWindow` and `onDetachedFromWindow` (call `super` as before). `StudioGl`: `createCount` incremented in `onSurfaceCreated` after a successful `StudioNative.init`.
- Report them through the existing Copy report hook used for `studio_texture_mb` as `studio_gl_attach`, `studio_gl_detach`, `studio_gl_create` (names from the same prefix list as the other studio gauges; add them to the report test list).
- Reading: after five rotations of one open project, create 1 and detach 0 means the movable surface works; create 6 means the view is detached by each rotation, then do R1(b): a single `Box` hosting the surface at one call site, panels switched around it.

### 5.2 R2 export while backgrounded (BK-509), option (a)
- `ExportSheet`: while `running` is true set `FLAG_KEEP_SCREEN_ON` on the activity window (`DisposableEffect(running)`, clear on dispose), so a screen timeout does not pause the view.
- `StudioGl`: `@Volatile var isPaused` set in `paused()` and cleared in `resumed()`; `StudioSession.isBackgrounded()` returns it.
- `StudioExporter.flatten`: when `renderStrip` returns null and `session.isBackgrounded()`, wait for foreground in 1 s steps (up to 10 minutes in total, honouring `cancel()`), then retry the same strip once; if it still fails, report FAILED with the existing path.
- `ExportSheet`: add a result text "Export paused while Rawline was in the background. Open it again to finish." shown when `StudioExporter.Result.FAILED` follows a background wait that ran out; the generic message stays for other failures. Strings follow docs/COPY.md.
- Host test: `FakeGpu` returns null while `paused`, a strip loop retries after `resumed`, and gives up after the limit (use a short limit via a parameter).

## 6. Phone checks (Jai; PHONE-TEST-S1.md has the same steps)
- Rotate the open project five times with a stroke between; Copy report shows `studio_gl_attach`, `studio_gl_detach`, `studio_gl_create`. Draw straight after each rotation: the stroke appears and undo works.
- Pen with the hand on the glass, then take the pen away from the screen edge without lifting it over the screen: a finger paints again within 2 seconds.
- Fill the phone's storage (Files, a big dummy copy), edit, press Back at once: the "Leave without saving?" dialog appears and nothing is lost on Stay.
- 12 MP project, PNG export, press Home at 30 percent, return after 20 seconds: export finishes; after returning at 90 seconds it still finishes (waiting limit is 10 minutes).

## 7. Report text for the PR
List what ran on the host (tests with counts), what was only written (UI and GL edits, section 5), and the counters Jai read. No claim about rotation or export speed without the Copy report.
