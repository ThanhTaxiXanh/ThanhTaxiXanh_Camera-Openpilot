# V1.3.21 — TTXLIVE01 5-minute local-progress evidence

## Gap corrected

The Direct RTSP player already exposed RTP, H264 access-unit, MediaCodec,
Surface release and synthetic-cadence counters. The IJK player exposed
output/decode FPS. However there was no owner-triggered, timestamped,
downloadable five-minute continuous capture usable for a real phone
assessment. A single instant or a single 10s rolling window cannot
demonstrate five minutes of uninterrupted playback.

This version adds the on-phone button **THU BIÊN BẢN VIDEO 5 PHÚT**.
Start playback on the A500S Wi-Fi, then press the button. The one-second
UI status ticker samples a bounded in-memory audit. On completion or
manual interruption the same button becomes **XUẤT BIÊN BẢN TTXLIVE01**
and lets the owner save a UTF-8 text file using Android Storage Access
Framework. It does not copy video, camera passwords, Wi-Fi/GPS coordinates,
RTSP URLs, model data or private frames.

## Trace contract and failure conditions

- Format: TTXLIVE01, Android elapsedRealtime (monotonic) local milliseconds.
- Backend is held to the selected Direct or IJK mode for the whole test.
- Direct progress is the native MediaCodec **Surface release counter**.
- IJK progress requires the native player reporting output FPS >= 2 and
  isPlaying. These telemetry values do NOT prove photons were displayed.
- A gap of more than 2500 ms between 1s samples, >4s without observed
  local frame progress, a counter reset, any reconnect, backend change,
  invalid clock or premature stop invalidates LOCAL 5-minute continuity.
- Capture is bounded to 480 sample rows and cannot grow indefinitely.
- The report is available even for a failed test, with exact failure codes.
- After >300 seconds of noninterrupted local progress, report status
  OBSERVED_LOCAL_ONLY means **only** local telemetry supports continuity.

### What the report NEVER proves

TTXLIVE01 always includes:

    field_gate_20=NOT_VERIFIED
    optical_latency_seconds=NOT_MEASURED
    independent_hardware_review=REQUIRED

The phone receives compressed H264 after the camera captured the scene.
RTP arrival, local H264 PTS, MediaCodec output/release time and IJK FPS
cannot reveal camera exposure time, encode queue duration, SurfaceFlinger
composition or display scanout latency. Even an unbroken 300s TTXLIVE01
cannot independently certify physical camera-to-glass latency.

## Physical Gate B #20 protocol (owner-controlled)

1. Connect to the **actual 70mai A500S** and use the same handset, streaming
   mode and IJK/Direct profile recorded in TTXLIVE01. Do not change modes
   during recording. Ensure the camera and its original preview are visible
   where a separate second phone/camera can simultaneously see them.
2. Record a second video with BOTH the physical scene containing an
   **externally generated changing millisecond timer** (or identifiable
   numbered light transitions) and the app's display clearly in one
   continuous frame. The external reference timer must be physically
   visible to A500S; do not add a virtual timestamp to decoded video.
3. Run the TTXLIVE01 recorder for at least 5 minutes. If the app detects
   reconnect, freeze or missing samples, retain the failed trace rather
   than re-labeling it PASS.
4. Independently inspect several corresponding unique changes in the
   optical reference and the phone's display in the second recording.
   For each event calculate the positive frame-offset / recording FPS;
   preserve raw selected frame numbers, resolution/FPS, camera clock
   uncertainty, median, P95 and maximum optical delay. Do not infer
   latency from RTP PTS or the local release-to-render telemetry.
5. Attach the TTXLIVE01 text, the separate continuous optical recording,
   a redacted event-match table, declared phone/camera/app version and
   reviewers' independent conclusions to a **private** evidence archive.
   Do not put unredacted street plates or faces on public GitHub.
6. The independent reviewer checks duration, absence of frozen visual
   content, no connection recovery (if uninterrupted is required),
   camera-to-glass delay vs the chosen near-one-second acceptance window,
   measurement uncertainty and source continuity.

Only independent review of this physical package can mark the mandatory
field #20 PASS. An uncalibrated visual timer without external synchronization
is not sufficient. The GitHub unit tests use synthetic clocks only.

## Regression scope

Eleven pure JVM tests cover healthy synthetic 300s traces, early owner
stop, reconnect, counter regression, missing samples, frozen frame
progress, IJK FPS, backend change, invalid timestamps, and private
data absence. Lite and AI builds are checked by GitHub Actions.

**Engineering Gate A remains 7/20=35%; mandatory field Gate B remains 0/5.**
