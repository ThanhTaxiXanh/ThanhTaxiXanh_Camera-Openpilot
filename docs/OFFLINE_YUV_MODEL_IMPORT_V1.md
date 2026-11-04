# V1.3.4 — Offline YUV420 replay and atomic authorized-model import

## Offline pipeline: what is actually implemented

The new **Nguồn camera → Chẩn đoán video YUV Offline** screen accepts a
user-selected local `TTXYUV01` fixture from Android's document picker.

The fixture is an **engineering container for raw YUV420p frames**, not
an MP4 player, not a captured A500S stream, not a new supported live camera
profile, and not an image/footage bundle distributed with the APK.

TTXYUV01 is exactly specified in **big-endian**:
- 8 ASCII bytes `TTXYUV01`.
- unsigned 16-bit width=640, height=480, frameCount=2..8, flags=0.
- For each frame: signed 64-bit local-relative receiptTimeMs,
  Y plane 307,200 bytes, U plane 76,800 bytes, V plane 76,800 bytes,
  then 32 raw SHA-256 bytes of Y||U||V.
- A strictly increasing 1..500ms gap between frames is required;
  no additional bytes may follow the last checksum.
- Maximum size: **3,686,736 bytes** (8 frames), with no arbitrary
  caller-controlled length allocation, no remote download and no
  camera permission required.

The importer validates the declared sizes, fixed format, timestamps
and all per-frame checksums. Checksum equality is an *integrity check*
against mistakes, not a proof of where the frames came from; the
document's author can change both the planes and the digest.

The replay engine uses the actual `Yuv420FrameConverter`,
`A500sRawVisionBridge`, both virtual camera homographies,
`VirtualCameraWarp.pairToModelInput` and
`VisionInputContract.requireRawStudioByteTensors`. It returns
the number of valid temporal pairs and deterministic SHA-256 of the
first `img` and `big_img` FLOAT32 tensors, using canonical
big-endian float bytes. It supplies a **virtual local clock**; therefore
elapsed time, camera-to-screen latency and source sensor capture time
are not measured here. No ONNX model is executed and no lane or
metric distance is fabricated.

These homographies remain experimental. Even an offline replay that
passes all checks does **not** validate the camera intrinsics, YUV
normalization against real licensed model training data, optical
distortion or xADAS coordinate conventions.

## Safer, explicit ONNX model import

A `VisionModelActivity` import now requires a checkbox where the
owner **affirms** that they possess rights to use the selected model.
This declaration is not independently authenticated and does not
grant rights to third-party proprietary artifacts.

`ModelInstallGuard` stages up to 250 MiB of incoming ONNX bytes
inside the application's PRIVATE model folder, computes SHA-256,
independently verifies the on-disk staged digest, and uses
`VisionModelBackend.inspect(candidate)` to verify the graph
before replacing a previously installed model.

The AI variant must be able to load the actual ONNX Runtime graph:
two FLOAT32 inputs `img` and `big_img` with [1,12,128,256],
and FLOAT32 output `vout_pass` with [1,1576].
The Lite variant intentionally has no ONNX runtime and cannot
approve graph import.

After a successful graph check, the model is moved into place
using **same-directory atomic replacement**. A failed import, invalid
graph, exceeded size limit, missing rights declaration or filesystem
without atomic rename support is rejected without deleting the
existing good model. No model is uploaded or downloaded.

This graph check is NOT a license audit, model-to-xADAS parity check,
camera calibration verification, statistical detection-quality test
or license to activate in-car ADAS alerts.

## Host CI coverage

- Self-contained synthetic planar YUV generated in memory,
  correct per-frame SHA-256, temporal order and bounded file size.
- Deliberately corrupted checksum, magic, width, frame count,
  truncated planes, trailing bytes and invalid timestamp gaps.
- Full real bridge/dual-warp/12-channel preprocessing, deterministic
  first-pair tensor hash, and fail-closed ADAS assessment.
- Model replacement after mock graph-validation success,
  SHA-256 correctness, failure to validate graph, Lite missing
  runtime, missing rights, oversized/truncated inputs and prior
  model preservation. Synthetic graph acceptance in host tests
  **is not a real ONNX runtime integration run**.

Both Lite and AI APK flavors must be built and published by GitHub
Actions before this version is accepted. No camera device trial is
requested under the user's >=90% predevice requirement.

## Measured project progress

The PREDEVICE_90_GATE ledger has **20 criteria**. Before V1.3.4,
**7 are PASS CI**, i.e. **35%** of all acceptance items have explicit
automated proof. This milestone extends offline QA and the
fail-closed model install workflow, but **does not make any
additional mandatory item pass**. The project remains **7/20
= 35% verified**, not 35% feature-identical to xADAS; the target
is at least 18/20 (90%) AND mandatory hardware/model requirements.
