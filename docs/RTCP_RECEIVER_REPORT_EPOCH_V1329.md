# V1.3.29 — RFC 3550 RTCP RR source epoch and interval accuracy

## Confirmed code defects

RtpReceiverDiagnostics previously set highestSeq to the first sequence of
a NEW SSRC, then processed that same packet as a duplicate. The resulting
RTP expected/received mismatch could report a phantom lost packet.
SSRC zero was used as an uninitialized sentinel even though it is a
representable 32-bit RTP source identifier.

The compound RTCP Receiver Report put only a nonnegative cumulative loss
integer in the 32-bit fraction-loss/loss field and did not maintain RR
interval baselines. As a result its top 8-bit fraction was always zero,
even with real loss, and duplicate/late receipts were not correctly
reflected in signed cumulative loss. Compare RFC 3550 §6.4.1 and A.3.

## Scope

- Maintain a distinct hasSource flag and monotonically increasing epoch
  for SSRC changes. Clear source sequence base/highest, receipt and
  transit/jitter state *before* recording the first packet in that epoch.
- Count all valid received RTP packets (including delayed and duplicate
  receipts) when computing RFC-style expected minus received; preserve
  a nonnegative diagnostics-only lostPackets field for the user interface.
- Encode fraction lost in the most-significant eight bits, and signed
  cumulative loss (clamped to ±24-bit range) in the remaining 24 bits.
- Maintain explicit per-SSRC previous RR expected/received counts, and
  advance them **only after the RTCP write/send succeeds**, not when
  constructing a report or when UDP server RTCP port is unavailable.
- Preserve the previous Direct RTP playback configuration and native
  MediaCodec, H.264 and ONNX pipelines; no third-party assets added.
- Add nine synthetic JVM RFC-style tests, covering source changes, SSRC=0,
  sequence wrap, delayed loss recovery, duplicates, wire signed-loss
  encoding, interval loss, failed-send retry and clamping.

## Evidence boundaries

This change fixes *local network reporting*, not A500S video latency.
A green CI run proves compilation and these synthetic cases only. It
cannot establish that the A500S interprets RR, adjusts bitrate, improves
frame cadence or lowers optical glass-to-glass latency. Real phone camera
uptime, geometry, model rights and validated lead/lane outputs remain
unavailable.

Gate A **7/20=35%**, Gate B **0/5**, mandatory #14–17/#20 NOT PASS.
