# V1.3.16 — Offscreen YUV H264 GOP freshness guard

## Problem

The offscreen secondary decoder already discarded YUV output received
more than 550 ms after its compressed access-unit receipt. Nonetheless,
it still accepted expired compressed H264 AUs into its queue and
submitted delayed pictures to native MediaCodec. This wastes decoder
resources on pictures that cannot produce usable RAW model inputs.

Skipping an arbitrary compressed P-frame is unsafe because later
pictures depend on it. Therefore the whole offscreen GOP must be
revoked and reacquired from a complete IDR on backlog expiration.

## Correction

- A pure Kotlin OffscreenGopAgeGate combines 550ms local receipt-age
  validation with H264SecondarySyncGate IDR recovery.
- The network producer checks age of the oldest queued AU; if stale,
  the entire offscreen queue/decoder epoch resets before new admission.
- A stale/future incoming AU also resets the full offscreen prediction
  chain and refuses to enqueue it.
- The offscreen worker rechecks age after queue dequeue AND before
  native MediaCodec.queueInputBuffer, including input-buffer wait.
- Reset invokes existing onDiscontinuity to invalidate pending RAW
  temporal ONNX pair and incomplete owner-armed YUV capture.
- Separate diagnostics count expired compressed AUs and old queue
  heads triggering a complete reset.
- The primary Surface preview, RTP transport and MainActivity reconnect
  budget are unchanged.

Nine pure JVM tests validate boundary age, clock faults, post-IDR
stale-frame rejection, old queue heads, 12,000 healthy synthetic AUs,
and IDR reacquisition.

These local elapsedRealtime timestamps are not camera exposure times.
The host tests do NOT demonstrate real dual MediaCodec support,
physical A500S timing, ONNX model accuracy, or certified ADAS warnings.
Mandatory #14–17 and #20 remain NOT PASS. Formal gate 7/20=35%.
