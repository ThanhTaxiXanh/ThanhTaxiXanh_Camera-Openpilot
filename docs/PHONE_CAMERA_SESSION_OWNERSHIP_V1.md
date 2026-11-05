# V1.3.18 — Camera2 and ONNX lifecycle ownership

## Defect

PhoneCameraActivity loaded a user-supplied ONNX model in a detached
thread. If the Activity paused or the Surface was destroyed while the
native graph was opening, the completion lambda could still assign the
new inference pipeline after onPause(), because it only checked
isFinishing/isDestroyed. Separately, delayed Camera2.onOpened and
CameraCaptureSession.onConfigured callbacks could create or replace
native objects after teardown. This could leak a decoder/session, mix
YUV temporal pairs across reopenings or display stale model results.

## Remediation

- PhoneCameraSessionLease: independent, synchronized revocable tokens
  for the Camera2 source and the ONNX load/result owner.
- Every Camera2 open/disconnect/error/configure callback is rechecked
  on Android's main thread; old devices and old sessions are closed
  but cannot change the new session state.
- The YUV ImageReader callback checks camera ownership and is
  fail-closed if the reader is already closed. Raw frame pairs are
  synchronized with teardown; the size must be exactly 640x480 and
  output format must be advertised in Camera2 configuration.
- ONNX cancel is allowed while loading; a late model is immediately
  closed. All published inference status callbacks are gated by both
  model and camera session tokens.
- onPause revokes all tokens, resets frame pairs and closes native
  resources without joining a Camera2 HandlerThread on the UI thread.
- Nine JVM tests cover pausing during model load, stale onConfigured,
  stale onOpened, surface changes, concurrency visibility and
  20,000 synthetic ownership transitions.

## Acceptance boundaries

The JVM tests validate ownership logic but do not drive native
Camera2 or Android image plane callbacks. They cannot demonstrate a
real handset's camera capabilities, YUV frame rate, ONNX graph accuracy,
A500S model axes or optical latency.

Criterion #8 stays Gate A CODED, with new source/tests recorded for
later independent device verification. Gate A 7/20; Gate B 0/5.
