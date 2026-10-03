# Third party software, data and models

## Libraries linked into the app
| Item | Version | Licence | Notes |
| --- | --- | --- | --- |
| LibRaw | 0.22.2 | LGPL 2.1 / CDDL 1.0 | Built from source by CMake (fetched from libraw.org, SHA-256 pinned) and linked as the shared library `libraw.so`. |
| LiteRT (TensorFlow Lite) + GPU delegate | 1.4.2 | Apache-2.0 | On-device model runtime. |
| ML Kit Subject Segmentation (Google Play services) | 16.0.0-beta1 | ML Kit terms | "Select subject". The model is delivered by Google Play services. |
| AndroidX (Compose BOM 2026.09, Room, Navigation, Lifecycle, Activity, Core, ExifInterface) | see gradle/libs.versions.toml | Apache-2.0 | |
| Kotlin, kotlinx.coroutines | 2.4.20 / 1.11.0 | Apache-2.0 | |

## Data
| Item | Licence | Notes |
| --- | --- | --- |
| lensfun lens database (L-mount lenses extracted to `core/render/src/main/assets/lensfun/lenses_lmount.xml`) | CC BY-SA 3.0 | Only the data is used, read by our own parser. The lensfun library is not linked: it needs GLib, which has no ready Android build. Source: Ubuntu package `liblensfun-data-v1` 0.3.4. Copyright notice: `assets/lensfun/COPYRIGHT.txt`. |
| Base tone curve (`engine/base_curve.h`) | own | Fitted against embedded camera JPEGs of Panasonic DC-S5M2X sample files from raw.pixls.us (CC0). |

## AI models (downloaded on first use, not bundled)
All inference is on the phone. The files are only downloaded (HTTPS GET) into the app's private storage. URLs are in `core/ml/.../ModelStore.kt` and `tools/models/models.json`.

| Feature | Model | Source | Licence | Size |
| --- | --- | --- | --- | --- |
| Select sky | SegFormer-B0 fine-tuned on ADE20K 512 (class 2 = sky) | Qualcomm AI Hub public release v0.63.0 (`segformer_base-tflite-float.zip`) of `nvidia/segformer-b0-finetuned-ade-512-512` | Apache-2.0 (wrapper); weights per NVIDIA SegFormer licence, personal use here | 14 MB |
| People parts | MediaPipe selfie multiclass 256x256 | storage.googleapis.com/mediapipe-models | Apache-2.0 | 16 MB |
| Select object | MobileSAM (encoder + decoder) | Qualcomm AI Hub v0.63.0 (`mobilesam-tflite-float.zip`) | Apache-2.0 (MobileSAM, Segment Anything) | 40 MB |
| Remove | LaMa-Dilated | Qualcomm AI Hub v0.63.0 (`lama_dilated-tflite-float.zip`) | Apache-2.0 | 170 MB |
| AI denoise | NAFNet (SIDD denoise) | Qualcomm AI Hub v0.63.0 (`nafnet_denoise-tflite-float.zip`) | MIT | 430 MB |
| Select subject | ML Kit Subject Segmentation | Google Play services | ML Kit terms | n/a |

No model was converted by us: the official TFLite releases are used as published, so `tools/models/` only fetches and verifies them.
