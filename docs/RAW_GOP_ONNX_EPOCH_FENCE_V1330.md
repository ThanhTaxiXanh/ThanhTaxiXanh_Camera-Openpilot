# V1.3.30 — Nonblocking RAW YUV/ONNX source GOP epoch fence

## Race fixed

A500S Direct RTP/H264 loss or decoder recovery previously set a flag
(rawPairResetPending) to clear the temporal ARGB frame at the next YUV
callback. While offscreen MediaCodec converted the current native
YUV image to 640x480 ARGB pixels, that background callback could
already have consumed the reset flag. A simultaneous discontinuity
could invalidate ONNX and still allow the ongoing ARGB copy to enqueue
a pair from the old GOP in a newly active ONNX queue epoch.

Holding the bridge monitor for an immediate reset from the RTP thread
would instead block compressed video receipt on pixel conversion.

## Changes

- A500sRawVisionBridge.signalDiscontinuity() increments an AtomicLong
  without taking the YUV conversion monitor.
- MainActivity calls it on RTP AU damage, native sidecar reset or
  other Direct discontinuity before signaling the pending bridge reset
  and invalidating the worker.
- A500sRawVisionBridge tags its previous ARGB image with the source
  epoch. A mismatch cannot become one temporal pair, even when the
  timestamp gap is below 500 ms.
- If epoch changes during expensive ARGB conversion, the partly
  copied frame is discarded. Recheck after submit() prevents falsely
  counting a submitted pair across a simultaneous epoch transition.
- The OpenpilotInferencePipeline.PairSample now supports an optional
  isSourceCurrent() lease (always true for existing Camera2/offline
  callers). The RAW bridge attaches an atomic source lease and the
  worker checks it before inference, after ONNX execution and at
  publication; stale outcomes are suppressed.
- reset() increments the epoch before trying to acquire the bridge
  monitor, preventing conversion-vs-reset races.
- Five deterministic pure-JVM tests cover nonblocking network-side
  signal during conversion, epoch transitions across frames, a
  transition inside submit, a revoked source while ONNX is running,
  and producer rejection of already revoked source.

## Acceptance boundary

No camera-image recording or model upload; IJK preview, primary
Surface decoder and vehicle controls remain unchanged. Tests use
synthetic pixels and a blocking fake engine. This validates only the
new synchronization contract, not a real A500S frame/decoder, actual
ONNX model, lane/lead ground truth, camera calibration or scene-to-screen
latency. It does NOT upgrade #14, #16, #17 or #20 to PASS.

Gate A: **7/20 (35%)**. Gate B: **0/5**.
