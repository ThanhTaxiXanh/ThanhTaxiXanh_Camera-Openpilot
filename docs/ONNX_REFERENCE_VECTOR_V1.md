# Authorized ONNX reference vector — TTXREF01 (V1)

The Android AI-flavor model screen can perform a **single-input/output
numerical comparison**, entirely offline, against a user-provided and legally
authorized reference vector. It never validates camera geometry or activates
ADAS. Do not bundle or distribute xADAS model weights, images or protected data.

## Fixed, bounded binary format

The file extension is `.ttxref` (no JSON, ZIP or executable data).
Total size must be **3,152,072 bytes** and contain exactly:

| Order | Bytes | Type and meaning |
|---|---:|---|
| 1 | 8 | ASCII `TTXREF01` (no terminator) |
| 2 | 32 | raw SHA-256 bytes of the precise imported ONNX file |
| 3 | 1,572,864 | `img`: 393,216 IEEE 754 float32, **big-endian** |
| 4 | 1,572,864 | `big_img`: 393,216 IEEE 754 float32, **big-endian** |
| 5 | 6,304 | `vout_pass`: 1,576 IEEE 754 float32, **big-endian** |

The float arrays are contiguous, NCHW (1×12×128×256), and are expected to be
raw studio-range float values from the authorized reference pipeline. In the
**current experimental packer hypothesis**, channels in each 6-channel temporal
half are Y00, Y01, Y10, Y11 (each 16–235), U and V (each 16–240). This is NOT
evidence that xADAS v0.2.7 uses an identical conversion/channel order.

The output is the known single `vout_pass` tensor, length 1,576. All values
must be finite; vector data, user-facing file names, and ONNX weights are never
uploaded.

## Comparison rules

1. User imports their own ONNX file through the model screen (AI flavor only).
2. User selects an authorized `.ttxref` input/output pair from Android's
   local document picker.
3. App hashes the imported model and rejects a reference whose 32-byte hash
   doesn't match. It also rejects malformed/truncated/oversized vectors,
   invalid channel values and NaN/Inf outputs.
4. The app runs ONNX from the supplied input floats and compares all 1,576
   float outputs. An element matches if
   `abs(actual - reference) <= 0.001 + 0.001 * abs(reference)`.
5. A PASS is reported only if **zero** elements exceed that tolerance.
   It means only numeric agreement with the provided file; a synthetic,
   fabricated or same-backend file does not establish independent truth.

## Evidence still required for the 90% milestone

- A legally usable, independently generated reference vector with documented
  provenance, preprocessing convention and source camera intrinsics.
- Model graph's exact tensor normalizer and temporal ordering.
- Verified projection and metric lead geometry against accurately measured data.
- Reproducible RTSP/MediaCodec timing and hardware validation on real A500S.

The 20-item checklist in `PREDEVICE_90_GATE.md` remains below 90% until all
mandatory items pass. An imported vector must **never** unlock alerts, speed
limits, steering or braking. Keep raw frame evidence private and avoid sharing
license-restricted model inputs/outputs.
