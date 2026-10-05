# PROJECT_EXECUTION_CONTRACT.md

Status: `waiting_for_approval`
Contract version: `1`
Prepared from commit: `5d84013` (branch `ccr-a2087c12-8vhdng`)
Operating mode: B, Audit + improve
Prepared: 2026-10-05

## 1. Objective and outcomes
- Objective: fix the verified defects and spec gaps found by the audit so Rawline is safer with Jai's edits, steadier on the phone, and usable with accessibility tools. No new features.
- Primary user: Jai, Samsung S24 Ultra, Panasonic S5IIX RW2.
- Primary use cases: browse and swipe RAW, edit, mask, remove, export, all without losing edits or crashing.
- Success measures: every mandatory requirement below has a test or sandbox evidence; nothing about phone speed or look is claimed without Jai's Copy report.
- Definition of Done: all R-items below fixed on a task branch, unit tests and golden tests green in CI, APK built by CI, release notes list what to check on the phone, and Jai's Copy report requests are written out.

## 2. Scope
### Mandatory requirements
Source: three read-only audits (static reading; line numbers approximate). Each is re-verified by its worker before any change.

| ID | Requirement | Acceptance evidence |
|---|---|---|
| R1 | Model downloads verify a pinned SHA-256 and size before use; bad files deleted; https-only redirects; pinned PEOPLE pack name | Unit test with good, truncated and wrong-hash files |
| R2 | Room: `exportSchema=true`, schemas committed, real migration replaces destructive fallback for user tables | Migration test with a v1 DB keeping edits, presets, snapshots |
| R3 | Recipe parse failure never overwrites stored JSON; raw JSON kept; `schemaVersion` migrate hook and newer-version guard | Unit tests: corrupt, future-version, bad-enum recipes |
| R4 | Raw handle double free on upload failure fixed (Exporter / `engineSetSource`) | JNI/Kotlin test or harness; code review by manager |
| R5 | Failed full-res source upload keeps the half-size source and resets state; GL errors cleared first | Golden or engine test with forced failure where possible |
| R6 | `showMask` index maps to the visible-mask index | RenderParams unit test with a hidden mask |
| R7 | JNI entry points catch `bad_alloc` and check array lengths; init failure frees everything | Code review plus unit tests where JVM-testable |
| R8 | Prefetch and pending-queue native handles are freed on cancel, release and exceptions | Unit test with fake handles |
| R9 | Rotation keeps UI state (selection, tab, panel, zoom, export dialog); editor does not re-decode needlessly | Robolectric or Compose test; manual item for Jai |
| R10 | `RawSlider` exposes range, value and increment/decrement semantics; roles on buttons, tabs, switches, checkboxes; star and status descriptions | Compose semantics tests |
| R11 | AI mask, people, remove and denoise failures show a message with retry; first-use download shows progress | Compose test with a failing fake model store |
| R12 | Editor load shows placeholder and error state with Back; unknown photo id pops back; save uses try/finally; `Stage.ERROR` has Retry and Back | Compose tests |
| R13 | Export service queue race fixed; cancel not lost; one Denoiser per batch, released | Unit test on the queue; code review |
| R14 | Histogram uses a separate render target so slider drags do not reallocate targets | Engine test; phone timing is a Jai check, not claimed |
| R15 | Local-tone analysis includes vignette and heal overlay, per SPEC order | Golden test update with reviewed `--update` |
| R16 | TIFF carries an ICC profile (sRGB or P3) | Test reading the tag; image inspection |
| R17 | Export surfaces AI denoise failure instead of silently skipping | Unit test |
| R18 | Single shared bitmap cache budget; thumb disk cache atomic, bounded, keyed by photo key; mask and patch stores hash keys, write atomically, recycle bitmaps; patch alpha round trip lossless | Unit tests |
| R19 | Delegate fallback expires; heavy models off the shared executor; model download checks connectivity, backoff, cleans `.part` | Unit tests |
| R20 | CI: `pull_request` trigger, non-blocking model link check, concurrency group, publish-only `contents: write`, release note names the real key | Workflow run on the task branch |
| R21 | Touch targets 48 dp with roles; hardcoded colours moved to Lr tokens; Material text fields, dialogs and progress replaced by Lr versions; settings top inset fixed | Compose tests, grep gate for stray literals, screenshots |
| R22 | Dead and unguarded controls fixed (Crop help, Reset all confirm, Remove undo/clear enablement, sort shows current) | Compose tests |
| R23 | Lens profile parser caches failures only once, parses off the main thread, reads cropfactor and aspect where present | Unit tests; real lens file check flagged as unverified |
| R24 | THIRD_PARTY.md gaps closed (LGPL relinking note, transitive licences, upstream links) | Review |

