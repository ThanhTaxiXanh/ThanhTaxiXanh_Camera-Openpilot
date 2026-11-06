# Independent review — A500S field bundle, 2026-11-06

## Source, custody and privacy

The owner uploaded four artifacts together: 158.733-second Android screen
video, a real A500S single-frame TTXYUV02, a 300.548-second TTXLIVE01
Direct local playback trace, and a 128.551-second TTXGPIP01 phone trace.
They were independently read, parsed, verified and cross-compared in the
project conversation on 2026-11-06.

Only SHA-256 hashes and deidentified technical statistics are committed.
**Do NOT upload original video, image, raw TTXYUV02 or screenshots**
to this public repository: on-camera video overlays can contain GPS
and identifying scene details.

Original file SHA-256 and size (bytes):
- Owner screen recording MP4: 98,736,810 bytes
  `7255e314abcad304d196911069622a3397faddbcc07dc19a930d6d313f3553ea`
- 1-frame TTXYUV02: 460,856 bytes
  `26f50fc1b584b7470fef8a4b7bea1cd8af9397998ac4f44a3d293f8f117e89e7`
- TTXGPIP01: 8,459 bytes
  `f9f2ed56e7888dc06818f21146c54da2adfdbbce5e9be22c2d1c05ba497585ba`
- TTXLIVE01: 18,073 bytes
  `b45a326372c9ccee8102ae52413d2e327abf47cc53324d22d44c72909616c01c`

SHA-256 identifies reviewed exact file bytes, not a signed capture chain,
authenticated APK version, licensed AI model or optical timestamps.

## A500S TTXYUV02 — one genuine recoverable frame

- Exact magic `TTXYUV02`; big-endian width 640, height 480,
  count **1**, flags **1 = NON_TEMPORAL**. No trailing bytes.
- Single local receipt elapsedRealtime timestamp (no photographic
  exposure timestamp). Full planar Y 307,200, U 76,800, V 76,800 bytes.
- Recomputed embedded per-frame SHA-256(Y+U+V) matches.
- Reconstructed 640x480 RGB via YUV420p to image (kept privately).
  Frame contains structured nonblank color scene and visually matches
  the recorded A500S Surface preview's scene. Y statistics:
  mean **111.772**, stddev **67.806**, range 6–255.
- No second image, so no motion cadence or temporal pair can be
  independently established from this TTXYUV02.
- This is physical evidence of a functional **single offscreen YUV
  image output and user export**, not sustained YUV or ONNX.

## On-device recording — offscreen decoder starvation still present

- Screen video H.264 512x1136, 30fps nominal, 4,762 video frames,
  total 158.733 seconds. Moving A500S Surface video visible after
  initialization and through sampled middle/later times.
- At approximately recording second 18, secondary
  `c2.android.avc.decoder [SOFTWARE]` shows **0 delivered YUV**,
  4 decoded outputs, 7 resets, 4 errors, 4 exhausted input waits
  and 3 stale YUV outputs; primary RTP/Surface is still active.
- At approximately second 28, the same software sidecar reports:
  **0 delivered YUV**, 7 decoded outputs, 13 resets, 7 errors,
  7 exhausted input waits and 6 stale YUV outputs.
  Displayed native fault: `Offscreen input unavailable after 3
  drain/wait attempts; await next IDR`.
- The extracted non-temporal file shows that the sidecar did later
  deliver and export **one** real image, not four contiguous frames.
- No evidence that software-only H264 decode can sustain the A500S
  RTP frame cadence in parallel with the hardware Surface on this
  handset. Frequent sidecar reinitialization is a concrete blocker.
- The 30ms total native input polling policy (3 x 10ms) may be too
  aggressive for asynchronous software AVC. V1.3.38 only tests a
  **bounded** 8 x 20ms software retry policy, while preserving
  the original 3 x 10ms hardware policy. Retaining the 550ms
  local-age/epoch fail-closed gate is mandatory.
  This mitigation is NOT independently validated on the phone.

## TTXLIVE01 — 5-minute LOCAL Surface continuity, not optics

- Header: backend DIRECT; `a500s_yuv_sidecar_required=false`.
- `DURATION_REACHED`, 300,548ms; 292 per-second samples,
  283 `PROGRESS`, 9 `NO_PROGRESS`, all reconnect flags false,
  zero reconnect events, faults NONE.
- First sampled Surface release count 240; last 5,346:
  increase **5,106 over 299.599s**, approx **17.04 sampled
  Surface releases/s** (not optical FPS or 30fps guarantee).
- Longest inter-sample gap was 1,056ms; zero two consecutive
  NO_PROGRESS samples. All observations are app telemetry.
- `local_five_minute_trace=OBSERVED_LOCAL_ONLY`;
  `field_gate_20=NOT_VERIFIED`, `optical_latency_seconds=NOT_MEASURED`.
- The separate 158.7-second screen recording DOES NOT optically
  cover the entire 300.5-second local trace.
- No YUV, ONNX, optical exposure-to-display delay or calibrated
  metric HUD were measured in this 5-minute trace.

## TTXGPIP01 — GPS present, PiP rejected (not PASS)

- 128,551ms duration, `OWNER_STOP`, 125 samples.
  All 125 say GPS requested/permission/provider true and fresh
  GPS speed present; view Surface true, DIRECT backend, metric
  units (kmh_m), brand visible.
- Two `PIP_REQUESTED` actions at ~112.2 and 119.5 seconds,
  both followed by `PIP_REQUEST_REJECTED`.
  Zero PiP entry and exit confirmation callbacks. Direct backend
  intentionally does not provide IJK-only PiP in current app.
- No change to Imperial/metric units or settings persistence is
  observed by this trace. GPS availability and denial/stale cases
  are not the same as verified full criterion #19.

## Strict acceptance findings

- **Gate A stays 8/20 (40%)**, IDs 1,2,5,8,9,10,13,18.
  #3 still needs independent RTSP fallback, #19 actual IJK PiP
  and settings tests.
- **Gate B stays 0/5**. #14 only has a single A500S YUV physical
  frame, no temporal ONNX inference; #20 only local 300s progress,
  no independent near-1-second optical scene→screen test.
- Build success or one screenshot must not promote any entire
  criterion. Inference remains uncalibrated and experimental.
