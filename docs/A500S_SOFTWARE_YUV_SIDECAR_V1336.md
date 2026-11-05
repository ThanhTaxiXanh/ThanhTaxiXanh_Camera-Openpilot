# V1.3.36 — A500S offscreen AVC codec contention mitigation

## Physical bug motivating this change

The owner's earlier V1.3.34 video showed a real secondary decoder
failure after tapping YUV A500S:

`YUV offscreen: IllegalStateException Offscreen input queue full; await next IDR`

At that time the secondary codec was chosen by
`MediaCodec.createDecoderByType("video/avc")` while the primary Direct
Surface codec was simultaneously active. Hardware decoder concurrency
and raw-buffer output behavior vary across Android handsets, so the
observed error does **not by itself prove** hardware scarcity.
Its occurrence is independent from the collapsed preview layout fixed
and physically confirmed in V1.3.35.

## Defensive change in this version

- The optional YUV sidecar checks Android codec descriptions (API 29+)
  for software-only AVC decoders, tries them in stable platform order,
  then falls back to the platform's default AVC codec if a software
  candidate is missing or fails `configure/start`.
- The primary MediaCodec Surface decoder, RTP reader, IJK fallback and
  UI preview remain unchanged. Codec enumeration/configuration happen
  ONLY on the sidecar worker, never on the RTP/network or UI thread.
- A temporary `dequeueInputBuffer(-1)` (meaning no buffer available)
  is not immediately treated as fatal. The sidecar drains available
  output buffers, retries input a bounded number of times and resyncs
  the whole GOP at the next IDR if it still cannot progress. A
  dependent P-frame is never selectively discarded.
- Real diagnostics include selected codec name, software/default flag,
  candidate software attempts, transient input waits, exhausted waits,
  and outputs with no accessible YUV Image.
- Nine pure JVM unit tests cover candidate selection, no-software
  fallback, deduplication, preserved order and bounded retry policy.
  These tests do **not** validate Android native MediaCodec behavior.

## Safe owner re-test (stationary vehicle only)

Use a successful main-built V1.3.36 Lite APK. Connect to the
owner-operated 70mai A500S Wi-Fi. Open xADAS Direct MediaCodec first
with YUV OFF and ONNX OFF; verify the visible preview. Next enable
YUV A500S; leave ONNX OFF. Observe the `YUV OFFSCREEN` diagnostic
line (codec name/flag, decoded/YUV frame totals, no-input waits,
resets/errors) for 30–60 seconds. If YUV frames grow without repeated
faults, arm the four real YUV frames and export TTXYUV01.

Record a short screen video with the viewport and diagnostic line.
If input starvation still occurs, report the decoder name and counters.
Do not declare the YUV fix successful without physical validation.
Only then consider an owner-triggered 5-minute dual-stream trace.

The raw TTXYUV01 contains real scene pixels; select a privacy-safe
stationary scene. Keep model inference and driving alerts disabled.
This patch does NOT establish an authorized ONNX graph, 3D calibration,
metric lane/lead correctness or near-1-second optical latency.

Gate A remains 8/20 = 40%; Gate B remains 0/5.
