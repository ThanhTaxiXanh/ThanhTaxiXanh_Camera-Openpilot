# 2026-11-05 — Independent review of owner-provided Phone Camera2 evidence

## Purpose and evidence custody

Scope: engineering criterion **#8 Phone Camera2 preview and YUV420 source**
only. The owner submitted a Camera2 TTXPHONE01 plain-text report,
TTXYUV01 binary (four frame sample), and a mobile screen recording.
These materials were independently parsed/visualized and cross-checked
in the project conversation on 2026-11-05. They are retained by the
owner/conversation, **not committed to this public repository**.
The evidence is not an A500S camera recording.

File integrity fingerprints (SHA-256 of the raw uploaded bytes):
- TTXPHONE01, 3,090 bytes:
  `b5f873e62aaa8006fe9859ba549517875ad981658381042b5645f72f417c186c`
- TTXYUV01, 1,843,376 bytes:
  `6abf06b1b5d1d69174c262f785d8cfb1a7ba0552bbe913545d6a85d833c40e70`
- Screen recording MP4, 13,869,828 bytes:
  `3bdeb586eccca4272b0fbf5439d9db2cef35cd43b15a4ecbd8b867be27a5b584`

These hashes establish the identity of the files reviewed, **not**
a cryptographically signed device/app origin or capture time. No
model/phone serial/IMEI, site, street name, geographic coordinates,
license plates, faces, camera credentials, video frames or raw YUV
pixels are reproduced in this repository.

## TTXPHONE01 consistency review

- Format header `TTXPHONE01`, `source=ANDROID_CAMERA2`,
  `lens=BACK`, `sensor_timestamp_source=REALTIME`,
  `api_level=33`, `requested_yuv_size=640x480`.
- `end_reason=DURATION_REACHED`, `elapsed_ms=60120`.
- `repeating_request_configured=true`; 60 report rows.
- 1,791 capture-completed callbacks (approximately 29.79/s).
- 1,792 ImageReader YUV callbacks (approximately 29.81/s).
- 66 successful 640x480 native YUV plane snapshots (sampled copies,
  NOT a claimed 66 FPS), 0 copy failures.
- 0 invalid sensor clock samples; 0 stale sensor images; peak
  REALTIME sensor-to-handler receipt age 80 ms.
- `faults=NONE`; all 60 rows report `surface_valid=true`;
  monotonic row times and maximum inter-sample gap 1,004 ms.
- The report labels its own verdict `OBSERVED_LOCAL_ONLY`, not a
  physical independent-review result. The report alone is insufficient.

## Independent TTXYUV01 binary inspection

- Exact 16-byte header: `TTXYUV01`; big-endian 640x480, count=4,
  flags=0.
- Each of the four frames has 307,200 Y bytes, 76,800 U bytes,
  76,800 V bytes and a SHA-256 of Y+U+V. **All four embedded digests
  independently recomputed and matched**, without trailing bytes.
- Monotonic boot-relative receipt timestamps:
  123807368, 123807549, 123807567, 123807597 ms;
  deltas 181, 18, 30 ms (all within 500 ms).
- The decoded YUV images contain visible scene detail and real
  non-constant luminance (per-frame Y standard deviation around
  56.7-56.8). Consecutive image mean absolute luma differences were
  approximately 5.07, 1.84, 2.50 levels, so these are not exact
  duplicate still frames. The saved camera sensor orientation is
  rotated relative to portrait preview as is typical for Camera2.
- This proves that the provided TTXYUV01 is syntactically valid and
  contains recoverable nonblank YUV imagery; SHA alone is not
  cryptographic proof of live camera origin.

## Independent screen-video inspection

- Uploaded H.264 MP4: approximately 19.79 seconds, 512x1136
  screen, 30 fps (592 video frames).
- Sampled screenshots through the clip show the TTX Phone Camera2
  activity with an active rear-camera scene; outdoor background,
  relative objects and framing move consistently with the owner
  panning the phone, rather than a black or persistently frozen preview.
- The YUV-decoded scene visually corresponds to the same setting
  shown by the preview (allowing for sensor orientation).
- The video covers only about 20 seconds, not the entire 60-second
  recording; the remaining duration is evidenced by app trace
  counters and cannot be independently certified as optical video
  continuity for each second.

## Criterion verdict and boundaries

**#8 Gate A engineering: PASS (8th of 20).** Directly relevant Camera2
preview and physical YUV_420_888 output have been independently reviewed
using three complementary real-device artifacts, alongside actual
Camera2 code and deterministic lifecycle/format JVM tests in CI.
This is device evidence for the *tested Android phone*, not universal
compatibility with every handset.

**Gate B stays 0/5.** This footage and TTXYUV01 originate from the
phone camera, NOT 70mai A500S. They cannot promote #14 (A500S YUV→ONNX),
#15 (A500S calibrated intrinsics), #16 (licensed ground-truth model),
#17 (metric ADAS HUD) or #20 (>5-minute A500S optical latency).

The 80 ms figure is the app's monotonic sensor-to-callback receipt age,
NOT exposure-to-screen optical delay, and says nothing about whether
the ADAS neural model is accurate or safe for public-road warnings.

Source provenance limitation: the video/report do not embed a
cryptographically verifiable APK hash, so the exact installed app
revision cannot be authenticated solely from these files. Existing
V1.3.32/1.3.33 Camera2 source and exact-head CI nevertheless support
the scoped engineering claim. A later device change still warrants
reverification. No raw camera media is committed.
