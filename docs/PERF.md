# Performance log

Targets (S24 Ultra, S5IIX RW2). **Nothing here is measured on the phone yet.** The in-app Settings > Copy report gives the real numbers.

| Action | Target | Phone result |
| --- | --- | --- |
| Open a photo from the grid, preview visible | < 150 ms | not measured |
| Swipe to next photo (prefetched) | < 16 ms | not measured |
| Swipe to next photo (cold) | < 250 ms | not measured |
| Grid scroll | steady 120 fps | not measured |
| Index 1,000 RW2 files | < 60 s | not measured |
| Press Edit to first editable preview | < 1.2 s | not measured |
| Slider move to updated preview | < 16 ms | not measured |
| Export full-res 24 MP JPEG | < 3 s | not measured |
| Library: grid does not move while indexing (`grid_resort_count`, in the Library block of the report) | 0 until the finger has been up for 1.5 s, then at most 1 | not measured |
| Library: capture times read for the newest 300 new RAW files at a scan (`scan_exif_ms`) | not set | not measured |
| Studio: open project (24 MP JPEG import) | < 1.5 s | not measured |
| Studio: stroke frame time | < 16.6 ms (8.3 ms at 120 Hz) | not measured |
| Studio: input to pixel | not set | not measured |
| Studio: 20 layer 24 MP pan/zoom frame | < 16.6 ms | not measured |
| Studio: export flatten 24 MP | < 6 s | not measured |
| Studio: autosave after a stroke | not set | not measured |
| Studio: export flatten 12 MP JPEG (`studio_export_ms`) | not set | not measured |
| Studio: home first paint (`studio_home_first_paint_ms`) | not set | not measured |
| Studio: memory high-water mark | under 2.2 GB | not measured |

Timers recorded by the app (names in the report): `tier2_total_ms`, `tier2_parse_ms`, `tier2_read_ms`, `tier2_decode_ms`, `swipe_cold_ms`, `grid frames`, `grid jank_frames(>25ms)`, `index_total_ms`, `edit_decode_ms`, `edit_upload_ms`, `edit_first_frame_ms`, `frame_render_ms`, `full_decode_ms`, `export_render_ms`, `ai_*_run_ms`, `heal_*_ms`, `denoise_total_ms`.

## Sandbox numbers (not the phone)
Measured on the build machine, only to show where work goes; the phone is a different CPU/GPU.
- LibRaw full decode of the 24 MP sample RW2 on the 4 core build machine: 3.0 s single threaded, 1.6 to 1.9 s with OpenMP (the Android build uses OpenMP too; the phone number is still needed). Half size: 0.71 s to 0.54 s. Output is bit identical.
- Peak memory of the same decode (host RSS): 420 MB to 242 MB full, 144 MB to 98 MB half size, after converting LibRaw's pixel block in place.
- Embedded preview parser on 38 MB RW2: 5 to 12 microseconds.
- LibRaw half-size decode of a 24 MP RW2 on the x86 build machine: about 0.9 s single thread. The phone budget for Edit to first frame is 1.2 s, so this is the number to watch.
- GL pipeline on Mesa llvmpipe (CPU rasteriser) at 960 x 640: about 35 ms per frame. A real GPU is far faster, but this is unmeasured.
- Models on the same x86 machine (CPU): MobileSAM encoder 1.3 s, decoder 0.1 s, SegFormer sky 0.25 s, LaMa 512 px tile 5.8 s, NAFNet 256 px tile 1.8 s. The app tries the GPU delegate first.
- Studio compositor (S1a, Mesa llvmpipe, golden scene studio_blend3, 4 layers, 256 x 192 output): holds 1.25 MB of textures; no speed number was taken. The Studio rows above stay unmeasured until Jai pastes a Copy report from the phone (budgets are in docs/STUDIO_SPEC.md 2.17).
- Studio S1b timers now in the Copy report (all "not measured" until the phone): `studio_frame_ms` (render plus display pass, per frame), `studio_stroke_stamp_ms` (addStamps), `studio_commit_ms` (readback plus bake), `studio_autosave_ms` (encode plus writes), `studio_input_to_pixel_ms` (event time to the frame that shows it; recorded, no target), gauges `studio_texture_mb`, `studio_history_mb`, and a Studio section (canvas size, layers, memory guard state, autosave state). Sandbox: the brush goldens run on Mesa llvmpipe; no speed number was taken.

## Checks that need the phone
Sliders under 16 ms; grid 120 fps; swipe timings; Edit to first frame; export time; AI mask readiness (< 2 s after editing starts, tap-to-select < 300 ms); remove a person-sized object from 24 MP in under 10 s; 30 minute soak test.
