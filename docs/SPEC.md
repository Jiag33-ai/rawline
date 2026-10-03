# Rawline spec (condensed from Jai's mega prompt, 3 Oct 2026)

Native Android RAW viewer/editor for one user (Jai), Samsung S24 Ultra (Android 14+, minSdk 31), camera Panasonic S5IIX (RW2, JPG, HEIF). Also JPG, HEIC, PNG, DNG.
No PC: code is written in a Claude Code cloud sandbox, GitHub Actions builds the APK, Jai installs from a GitHub Release.
Personal sideload app. Lightroom-Mobile-like workflow, own name/icons/wording. Never copy Adobe assets.

## Goals
1. Speed: RW2 on screen sharp and swipeable under 150 ms.
2. Full editor: light, colour, curves, detail, masks, on-device AI select/remove/denoise.

## Stack
Kotlin + Compose (M3, dark), C++17/NDK (LibRaw built from source, shared lib; lensfun LGPL shared), OpenGL ES 3.2 RGBA16F pipeline, LiteRT for AI (ML Kit subject, MediaPipe people, sky seg, MobileSAM, LaMa, NAFNet-style denoise), Room, coroutines, plain constructor DI (no Hilt), own caches (no image loader lib).
Check current stable versions on official sites before adding deps. Record model/library licences in THIRD_PARTY.md.

## Instant RW2 viewing
Never demosaic to view. Tiers: (1) 320 px grid thumb from embedded JPEG, disk cached; (2) screen preview: parse TIFF/IFD in C++, pread embedded JPEG bytes, hardware decode at screen size; (3) zoom: LibRaw half-size in background if embedded JPEG too small; (4) edit base: LibRaw full linear 16-bit to RGBA16F, started on Edit press or 1.5 s dwell.
Prefetch next 3 / prev 2, LRU cache ~1/4 heap, bitmap reuse, background indexer (newest first) into Room, SAF + persisted URI, native pread, EXIF orientation honoured.

## Speed targets (S24U, S5IIX RW2)
Open from grid <150 ms; swipe prefetched <16 ms; swipe cold <250 ms; grid 120 fps; index 1000 files <60 s; Edit to first preview <1.2 s; slider to preview <16 ms; 24 MP JPEG export <3 s.
Debug overlay with per-tier timings + "Copy report" (timings, version, device, recent errors, last crash).

## Editing pipeline (GPU, linear, wide gamut)
raw (black/white, WB, demosaic, highlight recon) -> camera matrix -> lensfun -> geometry -> global tone/colour -> local masks -> detail (NR, sharpen) -> effects -> output transform.
Panels: Light, Curve, Colour, Colour mixer, Colour grading, Effects, Detail, Optics, Geometry, Presets, History. Before/after, histogram.

## Masks and AI (all on-device)
Brush, linear, radial, colour range, luminance range, depth range; add/subtract/intersect; per-mask adjustments. AI: subject, sky, background, people parts, tap-to-select object, remove (LaMa tiled), heal/clone, AI denoise. Never fake a model; stop and tell Jai if one cannot be used.

## Catalogue / export
Originals untouched. EditRecipe JSON (schemaVersion) in Room. Ratings, flags, filters, copy/paste edits, snapshots, XMP, zip backup. Export JPEG/PNG/TIFF16/AVIF, size presets, colour space, metadata, SAF destination, batch foreground service, share. Tiled 2048 px full-res render, same shader graph as preview.

## UI
Neutral mid-dark grey, one accent, big touch targets, edge-to-edge. Library grid, loupe, editor (photo top, tabs bottom, sliders), masking side sheet, landscape panels right. Inline cancellable progress, no modal spinners. Accessibility basics.

## Repo layout
app/, core/{model,data,cache,native,render,ml,ui}, feature/{library,loupe,editor,masking,remove,export,settings}, tools/, docs/, testdata/ (git-ignored). Core never depends on feature.

## Build/deliver
tools/setup-sdk.sh, ./gradlew assembleDebug + tests before every push, CI on push to main builds signed release APK into a GitHub Release (version + build number). Keystore as Actions secrets, never committed. Branch per task, merge when green, tag m1..m9. Version + build date in Settings. Crash handler saves last crash.

## Milestones (strict order; each: CI green, APK on Release, Jai installs, pastes Copy report, timings in docs/PERF.md)
1 Skeleton (modules, Gradle, CI, signing, theme, nav, JNI hello, LibRaw+lensfun as shared libs; LibRaw version in Settings)
2 Fast viewer  3 Raw decode + render engine  4 Core editing  5 Catalogue  6 Manual masks  7 AI masks  8 Remove + AI denoise  9 Export + polish.

## Working rules
Ask everything up front; plan then build; verify what can be verified and be honest about the rest (no phone access); measure not guess; check sources; keep lean; never fake; ask only when it is Jai's call; phone-friendly tap-by-tap instructions.
Reports: dot points, Australian English, no em dashes, sections Done / You need to do / Still to do / Issues, with timings and APK link.
