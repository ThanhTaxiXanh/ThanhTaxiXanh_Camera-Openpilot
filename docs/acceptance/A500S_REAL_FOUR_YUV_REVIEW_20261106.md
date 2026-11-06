# A500S real 4-frame TTXYUV02 and V1.3.38 video review — 2026-11-06

## Owner-provided physical evidence and privacy

The owner submitted a single Android screen recording plus a four-frame
non-temporal TTXYUV02 binary from an A500S test following V1.3.38.

Private uploaded media, GPS overlays and street imagery MUST NOT be
committed to public GitHub. This text documents technical checks only.
The hashes below identify the exact files reviewed (not device/app
cryptographic attestation or camera-exposure timestamps).

- Screen MP4: 56,659,000 bytes; SHA-256:
  `090181336ce9f805b0c67f596665eea565b6c27ae9f23210f3a63e0c51d4d95d`
  H264 512x1136, nominal 30fps, 2521 video frames, 84.0333 seconds.
- Raw TTXYUV02: 1,843,376 bytes; SHA-256:
  `5ff401b3b1c4966f8c95083018e6049949d58620bb0819d7476684e16a313ca6`
  Magic `TTXYUV02`; big-endian 640x480; four frames; flags
  `NON_TEMPORAL=1`; no trailing bytes.

### Independent four-frame verification

| Index | Local receipt elapsedRealtime ms | SHA-256 of Y+U+V | Valid |
|---|---:|---|---|
| 1 | 187387755 | 5125bc79a9f515632577a2cd604c7f3a0a2edca7495256d0498b208466f85efb | yes |
| 2 | 187417960 | b5ac9e1dcfd3765588351f8e1b68661e79c7d306f1fec18aa3d96cb5bef66bd0 | yes |
| 3 | 187425232 | 2fd6f61604417c4480035f4e34a7eb3490771696590446040582e8b63846e63a | yes |
| 4 | 187433955 | e905546d767a1a430ee63e6daba29e92115ad9d724051cf7f0067bd9d8979dd0 | yes |

Y plane length 307200 and U/V each 76800 in all frames;
all four digests match their internal SHA-256.
The four decoded color images contain real-looking coherent street
scene details and independent frame-to-frame variation
(RGB mean absolute difference: 6.09 / 3.95 / 4.77). No blank,
all-zero, identical or checksum-failed frames.

**Gaps:** 30,205ms, 7,272ms, 8,723ms.
The entire sampled span is 46,200ms for only four delivered frames.
These data are NOT 4 contiguous camera frames and MUST NOT be loaded
by TTXYUV01 or interpreted as a 12-channel model temporal pair.

### Video corroboration and remaining fault

The owner's 84-second video shows the Direct 70mai A500S Surface
preview visible and moving during UI scrolling. Native offscreen
YUV controls were enabled, with ONNX RAW disabled.

At around 36s, the on-screen sidecar diagnostics display a single
YUV image, 21 decoded output buffers, 17 offscreen resets and
17 stale YUV outputs. At around 48s, they show:
- optional `c2.android.avc.decoder [SOFTWARE]`,
- ONE valid YUV output so far, 37 decoded output buffers,
- **27 sidecar resets and 27 stale YUV output drops**,
- six transient input waits, **zero exhausted-input failures and
  zero reported decoder exceptions** at that time,
- independent Direct primary RTSP Surface output remains active.

The visual correlation between reset count and stale YUV output count
is consistent with the exact V1.3.38 source behavior:
`H264YuvSidecar.deliver()` calls `dropPredictionChain()` for every
`OffscreenYuvOutputGate.STALE_FRAME`. That resets the entire
secondary decoder, waits for IDR, and repeats even though
an old *decoded output image* alone does NOT imply the compressed
H264 reference chain was damaged.

### Focused V1.3.39 code correction

A stale decoded image now fails the same two-phase 550ms freshness
check and is NEVER sent to RAW/ONNX. It is discarded with the output
buffer released, and `onDiscontinuity()` invalidates any previously
cached temporal pair. However, stale decoded OUTPUT alone does not
restart the native sidecar codec or invalidate an otherwise good H264
GOP. INVALID_CLOCK, corrupt/missing input AUs, epoch revocation and
input-side staleness continue to fail closed and/or reset as before.

This only removes an unnecessary repeated decoder restart. It does
NOT claim that software AVC can maintain full native input cadence,
that a trained authorized ONNX graph ran, or that the virtual camera
has verified calibration.

## Acceptance impact

One user-tested TTXYUV02 successfully exported four separately
decoded actual A500S frames, proving single-frame acquisition and
serialization in owner device. NOT a continuous YUV stream, not
real-time ONNX, not optical delay measurement.

Gate A **8/20 = 40%**, Gate B **0/5**. #14 and #20 still NOT VERIFIED.
An owner physical retest of the new decoder policy is required before
any claim of improved continuity.
