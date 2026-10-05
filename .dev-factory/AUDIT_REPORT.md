# Rawline audit report (Dev Factory Cloud, mode B)

Date: 2026-10-05. Commit: 5d84013 on `ccr-a2087c12-8vhdng`.

## 1. Audit transport health
- Files readable, Git works, shell works, Maven Central reachable but rate limits (HTTP 429) under load.
- Three optional read-only reviewers (engine, UI, data/ML) all returned. No `AUDIT_WORKER_FAILED`.
- No emulator, device or adb. Runtime device checks are `UNAVAILABLE`.

## 2. Repository map
Native Android RAW editor. Kotlin + Compose, C++17 engine (GLES 3.2, LibRaw), Room, LiteRT models. About 11k lines across app, 7 core modules and 7 feature modules. CI: golden shader tests, unit tests, signed APK to a GitHub Release.

## 3. Executed checks
| Check | State | Note |
|---|---|---|
| Spot-read of R2, R3, R4, R6 in code (main session) | PASS (findings confirmed) | Catalog.kt, Db.kt, Exporter.kt, jni_engine.cpp, RenderParams.kt |
| Android SDK install | BLOCKED_ENV, then fixed | Corrupted build-tools from an interrupted download; reinstalled |
| `./gradlew testDebugUnitTest assembleDebug` attempts 1 to 4 | BLOCKED_ENV | Maven Central 429 on dependency resolution, and a corrupted SDK install. Not a project failure |
| Same, retry loop (attempt 5) | NOT_RUN until it finishes | Result recorded below when done |
| Golden shader tests | NOT_RUN | Needs Mesa packages; to be probed |
| Phone speed and look | UNAVAILABLE | Needs Jai's Copy report |

## 4. Findings
Severity-ranked list is in the contract (R1 to R24). Counts: P1 x11, P2 x24, P3 x10 (static reading; line numbers approximate and re-verified by each worker before editing).

## 5. Confidence
Medium. Static only apart from four main-session confirmations. Build and test evidence arrives with the baseline.
