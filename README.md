# Rawline

Native Android RAW photo viewer and editor (Samsung S24 Ultra, Panasonic S5IIX RW2).
Spec: docs/SPEC.md. Working notes: CLAUDE.md. Timings: docs/PERF.md.

## How build and install work
1. Push to `main`. GitHub Actions builds the APK and creates a Release (`v0.1.<run number>`).
2. On the phone open the repo's Releases page, download `rawline.apk`, tap to install.
3. Settings shows version, build date and the LibRaw version.

Release signing uses Actions secrets `RAWLINE_KEYSTORE_B64`, `RAWLINE_KEYSTORE_PASSWORD`, `RAWLINE_KEY_ALIAS`, `RAWLINE_KEY_PASSWORD`. Without them CI signs the release APK with the committed sideload key (`app/rawline-sideload.jks`, not secret) so updates still install over the top. Each release also carries `rawline.apk.sha256` and a list of the commits since the last release.

## What is in it
Library (grid, filters, ratings, flags, labels, copy/paste/sync edits, backup), viewer (fast embedded preview, prefetch, zoom, info), RAW editor (light, curves, colour, mixer, grading, effects, detail, optics with lens profiles, geometry, masks, AI masks, remove/heal/clone, AI denoise, presets, history, snapshots) and export (JPEG, PNG, 16-bit TIFF, sizes, sharpening, Display P3, metadata, batch service, share).

## Tests
`./gradlew testDebugUnitTest` (JVM unit tests), `./tools/golden/run-golden.sh` (shaders on Mesa vs reference images; needs `libgles2-mesa-dev libegl1-mesa-dev` and Pillow), `./tools/test-preview-parser.sh` (embedded preview parser on `testdata/*.RW2`), `./tools/models/fetch.sh` (model URLs still live).

## Local build (cloud sandbox)
`./tools/setup-sdk.sh && source tools/env.sh && ./gradlew assembleDebug testDebugUnitTest`
