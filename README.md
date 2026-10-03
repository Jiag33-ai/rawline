# Rawline

Native Android RAW photo viewer and editor (Samsung S24 Ultra, Panasonic S5IIX RW2).
Spec: docs/SPEC.md. Working notes: CLAUDE.md. Timings: docs/PERF.md.

## How build and install work
1. Push to `main`. GitHub Actions builds the APK and creates a Release (`v0.1.<run number>`).
2. On the phone open the repo's Releases page, download `rawline.apk`, tap to install.
3. Settings shows version, build date and the LibRaw version.

Release signing uses Actions secrets `RAWLINE_KEYSTORE_B64`, `RAWLINE_KEYSTORE_PASSWORD`, `RAWLINE_KEY_ALIAS`, `RAWLINE_KEY_PASSWORD`. Without them CI builds a debug APK instead.

## Local build (cloud sandbox)
`./tools/setup-sdk.sh && source tools/env.sh && ./gradlew assembleDebug testDebugUnitTest`
