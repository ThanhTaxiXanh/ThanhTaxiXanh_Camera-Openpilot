# V1.3.5 — Three-way offline ONNX reference parity and RTSP source boundary

## Purpose

An ONNX graph that accepts two [1,12,128,256] FLOAT32 tensors is not
sufficient evidence that **camera pixels and temporal preprocessing**
match the model training contract. V1.3.4 tested the YUV-to-tensor path
without running an ONNX model. V1.3.5 adds an *optional* three-way test
that links those same generated tensors to a separately authorized
TTXREF01 model input/output reference.

**No licensed weights, A500S frames, or independent ground truth are
bundled.** The owner must supply the files and attest usage rights.
A positive comparison against a self-supplied vector proves
reproduction of that vector **only**, never the reference's correctness.

## User workflow

From Camera source settings, select **Chẩn đoán Video YUV offline**.
The first step selects `TTXYUV01`, verifying its checksums and
running the existing production YUV420 converter, temporal frame pair,
two experimental virtual camera warps and FLOAT32 12-channel packer.

The second step is optional: select `TTXREF01`. The AI flavor then:

1. Reopens the already-selected YUV file from Android's SAF provider.
2. Strictly parses the separately-selected TTXREF01 with its fixed
   32-byte model SHA-256, two 393216-FLOAT32 tensor inputs and
   one 1576-FLOAT32 output.
3. Hashes the previously imported, user-authorized app-private
   `models/vision.onnx` and checks it against the reference's model
   hash with `MessageDigest.isEqual`.
4. Regenerates both inputs from the first REAL production YUV temporal
   pair. Compares every FLOAT32 input bit-for-bit against the
   claimed reference tensors, reporting which channel/index first
   mismatches. **If either input differs, no model is executed.**
5. Opens the actual ONNX Runtime engine in the **AI flavor only**,
   calls it with the regenerated tensors and checks the 1576-element
   output using the established combined absolute/relative error
   tolerance. Lite deliberately reports ENGINE_UNAVAILABLE.

Failure statuses distinguish missing model, model hash mismatch,
wrong preprocessing tensor, unavailable engine and wrong output.
Only a match at all three levels produces `MATCHED_UNVERIFIED`.

The native ONNX result is never sent to `AdasHudView`, speed/range
alerts or live camera. No files are uploaded or recorded, and the
activity runs all file reads and computation off the Android UI.

## Camera source validation

`RtspSourcePolicy` now validates custom RTSP URLs and the observed
Wi-Fi camera gateway before storing/resolving the source:

- Only the `rtsp` URI scheme, a nonempty host, valid TCP port (or
  default), no embedded password/user-info, URL fragment or control
  characters.
- IPv4 octets must be within 0..255, without ambiguity from leading
  zeros, loopback, link-local, wildcard or multicast addresses.
- Owner-provided camera DNS hosts such as `dashcam.local` remain
  syntactically accepted. Successful source validation is NOT RTSP
  connectivity, RTP H264 compatibility or owner authentication.
- Unsupported vendor profiles remain disabled: saving the profile
  still fails closed; this work does not pretend to implement new
  xADAS vendor protocols or USB video.

## Test evidence

Pure JVM unit tests use **synthetic YUV and fake ONNX engine ports** to
validate hash-before-model sequencing, exact two-input tensor parity,
model mismatch, preprocessing mismatch, missing Lite runtime and
output mismatch; they explicitly cannot prove a real model works.
The actual model execution path exists in the AI app and can be
operated only when a real authorized model + independently supplied
reference are available.

Independent RTSP source tests cover legal A500S/dashcam URLs and
invalid gateway addresses, credentials, schemes, fragments and ports.

## Acceptance accounting

As of this release, the only independently established PASS CI items
remain **7 of 20 = 35%**. These additions strengthen diagnostic
reproducibility and source validation; neither an invented fake
engine test nor a potentially self-authored reference can satisfy
mandatory #14–17. Physical glass-to-glass A500S latency (~3s TTX vs
~1s official 70mai, as owner observed) is still unverified in the
new build and acceptance #20 remains NOT PASS.

The owner requested no phone testing before **>=18/20 = 90%** of
engineering acceptance criteria with all mandatory real-camera/model
gates satisfied. This is a criterion-based evidence percentage, not
a metric of source-code copying or xADAS feature equivalence.
