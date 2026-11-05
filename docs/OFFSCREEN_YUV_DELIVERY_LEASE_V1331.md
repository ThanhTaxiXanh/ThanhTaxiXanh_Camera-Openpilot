# V1.3.31 — Offscreen MediaCodec YUV output lease and freshness

## Defect

The secondary A500S H.264 MediaCodec checked source epoch, active state and
receipt age immediately before calling AndroidYuvImageBridge.snapshot(Image).
Native YUV_420_888 plane copying is expensive and can overlap a network/RTP
GOP discontinuity or decoder reset. The prior callback could nevertheless
receive a frame from an obsolete epoch. The 300 ms cadence timestamp was
advanced BEFORE a native image was successfully copied and delivered, so
one failed/unsupported frame could suppress the next valid YUV sample.

## Code and tests

- Introduce a pure Kotlin OffscreenYuvOutputGate validating activity,
  pending decoder reset, captured receipt clock, current source epoch,
  age <=550 ms and local output spacing >=300 ms.
- Apply that gate BEFORE and AFTER Android native Image plane snapshots.
  A decoder reset during copy rejects the old YUV data without touching
  the main Direct Surface/preview MediaCodec.
- Update the sample cadence only after a successful callback in the
  current decoder epoch. Do not throttle the next valid sample after
  an invalid or revoked copy.
- Unsupported ImageFormat output is reported as a sidecar fault and its
  GOP is dropped instead of silently presenting no YUV.
- Expose sidecar counters: stale/invalid-clock output, revoked-epoch output
  and unsupported-format output in Direct diagnostics.
- Eight host-JVM tests simulate a GOP change during native-copy window,
  copy-time expiration, clock anomalies, reset, spacing, and a virtual
  10,000-frame stream. The tests are synthetic, not native MediaCodec
  hardware instrumentation.

## Acceptance

This patch does not prove that Android hardware can operate two AVC
decoders concurrently, that 70mai A500S output Image planes are
YUV_420_888, that raw YUV feeds a legally authorized trained ONNX model
in real time, or that optical camera-to-screen latency is near one second.

No proprietary weights/data are added. No camera auth bypass or vehicle
control. Both app variants stay ABI arm64-v8a.

Gate A remains **7/20=35%**. Gate B remains **0/5**. A passing CI build is
not an independent physical camera measurement.
