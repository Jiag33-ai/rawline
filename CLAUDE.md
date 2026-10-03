# Rawline working notes

Spec: docs/SPEC.md. Decisions: docs/DECISIONS.md. Timings: docs/PERF.md.

## Rules
- Jai is phone-only. Report in dots: Done / You need to do / Still to do / Issues. Australian English, no em dashes.
- Never claim speed/look results without the phone's Copy report. Never fake models or skip tests.
- Branch per task, merge to main when CI is green, tag milestones.
- Do not name any model in commits, code or docs.

## Commands
- Sandbox toolchain: `tools/setup-sdk.sh` then `source tools/env.sh`
- Build: `./gradlew assembleDebug` ; tests: `./gradlew testDebugUnitTest`

## Gotchas
(add as found)
