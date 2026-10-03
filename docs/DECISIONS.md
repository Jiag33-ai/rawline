# Decisions

- LibRaw 0.22.2 is fetched by CMake from libraw.org with a pinned SHA-256, not a git submodule: the build sandbox cannot reach other GitHub repos. Shared lib `libraw.so`.
- lensfun is NOT in M1. It needs GLib, which has no ready Android build. Revisit in M3: options are building GLib with meson, or reading the lensfun XML database with a small own parser.
- Only arm64-v8a is built (S24 Ultra). Keeps CI fast.
- M1 modules: app, core:native, core:ui, feature:settings. Other modules from the spec are added when first needed, not as empty stubs.
- compileSdk/targetSdk 37 (androidx Compose 1.12 requires compileSdk 37). AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0.
- Until signing secrets exist, CI falls back to a debug-signed APK. Debug keys differ per runner, so updates may not install over the top until the real key is set.
