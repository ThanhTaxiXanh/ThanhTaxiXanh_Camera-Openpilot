# V1.3.2 — 10-second video-cadence and accumulating media-backlog audit

## Motivation

The owner measured about **3 seconds** of visual delay in TTX Direct and
IJK previews against about **1 second** in official 70mai 4.5.0 on the
same phone and A500S. Earlier probes established that small video-cache
values and nearly normal output FPS **do not** exclude a multi-second
delay. A decoder can output 25 FPS while always showing a picture from
two or three seconds behind the currently received stream.

This release adds a **non-invasive, purely diagnostic** 10-second
throughput and relative media-timeline monitor. The observation cannot
prove the camera's exposure time or the driver's actual screen lag.

## Five separately observed local checkpoints

`StreamCadenceAudit` records timestamped events on Android's
`elapsedRealtime()` clock, with a session-local, unwrapped RTP PTS.

| Stage | Instrumentation | What it actually confirms |
|---|---|---|
| AU_RECEIVED | Whole H264 AU accepted to bounded Direct queue | App assembled and admitted a compressed AU |
| CODEC_INPUT | `MediaCodec.queueInputBuffer()` succeeded | A compressed AU entered codec input |
| CODEC_OUTPUT | Nonempty `onOutputBufferAvailable` | Codec produced a decoded output |
| SURFACE_RELEASE | `releaseOutputBuffer(index,true)` succeeded | Buffer was submitted for Surface rendering |
| RENDER_CALLBACK | A PTS-matched frame-rendered listener event | Vendor reported a render callback; not scanout |

The app displays individual 10-second FPS estimates, the source RTP
PTS-derived AU cadence (an *estimate*, not an H264 VUI declaration),
the most recent valid PTS-based source→decoder and source→Surface
**media-timeline gaps**, source AU silence duration, invalid clock
events and the diagnostic status.

A 2,500ms absence of AU receipts after warmup is classified as
`SOURCE_AU_STALLED`. This is **not** proof that the physical camera
stopped sending; the transport, read thread and source can all cause
a missing local AU.

The audit distinguishes these cases:
- `RTP_RECEIPT_RATE_MISMATCH`: rate of locally assembled AUs differs
  substantially from RTP PTS cadence; does not assign blame to Wi-Fi.
- `CODEC_INPUT_LAGGING`: codec input throughput under 80% of
  the AU assembly throughput.
- `CODEC_OUTPUT_LAGGING`: decoded output throughput under 80%
  of codec input throughput.
- `CODEC_OUTPUT_MEDIA_BACKLOG`: decoded FPS may be healthy,
  **but the newest decoder PTS is more than 1,000ms behind the
  highest PTS locally accepted from the RTP stream**.
- `SURFACE_RELEASE_LAGGING`: successful Surface submit throughput
  under 80% of decoder output throughput.
- `SURFACE_RELEASE_MEDIA_BACKLOG`: normal release rate,
  but the latest released PTS trails the latest locally received
  source PTS by more than 1,000ms.
- `RENDER_CALLBACK_UNCONFIRMED`: fewer than 60% as many PTS-matched
  render callbacks as buffers released. Certain devices omit/batch
  callbacks, so this is **not** proof of a display bottleneck.
- `FLOW_OBSERVED`: local rate checks and relative media-PTS gaps
  were within these diagnostic thresholds. **This is not a safety
  pass or a real-world low-latency certificate.**
- `WARMUP`: fewer than 10 seconds of source observations or
  insufficient samples for reliable rate comparison.

All rate thresholds are diagnostic heuristics, not validated A500S
safety acceptance limits. The source stream might be ~20–25 FPS;
the app must NOT assume it is 30 FPS just because tests simulate 30 FPS.

## Relative media gap is not exposure-to-glass latency

The PTS-based gap is

```text
(max RTP AU PTS received - max PTS decoded/released) / 1,000 [ms]
```

It describes the **relative span of locally received-but-not-yet-
decoded or released media timestamps**. It does not include
sensor exposure, the camera's encoder, upstream RTSP backpressure
before the app reads packets, SurfaceFlinger composition or actual
display scanout.

If the camera encodes video 2s behind reality and the TTX decoder
plays all received media promptly, this monitor can report a
0ms internal gap **while glass-to-glass is still ~2s**.

Timestamps arrive on asynchronous RTSP, codec and render callbacks;
the monitor is synchronized and bounds histories to at most 2,048
events per stage and a 10-second rolling window. It neither records
frames nor changes Surface scheduling or MediaCodec options.

## Synthetic CI and non-goals

The JVM suite exercises steady 30 FPS, an upstream RTP/receipt
clock-rate mismatch, codec input bottlenecks, codec output bottlenecks,
a Surface submit bottleneck, unavailable render callbacks, 3-second
media-gap accumulation **despite otherwise normal FPS**, source
pauses, invalid clocks and a ~20-minute simulated session.

The tests use generated timestamps, not a physical A500S stream.
No model weights/optical calibration are inferred, and no unverified
ADAS warning, lead distance or lane drawing is enabled.

The owner requires >=18/20 engineering criteria before a new phone
test, including separately mandatory model/ground-truth criteria.
These still cannot be marked PASS based on deterministic synthetic CI.
See `docs/PREDEVICE_90_GATE.md`.
