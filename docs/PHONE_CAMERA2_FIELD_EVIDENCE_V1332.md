# V1.3.32 — Android Phone Camera2: owner-led field evidence

## What is implemented

The Phone Camera2 activity already had a rear-camera SurfaceView preview, an
ImageReader delivering YUV_420_888 at advertised 640x480 and an optional RAW
ONNX diagnostic. This milestone makes it an independently accessible camera
source, and adds two explicit, owner-initiated evidence buttons:

1. **ĐO CAMERA2 60 GIÂY** — captures a bounded TTXPHONE01 text trace with
   repeating CaptureRequest callback counts, actual ImageReader callback
   counts, successfully copied 640x480 YUV frame count, late/invalid REALTIME
   sensor timestamps, gaps/stalls and Surface-valid observations. A trace
   can be sealed early (marked incomplete) and saved via Android's system
   document picker. It captures no GPS, camera URL, device serial, imagery,
   audio, model weights or driving inference.
2. **LẤY 4 KHUNG YUV THẬT** — opt-in snapshot from the actual Camera2
   ImageReader. Copies four YUV420_888 cropped/stride-aware samples, verifies
   dimensions and chronological local receipt times with existing
   LiveYuvEvidenceCapture, exports canonical TTXYUV01 with per-frame SHA-256
   via Android Storage Access Framework. File is approximately 1.84 MB and
   contains camera images; owner controls whether to share it.

Both operations work without the 70mai A500S Wi-Fi connection or an ONNX
model. A new direct button in Camera Source Settings opens Phone Camera2.

The phone Camera2 RAW ONNX path remains diagnostics-only: no licensed
driving model is bundled, the camera is not geometrically calibrated,
and speed/lead/warnings must remain disabled.

## Manual phone acceptance steps (#8 only)

Use V1.3.32 AI or Lite build (both support Camera2). On the Android phone,
open **Nguồn camera / Camera Source Settings**, select **MỞ CAMERA ĐIỆN
THOẠI**, and grant CAMERA permission. Point rear camera at a safe,
stationary indoor scene. Confirm that the preview displays live movement.
Tap **BẮT ĐẦU ĐO CAMERA2 · 60 GIÂY**; keep the app in the foreground for
one minute. When it finishes, tap the same button to save the TTXPHONE01
report. Tap **LẤY 4 KHUNG YUV THẬT**, let it collect four frames, tap again
to save the TTXYUV01 file. Record one non-sensitive screenshot or short
screen video that shows a clearly changing live preview and the controls.

Send these THREE items for independent analysis: (a) TTXPHONE01 text, (b)
TTXYUV01 binary, (c) screenshot/short screen recording. The user may obscure
faces/plates/identifying objects by choosing a neutral indoor subject.
TTXYUV01 contains genuine pixels, so do not share private scenes.

A blank preview, CAMERA permission denial, unsupported YUV dimensions,
sensor clock UNKNOWN, repeated capture stalls, or no valid TTXYUV01 are
useful failures to investigate, not reasons to declare PASS.

## Evidence interpretation and limits

- TTXPHONE01 repeating capture completion means Camera2 API processed a
  request; it does NOT independently prove real photons visible on the
  display. A physical screen review is mandatory.
- REALTIME sensor age is a monotonic sensor-to-handler measurement and
  cannot be equated to camera exposure-to-screen optical latency.
- An UNKNOWN sensor clock can still provide verifiable Camera2/YUV images,
  but must not enter the existing RAW ONNX freshness gate.
- TTXYUV01 frame hashes support corruption checks, not genuine phone
  provenance or independent model calibration by themselves.
- Physical #8 Gate A can only be promoted after consistent independent
  phone evidence; no CI status or simulated test can promote it.
- This milestone does NOT validate 70mai A500S sidecar (#14), its optics
  (#15), authorized ADAS model (#16), calibrated HUD (#17) or live
  A500S 5-minute optical latency (#20).

## Baseline

Gate A 7/20 (35%). Gate B 0/5. No new PASS claimed in this change.
