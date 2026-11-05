# V1.3.24 — End-to-end freshness fence at YUV→ARGB→ONNX ingress

## Verified code defects fixed

The optional 70mai A500S RAW diagnostic has two independent
freshness admission points:

1. A500sRawVisionBridge tested local H264 access-unit receipt age
   **before** YUV→ARGB copy. Converting an A500S 640×480 image may take
   CPU time. A pair which was fresh before conversion could be stale
   when it reached the inference worker.
2. OpenpilotInferencePipeline.offer checked only invalid/future
   timestamps, not already expired frames. It returned true and
   counted an expired frame as received, queued it, then left its
   rejection to the worker. This can mislead diagnostics and costs
   a scheduling wakeup in overloaded decoder sessions.

The corrected bridge checks the local monotonic timestamp again
**after** converting the new frame to ARGB. If the new frame has expired,
it rejects and clears the temporal state. If only the previous
frame expired while the current one is still fresh, it starts a fresh
pair from the current image, reporting a discontinuity instead of
combining two temporally incompatible frames.

If the downstream ONNX sink refuses a pair, the bridge clears its
previous-frame state rather than chaining the rejected image into
another pair. The pipeline now rejects expired/future/invalid source
timestamps **before** queue admission or received counter increments.
The worker continues to enforce the same freshness deadline after
preprocessing and model execution.

## Acceptance evidence

- Nine new deterministic host tests inject a slow converter using a
  synthetic monotonic clock. They verify post-copy expiration,
  previous-frame-only expiration, sink rejection, exact 550ms
  boundary, clock regression, future timestamps and producer-level
  ONNX rejection with no worker invocation.
- The pre-existing OpenpilotInferencePipelineTest now asserts that
  a stale pair is rejected directly rather than admitted provisionally.
- Neither a simulated slow conversion nor a unit test substitutes for
  a real A500S H264 stream/MediaCodec/ONNX trace.

## Safety boundary

This is the **RAW diagnostic** YUV→AI input path. No proprietary
weights, calibrated model, optical image capture timestamp, lane/lead
distance or vehicle warning is inferred by this patch. Input timestamps
are local H264 access-unit receipt clocks, **not** camera exposure
time or camera-to-screen latency.

#14 remains CODED and not independently live-verified. #16 model rights,
#17 HUD metric calibration and #20 optical delay all still require
independent evidence. Gate A remains **7/20 (35%)**, Gate B **0/5**.
