# V1.3.19 — Real ONNX Runtime CPU contract fixture (synthetic original model)

## Why this is needed

Before V1.3.19, the AI APK could load owner-imported ONNX files and compare
user-provided TTXREF01 reference tensors, but GitHub CI did NOT execute
a real ONNX graph. Shape checks and fake inference engines do not prove that
the two FLOAT32 inputs actually influence a 1,576-element output.

The new **tools/onnx_cpu_contract_fixture.py** executes a graph with
**ONNX Runtime CPU**, independently computes a Python reference and
produces a model/vector pair which the owner can optionally import on
their Android phone using the existing AI APK. This test graph is authored
from simple arithmetic, not copied or distilled from an xADAS/Openpilot
model, and has no proprietary training weights. The graph's only parameters
are fixed public scalars and a deterministic index-wise arithmetic bias.

## Exact contract

- Inputs: img and big_img, each FLOAT32 [1,12,128,256], 393,216 floats.
- Values: deterministic 16..234 studio-range integers, with distinct
  input patterns and no NaN/Inf.
- Output: vout_pass, FLOAT32 [1,1576].
- Synthetic graph:
  for i=0..1575, output[i] = 0.25*img[i] + 0.75*big_img[i]
  + 0.125*(i modulo 29).
- The shape contract is real but the output is **not** lane geometry,
  lead distance, pose or any valid ADAS observation.

An independent Python/numpy calculation produces the reference output
without using ONNX Runtime results. CI checks that a genuine CPU
onnxruntime.InferenceSession returns bit-identical 1,576 elements,
that perturbing EITHER tensor changes the output by the known amount,
and that invalid/missing inputs fail.

## CI artifacts

The workflow runs a pinned isolated Python virtual environment using
numpy==2.3.3, onnx==1.19.1 and onnxruntime==1.23.2, then uploads:

- TTX-original-contract.onnx — an original, synthetic, non-ADAS model
  compatible with OnnxVisionEngine's two-input contract.
- TTX-original-contract.ttxref — TTXREF01 with graph SHA256 and the
  two big-endian FLOAT32 tensor streams plus independent expected
  FLOAT32 output.
- manifest.json — hashes, exact input/output contract, CPU runtime,
  zero-difference checks and clear scope limitation.

Download the artifact ZIP on Android, extract the model and reference.
Use TTX Camera AI **AI** variant, enter the Vision Model screen,
explicitly declare the right to use this original arithmetic fixture,
import the .onnx, then select .ttxref for comparison. A local result
"MATCHED_UNVERIFIED" confirms a useful Android ONNX smoke test.
It does NOT validate an independent driving model, model training,
ground truth, camera warp, or live ADAS accuracy. Do not enable metric
HUD/warnings on this synthetic model.

Lite has no ONNX runtime and must remain without inference; this
contract model is NOT added to either APK.

## Acceptance decision

This is meaningful executable CPU integration evidence and a bridge to
a reproducible Android smoke test, but **criterion #11 is still CODED,
not Gate A PASS** because there is no independently sourced authorized
real driving model and production Android-reference parity. Criterion
#16 remains NOT READY and Gate B mandatory #14–17,#20 remains 0/5.
Gate A remains **7/20 (35%)**. A passing CI does not certify a real
camera or physical exposure-to-glass latency.