### Constraints / must-not-change
| ID | Constraint | Proof |
|---|---|---|
| C1 | Originals untouched; recipe JSON stays backward compatible | Migration and recipe tests |
| C2 | Params layout mirrored in `params.h` and `RenderParams.kt` | Existing mirror test |
| C3 | Engine calls stay on the GL thread | Review |
| C4 | No model named in commits, code or docs; no fake models | Review |
| C5 | Look of the unedited raw stays as fitted (base curve) | Golden references unchanged except R15, reviewed |

### Explicit exclusions
- Any claim about phone speed or look without Jai's Copy report.
- The committed sideload keystore stays (DECISIONS.md accepted it; removing it would stop updates installing over the top). Only the release-note wording is fixed. Jai can choose to rotate it later.
- New features, tablet layout, shared-element return, AVIF, depth-range mask (already documented gaps).
- Tag pushes (the sandbox rejects them).

## 3. Research and material assumptions
| ID | Question | Evidence | Checked | Freshness trigger | Decision |
|---|---|---|---|---|---|
| A1 | Maven Central returned 429 in the sandbox | Baseline build log | 2026-10-05 | Any new build | Retry with fewer workers; CI is the build of record |
| A2 | Audit line numbers are approximate | Audit reports | 2026-10-05 | Before each fix | Worker re-reads code first |
| A3 | Model hashes and sizes | Must be computed from the real files | not yet | Before R1 | Download and hash in the sandbox; if a host is unreachable, report as a blocker |
| A4 | Real Lumix S lens file normalisation | DECISIONS says unverified | not yet | R23 | Stays labelled unverified unless a real lens file is available |

## 4. Current system / baseline
- Architecture: 8 core and 7 feature modules, about 11k lines of Kotlin and C++.
- Build/test status: baseline build in the sandbox is still running at time of writing; results are added to the Graphify evidence, not assumed. CI on main is the last known good build.
- Known defects: the 24 items above, plus the gaps already in DECISIONS.md.
- Performance baseline: none measured on the phone (docs/PERF.md).

## 5. Target architecture
No structural change. Additions: Room schema files, a model hash manifest (`tools/models/models.json`), a shared cache budget object, a small saver set for UI state. Errors surface as inline messages, never silent. Native entry points return 0 on failure; Kotlin treats 0 as failure and frees what it owns.

## 6. UI/UX
Follows the locked docs/UI_SPEC.md. Changes only where listed (R9 to R12, R21, R22). Loading, empty, error, disabled and offline states added for editor load, AI runs and model download.

## 7. Game design
N/A, this is a photo editor.

## 8. Audio
N/A, the app has no audio.

## 9. Visual/assets
N/A for new art. Colour literals move to existing Lr tokens plus a few new named ones.

