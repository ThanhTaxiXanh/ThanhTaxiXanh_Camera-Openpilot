# V1.3.34 — A500S Direct Surface + offscreen YUV 5-minute evidence

## Why this change matters

The original TTXLIVE01 recorder only observed Surface-release progress
(or native IJK output FPS). A healthy 5-minute preview trace could
wrongly appear to validate the secondary A500S YUV path while the
MediaCodec sidecar was halted, resetting or returning unsupported frames.

The enhanced recorder preserves old Video/IJK TTXLIVE01 behavior and
optionally attaches BOTH Direct Surface and secondary decoder callback
counts with a fixed 1-second interval and bounded rows.

## Behavior

- Arm a dual trace only when the Direct backend is playing and
  YUV A500S: BẬT is selected before tapping THU BIÊN BẢN VIDEO 5 PHÚT.
- Lock that requirement for the entire trace. Tap disable, missing
  counters, counter regressions, decoder resets/errors since arming
  or sustained 4-second YUV stall fail the report even if Surface
  video remains active.
- The cumulative reset/error count is baselined at trace start to
  avoid attributing old decoder failures to the current test.
- The TTXLIVE01 rows include yuv_progress, yuv_count, sidecar_resets,
  sidecar_errors, raw_pairs and onnx_results. Counts alone do not
  validate model licensing, geometric or metric lane/lead correctness.
- No camera images, RTSP URLs, credentials, GPS or audio are recorded
  in TTXLIVE01. The row cap stays bounded at 480.
- IJK and Direct video-only traces explicitly mark YUV NOT_ARMED.

## Owner Android phone experiment — stationary vehicle

Only test while parked. Connect the phone to the owner's own 70mai
A500S Wi-Fi and keep both devices powered.

1. Install Lite ARM64 V1.3.34 from successful main build. Start
   xADAS · MEDIACODEC for the A500S.
2. Confirm video Surface is visible. Tap YUV A500S offscreen to ON.
   Verify the YUV OFFSCREEN frame count increases.
3. Tap LƯU 4 KHUNG YUV A500S, then save TTXYUV01 via the Android
   document picker. Reconfirm Surface AND YUV both resume afterwards.
4. With Direct AND YUV on, start THU BIÊN BẢN VIDEO 5 PHÚT. Keep
   app in foreground for five minutes. Do not switch transport or
   disable YUV. Save TTXLIVE01 after it finishes.
5. Record 15–30 seconds of screen video proving preview movement and
   YUV counter activity. To measure true optical latency, separately
   record the physical changing scene and phone screen SIMULTANEOUSLY
   with a second camera; a clock/flash target is useful.

Send TTXLIVE01 text, TTXYUV01 binary and non-sensitive screen/optical
video. TTXYUV01 contains real scene pixels: select a privacy-safe
scene, do not publish identifying content.

## Acceptance boundaries

These artifacts target real A500S secondary MediaCodec YUV continuity
(#14 partial) and 5-minute local video stability (#20 partial).
They do NOT verify an independently licensed model, real ONNX
inference, calibrated lane/lead results or optical near-1s delay.
No single counter trace is sufficient for Gate B PASS.

CI tests use synthetic counters. The owner's physical A500S recordings
must be independently reviewed. Gate A **8/20 (40%)**,
Gate B **0/5** until additional evidence is established.
