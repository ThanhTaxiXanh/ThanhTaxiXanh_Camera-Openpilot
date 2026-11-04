# V1.3.3 — H264 video-freeze detection and bounded RTSP/MediaCodec recovery

## Symptoms addressed

A 70mai A500S RTSP socket or RTP packet counter can remain active even
when no complete H264 picture is assembled, the decoder produces no new
picture, or Surface receives no new decoded output. FPS snapshots alone
may also miss an intermittently stuck source. V1.3.3 uses the **local
progress state of one Direct playback session** to diagnose these
conditions. No physical A500S benchmark was collected in this release.

The experimental Direct freshness profile remains optional. The default
Smooth profile and both IJK profiles are not retuned by this release.

## DirectStreamHealth

The pure-Kotlin, synchronized watchdog records the latest local
`SystemClock.elapsedRealtime()` for these actual milestones:

1. Accepted RTP packet from the negotiated RTSP transport.
2. Complete H264 access unit accepted into the Direct preview queue.
3. Non-empty decoded MediaCodec output buffer.
4. Successfully released non-empty output buffer to Android Surface.

When an already established stream stops progressing, the watchdog
classifies `RTP_SILENT`, `AU_STALLED_WITH_RTP`,
`CODEC_OUTPUT_STALLED` or `SURFACE_RELEASE_STALLED`.
It allows up to 12 seconds after PLAY to acquire initial decoder
output. During established playback it uses local 6-second complete AU
silence and 5-second decoder/Surface output silence thresholds.

Separately, the audit records PTS duplicates and backward PTS. Isolated
negative PTS steps may be valid H264 B-frame decode order and are
**diagnostic only**, never individually dropped. If the highest
session-local media PTS fails to advance for more than 3 seconds and
at least 8 new AUs arrive without advancing it, the watchdog reports
`SOURCE_PTS_FROZEN`.

Missing `MediaCodec.OnFrameRenderedListener` callbacks do NOT trigger
a reconnect because some vendors omit or batch these callbacks. The
watchdog uses actual successful `releaseOutputBuffer(index,true)`
calls for the Surface progress check, not a callback whose delivery is
platform-specific. It does not attempt to infer screen scanout or
camera exposure time.

A matching watchdog failure causes the established RTSP run loop to
exit, close the session and arrange a **clean session reconnect**.
It never discards arbitrary compressed reference P-frames or tries
to invent a valid H264 decoder picture.

## Bounded recovery and explicit native release barrier

Prior logic reset `retryCount` based on total playback uptime after
60 seconds. During a long-running stream, each failure after that
point could reset the count again, allowing unlimited short-interval
reconnect attempts. `DirectReconnectBudget` instead keeps at most
three recent restart timestamps in a rolling 60-second window.
A fourth failure in that window stops Direct safely. Once old
failures actually age out, subsequent recovery is possible.

The app also rejects duplicated recovery requests while a release is
pending and checks that a network callback belongs to the *current*
DirectRtspPlayer instance, not only the broad Activity generation.

`DirectRtspPlayer.cleanup()` now provides a completion latch which is
signaled after stopping/releasing the old MediaCodec and quitting its
codec HandlerThread. A detached Java thread waits up to 5 seconds for
this latch before the next Direct player is scheduled. Android UI,
MediaCodec callback and RTP receive threads are never blocked waiting
for the native codec. If release cannot be confirmed in time, the
application stops the session rather than open a possibly overlapping
second hardware decoder.

This is local lifecycle safety, not a proof that every vendor decoder
releases all native memory immediately. JVM CI cannot emulate an
actual MediaCodec device driver.

## Regression test coverage

- Healthy synthetic RTP→AU→codec→Surface frame progress without any
  vendor frame-rendered callbacks.
- RTP packets arriving continuously but no complete H264 AUs.
- Complete AUs arriving while decoder outputs freeze.
- Decoder outputs arriving but no successful Surface releases.
- Repeated/unchanging source PTS for over three seconds, without
  triggering false resets on occasional B-frame PTS regressions.
- No first RTP media, clock regressions and startup grace.
- A ~20-minute synthetic steady frame stream with constant-size
  watchdog state.
- Three reconnects within one rolling minute, fourth retry blocked;
  old failures expire by their *individual* time, not total uptime;
  invalid clocks and simulated long-running sessions.

Both Lite ARM64 and AI ARM64 APKs must compile and pass JVM tests.
An approved real A500S capture would be needed to verify the
hardware decoder lifecycle and actual visual delay.

## Safety and acceptance

This milestone does not change `VisionSafetyGate`, physical lead
distance, model calibration, lane geometry, xADAS model weights or
the disabled ADAS warnings. A build PASS cannot prove the owner's
previous ~3-second TTX versus ~1-second official 70mai 4.5.0
glass-to-glass comparison is now fixed.

The >=90% predevice gate remains below its minimum 18/20 PASS
requirements. See `docs/PREDEVICE_90_GATE.md`.
