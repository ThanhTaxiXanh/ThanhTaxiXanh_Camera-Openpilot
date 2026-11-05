# V1.3.32 — Phone Camera2 hardware acceptance evidence (TTXPHONE01)

## Owner testing (Android only; no PC, A500S, model or purchase)

1. On your Android phone open the AI or Lite APK from GitHub Actions
   after a successful exact-head build. Grant CAMERA permission when asked.
2. Tap **CAMERA ĐIỆN THOẠI · KIỂM CHỨNG 30 GIÂY** on the home screen.
   Ensure the rear camera actually shows moving scenery, not a black image.
3. In the Camera2 screen, tap **GHI BẰNG CHỨNG CAMERA2 · 30 GIÂY**.
   While it runs, record the phone screen with Android's screen recorder.
   Slowly move the camera; do not drive while handling the phone.
4. Allow at least 30 seconds (prefer 35–40), then tap
   **DỪNG & LƯU BẰNG CHỨNG CAMERA2**.
5. Choose a storage location when Android prompts. Share the
   TTX-PhoneCamera2-30s.ttxphone.txt report and short screen recording
   for an independent review of criterion #8.
6. If CAMERA is denied, preview is black, a camera has UNKNOWN sensor
   clock, the output is NOT YUV420_888 640x480 or the report says
   INCOMPLETE_OR_UNVERIFIED, provide the report anyway; do not mark PASS.

## What the report measures

- Camera2 capture callbacks after a configured repeating request targeting
  both preview Surface and YUV_420_888 ImageReader.
- Reader image callbacks and actual sensor capture timestamps (REALTIME only
  is trusted for age comparison to SystemClock.elapsedRealtimeNanos).
- At most one frame per second, native crop/stride-aware YUV Image plane
  snapshot, then sampled YUV pixels and CRC32 checksum; no image content.
- 30-second duration, clock anomalies, valid/invalid sensor frames, copied
  image samples, unsupported formats and failures.

"READY_FOR_INDEPENDENT_REVIEW" means only *metadata sufficient to submit
for review*, NOT verified optical preview, trained ADAS model, model rights
or an accepted Gate A criterion. A reviewer must verify the actual preview
video and match the genuine phone-produced TTXPHONE01 artifact. A reviewer
may request a longer hardware trace or failure reproduction.

## Scope and safety

Existing A500S Direct/IJK transports and independent offscreen codec are
unchanged. The Phone Camera Activity already contains an optional RAW ONNX
diagnostic engine, but it needs the owner's legally authorized model.
This version adds evidence collection that works independently even when
ONNX is disabled. It additionally rejects sensor frames that become too
old while converting YUV pixels to ARGB.

No offline image, license-restricted model, precise address, or user data
is uploaded. The TTXPHONE01 text export is owner-initiated.

## Evidence policy

- The app cannot self-certify #8. The text and physical screen recording
  must be inspected independently.
- This does NOT prove #14 A500S YUV-to-AI, #15 camera geometry,
  #16 licensed independent model ground truth, #17 metric lead HUD, or
  #20 real uninterrupted 5-minute latency.
- Host unit tests and CI do not count as phone camera hardware evidence.
- Current acceptance remains Gate A 7/20=35%; Gate B 0/5.