## 10. Work breakdown and dependency DAG
| Task | Owner role | Depends on | Paths | Parallel? | Worktree? | Boss Acceptance Bar |
|---|---|---|---|---|---|---|
| T0 Baseline build and test green in sandbox | Build | none | whole repo (read) | no | no | Tests run and results recorded |
| T1 Data safety: R2, R3, R18 | Data | T0 | core/data, core/model, core/cache | yes | yes | Migration and recipe tests pass |
| T2 ML and downloads: R1, R19 | ML | T0 | core/ml, tools/models | yes | yes | Hash tests pass |
| T3 Engine and JNI: R4 to R8, R14, R15, R17 | Native | T0 | core/native, core/render | yes | yes | Golden and unit tests pass |
| T4 Export: R13, R16, R17 | Export | T3 for R4 | app ExportRunner, Exporter | after T3 | no | Queue and TIFF tests pass |
| T5 UI state and errors: R9, R11, R12 | UI | T0 | feature/*, app | yes | yes | Compose tests pass |
| T6 Accessibility and tokens: R10, R21, R22 | UI | T5 | core/ui, feature/* | after T5 | no | Semantics tests and grep gate pass |
| T7 CI and docs: R20, R23, R24 | Release | none | .github, docs, THIRD_PARTY.md | yes | no | CI run green |
| T8 Integration regression | Executive | T1 to T7 | all | no | no | Full tests, golden, CI green on merged branch |
| T9 Customer Reality Panel and release | Executive | T8 | n/a | no | no | Section 13 gates |

### Hierarchy
Main session is executive. Flat subagents (nesting depth is 1 here) own T1 to T3, T5 and T7 in parallel worktrees; each gets a context packet from Graphify. A separate manager agent attacks each result. T4 and T6 run in the main session because they touch shared files. A workflow script is not used; the fan-out is small.

## 11. Quality plan
- Unit/component: `./gradlew testDebugUnitTest`, plus new tests listed per requirement.
- Shader: `tools/golden/run-golden.sh`; `--update` only for R15, with a before/after image check.
- Integration: sandbox build `assembleDebug`; CI builds the APK.
- Visual/device: no phone in the sandbox. Compose screenshots where Robolectric allows; everything else is a Jai check.
- Performance: no claims. Jai gets a short checklist keyed to PERF.md timers.
- Security/privacy: model integrity, https-only, no secrets in Graphify or logs.
- Regression: full suite on the integrated branch before merge.

## 12. Graphify plan
- Nodes: requirement R1 to R24, decision, task T0 to T9, test, defect, evidence, research.
- Edges: requirement to task to test to evidence to release.
- Context-packet budget: about 4k tokens per worker.
- Freshness: code-derived nodes go stale when their paths change in Git.
- Conflicts: competing heads kept and resolved explicitly.
- State lives in `.dev-factory/graphify/`; the derived index is not committed.

## 13. Customer Reality Panel
Personas (all Jai-relevant): a photographer culling 500 RW2 on the bus; one-handed editing at a shoot; low-vision user with TalkBack and large text; someone on patchy mobile data using AI tools for the first time; a returning user who rotates the phone mid-edit.
- Evidence: sandbox screenshots and Compose runs for journeys; phone Copy report from Jai for speed and look.
- Gates: default thresholds from the skill apply. Anything needing the phone is marked "pending Jai", never passed by assumption.

## 14. Git/integration/release
- Base: `main`. Work branch: `ccr-a2087c12-8vhdng` (given for this session); worker worktrees branch from it and merge back.
- Merge to main only when CI is green, per CLAUDE.md.
- Tags: not pushable; milestone noted in release notes.
- Artifact: `rawline.apk` built by CI into a GitHub Release.
- Fallback: `release/android/` folder with notes, SHA, checksums.
- Pushes allowed to the task branch only. No force pushes, no deletions.

## 15. Risks and hard blockers
| Risk | L/I | Mitigation | Release consequence |
|---|---|---|---|
| Maven rate limits block sandbox builds | M/M | Fewer workers; rely on CI | Slower loop, no weaker checks |
| No phone in sandbox | H/M | Compose and golden tests plus Jai checklist | Speed and look stay "pending Jai" |
| Golden update hides a real regression | L/H | Manager reviews before and after images | Reopen R15 |
| Model host unreachable for hashing | M/M | Report; keep size check plus https until hash is available | R1 partial, stated plainly |
| Migration bug damages user edits | L/H | Test with a v1 fixture; keep the old fallback only for unknown versions | Block release |

## 16. Approval
Implementation may begin only after explicit approval of this contract (or an explicitly identified revised version).
