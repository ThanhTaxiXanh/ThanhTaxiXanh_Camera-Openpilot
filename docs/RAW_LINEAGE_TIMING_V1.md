# V1.2.9 — A500S RAW lineage, temporal continuity and local timing audit

## Scope

This is an OFFLINE-TESTED, RAW-only engineering safeguard. It does not
make the imported ONNX model, camera warp, detected lanes or lead distance
physically valid. All synthetic tests use fake model outputs; they do not
certify compatibility with xADAS or the 70mai 4.5.0 application.

The intended A500S Direct path is:

```
70mai A500S RTSP H264 AUs
    ├─ Main MediaCodec / Surface (preview untouched)
    └─ offscreen MediaCodec (independent) -> copied YUV_420_888
        -> 640x480 stride-aware ARGB
        -> consecutive time-bounded image pair
        -> two 512x256 virtual warps [UNVERIFIED CAMERA GEOMETRY]
        -> 12-channel studio-range FLOAT32 temporal YUV
        -> optional user-supplied ONNX Runtime model
        -> 1,576-output parser
        -> RAW diagnostics ONLY (no lane HUD/lead metres/warnings)
```

The host integration tests execute the bridge, the pure YUV converter,
both virtual image warps, temporal tensor packer, fake
`VisionInferenceEngine`, and the full raw decoder. An authorized
actual ONNX model, real A500S decoded YUV and a validated camera
homography are NOT available in CI.

## New generation/epoch barrier

- `OpenpilotInferencePipeline.invalidate()` increments an atomic
  generation and clears the latest pending input without waiting on
  ONNX, the camera callback or the main Android thread.
- A queued/in-flight result belongs to the generation at input offer
  time. Any later discontinuity invalidates that generation.
- A500S offscreen codec discontinuity (RTP damage, queue overflow,
  decoder reset) signals generation invalidation immediately, before the
  next image is decoded. Reconnect and YUV conversion errors invalidate too.
- The temporal bridge still resets at the first new YUV frame. This
  prevents crossing a damaged GOP even when two captures are separated
  by only a few milliseconds.
- The old temporal tolerance of 850ms is narrowed to 500ms, and the
  previous frame must still be within the 550ms arrival-age budget.
  The sidecar currently samples YUV no more frequently than ~300ms.
  These are engineering input boundaries, NOT physical exposure timing.
- Phone Camera2 RAW input now applies an analogous temporal gap check
  and invalidates on camera shutdown/capture conversion failures.

The callback checks the generation before delivery; concurrent callbacks
can race with a simultaneous epoch reset, so this remains a RAW diagnostic
implementation. The production UI also requires its session token and
ONNX request token to match. Safety-critical warnings are not enabled.

## Latency: what is and is not measured

`VisionPipelineTiming` samples a single monotonic timebase:

| Stage | Meaning |
|---|---|
| Receipt | Local elapsedRealtime on receipt of a compressed H264 access unit |
| Offered | Pair submitted to the inference input slot |
| Worker start | RAW worker dequeues the current pair |
| Model start | Preprocessing completed and ONNX is about to execute |
| Model completed | ONNX execution returned |
| Published | 1,576-head decode and geometry diagnostics completed |

Derived measurements are local **queue delay**, **preprocessing time**,
**model runtime**, **postprocessing time**, and **receipt-to-result age**.
If ordering regresses or receipt-to-result exceeds 550ms, the result
is suppressed as invalid/stale. `Metrics.lastTiming` remains diagnostic
even if a sample expired; epoch invalidation clears it.

These numbers are NOT camera optical exposure time, display scanout time,
or glass-to-glass latency. The owner's prior observation — ~3 seconds
in our app versus ~1 second in official 70mai 4.5.0 on the same phone —
is still UNRESOLVED. No improvement is asserted from pure-JVM tests.

## CI acceptance of this milestone

Synthetic host regression cases exercise:
- 640x480 YUV420 -> ARGB -> temporal pair -> both warps ->
  FLOAT32 [1,12,128,256] -> fake ONNX -> 1,576 parser.
- Temporal previous/current channel ordering and strict RAW-only result.
- An ONNX call blocked across an epoch invalidation: old output is dropped.
- Slow model execution that exceeds 550ms: nothing is published.
- One-slot overload: the newest pair replaces the older waiting pair.
- Source frame gap and previous-frame age checks, timebase reversal,
  queue/preprocessing/model/postprocessing phases.

Passing the unit tests and Lite/AI APK build does NOT satisfy the
mandatory #14–17 items or the >=90% pre-device acceptance gate. The
genuine A500S decoder/timestamp behavior, actual model throughput and
physical calibration still require separately authorized evidence before
asking the owner to test the phone again.
