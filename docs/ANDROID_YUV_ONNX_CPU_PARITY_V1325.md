# V1.3.25 — Android YUV→ONNX TTXREF01 and independent CPU replay

## What this release makes possible

Previously the phone could capture an owner-controlled A500S TTXYUV01
and import an authorized ONNX graph, but not produce the reference
combining the EXACT resulting tensors and the Android native ONNX output.
The user can now do so in Offline YUV Audit on Android without a PC.

1. Capture and export 4 real A500S YUV frames as TTXYUV01 in Direct mode.
   These timestamps mark LOCAL H264 receipt, NOT camera exposure.
2. Install an authorized model using the AI APK's existing model import
   feature. The harmless original contract graph from CI is sufficient
   for a technical smoke test; it is NOT a real ADAS model.
3. Open TTX offline YUV audit and validate the captured TTXYUV01.
4. Select "XUẤT TTXREF01 TỪ YUV + ONNX TRÊN ĐIỆN THOẠI".
   The AI APK reconstructs the first temporal pair with both current
   experimental warps, executes the installed model through native ONNX
   Runtime, then writes a canonical 3,152,072-byte TTXREF01 via SAF:
     header "TTXREF01", model SHA256, two FLOAT32 BE [1,12,128,256]
     tensors and Android vout_pass FLOAT32 BE [1,1576].
   The reference does not directly contain original video or model
   weights, but its tensors can reveal scene information: keep it private.
5. Compare the export independently using a CPU ONNX Runtime:
     python tools/verify_device_onnx_reference.py \
       --model TTX-original-contract.onnx \
       --reference TTX-Android-YUV-ONNX-output.ttxref \
       --report local_cpu_parity.json
   SHA256, sizes, studio-range tensor values, actual ONNX input/output
   contracts, finite values and 1,576 output comparisons are verified.
   This independently reproduces the model's arithmetic, NOT the
   optical/physical camera measurements or training ground truth.

## CI protection

GitHub Actions independently replays the original arithmetic-only
ONNX graph and reference in real CPU ONNX Runtime. Negative controls
corrupt the embedded model hash and expected output and REQUIRE
rejection. Six host tests validate Android fixture generation,
canonical TTXREF01 round trip, missing model, Lite-without-ONNX,
bad output and malformed reference.

## Strict evidence limits

An Android-produced TTXREF01 is SELF-GENERATED OUTPUT, not independent
ground truth. CPU parity is useful cross-runtime evidence but does not
establish real xADAS/Openpilot equivalence, model use rights, calibrated
camera geometry, lane/lead correctness or camera-to-glass delay.
No model result is connected to driving HUD/warnings. Camera images,
models, network credentials and tensors are never uploaded by the app.

Gate A 7/20 (35%); mandatory field Gate B 0/5. Items #11, #14 and #16
are NOT newly PASS just because a self-authored synthetic fixture matches.
