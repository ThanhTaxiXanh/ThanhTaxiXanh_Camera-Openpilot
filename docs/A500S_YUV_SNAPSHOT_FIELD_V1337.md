# V1.3.37 — Save real A500S YUV even when secondary codec stalls

## Independent field evidence and the actual user failure

On 2026-11-06 the owner uploaded a real Android screen recording
(size 62,335,279 bytes; duration 106.27 seconds; H264 512x1136 at
30fps; 3,188 video frames) of V1.3.36 connected to their own 70mai
A500S RTSP stream. Raw uploaded-video SHA-256:

`c549c3965f36c96a8b918167b7270e645ec376f2eada054136f4ba3668933ebc`

The screen recording and imagery (including sensitive location and
people/vehicles) are NOT committed to this public repository.

The main Direct MediaCodec video remained visibly moving. In the early
diagnostics the optional YUV decoder had delivered 4 frames 640x480
over a much longer observation interval; after the owner tapped
"LƯU 4 KHUNG YUV", the button remained "ĐANG THU" and Android's
save-file picker never appeared. Later warnings include:

`YUV offscreen: IllegalStateException Offscreen input unavailable after 3 drain/wait attempts; await next IDR`

The code has a separate strict TTXYUV01 path requiring four actual
frames no more than 500ms apart. A decoder reset, GOP interruption
or larger frame spacing cancels that sequence without any export.
The previous UI did not always reset its active-capture button after
an asynchronous GOP reset. That matches the observed failure to
open the save-file picker.

This recording does NOT prove the secondary decoder can produce
a healthy, continuous 30fps video stream. The software-codec
starvation remains a separate performance blocker.

## Implementation: a DIFFERENT, explicitly non-temporal file format

When the owner enables Direct A500S YUV, the app now keeps at most
four **real decoded 640x480 YUV420p frames** in process memory only
(approximately 1.85MB), preserving actual local receipt timestamps
even when those frames are far apart or the auxiliary decoder later
stalls. Once four have been captured, additional frames are not
copied, avoiding unbounded allocations or image storage.

The new button "XUẤT YUV THẬT n/4 · TTXYUV02" exports what already
exists (1, 2, 3 or 4 real frames) using Android's standard
ACTION_CREATE_DOCUMENT picker. If no real YUV frame was decoded,
it refuses to fabricate a file and displays the observed decoder
name, error count and exhausted-input count.

TTXYUV02 structure:
- ASCII magic `TTXYUV02` (8 bytes).
- Width/height u16 big-endian = 640/480.
- Frame count u16 = 1..4, flags u16 = 1 (NON_TEMPORAL).
- For each frame: actual local elapsedRealtime receiptMs i64,
  canonical Y 307200 bytes, U 76800 bytes, V 76800 bytes and
  SHA-256 over Y+U+V.

Unlike TTXYUV01, the new format **allows receipt gaps >500ms**.
It is never consumed by the temporal YUV→ARGB→12-channel→ONNX
pipeline. The offline TTXYUV01 reader explicitly rejects the new
magic, and its strictly monotonic <=500ms contract is unchanged.

The original "LƯU 4 KHUNG YUV" still requests four contiguous
frames and exports TTXYUV01 if available. When interrupted by a
damaged GOP, the button now resets and explains why a strict
TTXYUV01 file cannot be created.

The buffered images are never uploaded, committed, recorded to
persistent storage or transmitted before the owner explicitly
chooses Export. Disabling YUV/closing playback clears the cache.

## Next device test (parked)

1. Install Lite V1.3.37 from the verified post-merge CI build.
2. Connect to the owned 70mai A500S and run XADAS · MEDIACODEC;
   leave ONNX OFF.
3. Turn on YUV A500S. Watch the new TTXYUV02 button change
   from 0/4 toward up to 4/4 if real offscreen frames arrive.
4. Tap "XUẤT YUV THẬT n/4 · TTXYUV02" when n>=1. Choose a save
   location and send the .ttxyuv2 for independent hash/pixel review.
5. If 0/4 persists or there are frequent decoder resets, record
   a 20–30s screen clip that includes YUV OFFSCREEN metrics.
   No fabricated TTXYUV02 should be offered at 0/4.
6. Only when sidecar outputs are continuous, try strict
   "LƯU 4 KHUNG YUV A500S" (TTXYUV01).

Note: TTXYUV02 contains potentially identifying scene pixels;
use a privacy-safe stopped-vehicle scene and share deliberately.

## Acceptance

This is an evidence-collection and feedback improvement, NOT
proof that the native decoder has been fixed. It cannot advance
#14 or #20, establish continuous temporal pairs, calibrated camera
coordinates, licensed model weights, or exposure-to-display latency.
CI tests are synthetic and fail-closed. Gate A 8/20 (40%),
Gate B 0/5 pending new physical evidence.
