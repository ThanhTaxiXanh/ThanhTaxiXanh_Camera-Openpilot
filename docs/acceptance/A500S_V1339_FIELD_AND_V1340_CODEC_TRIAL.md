# V1.3.40 — Owner-controlled A500S YUV codec comparison

## Evidence from actual owner V1.3.39 Android phone recording

On 2026-11-06 the owner uploaded a new 61.033s Android screen video
(512x1136, nominal 30fps, 1,831 H264 frames, 40,681,126 bytes).
SHA-256 of the exact private uploaded MP4:
`46bab4bc86d906ae01230a167df6cd186e8e5f70fad00c59f49511e1f9aee403`.

**Do NOT commit the video, extracted images, scene coordinates, GPS
overlay or license plates to public GitHub.** These observations are
a technical derivative with bounded evidence. This is screen video,
not a synchronized physical camera scene-to-screen latency test.

The Direct Surface preview remained visibly populated while YUV was
enabled, and decoder counters continued to advance. Extracted screen
diagnostic samples from V1.3.39:

| Screen time | Actual YUV callback total | Secondary decoded | Secondary resets | Secondary errors | Stale YUV output drops | Exhausted input |
|---|---:|---:|---:|---:|---:|---:|
| 15s | 5 | 165 | 0 | 0 | 143 | 0 |
| 30s | 9 | 575 | 0 | 0 | 535 | 0 |
| 45s | 16 | 912 | 0 | 0 | 829 | 0 |
| 48s | 18 | 989 | 0 | 0 | 892 | 0 |
| 60s | 26 cumulative | 149 (new RTSP session) | 0 (new sidecar) | 0 | 138 | 0 |

At ~48s the status reports `SocketException: Socket closed`
and the Direct reconnect budget later reaches 2/3. The new sidecar
instance starts with counters reset; the displayed activity-wide
YUV callback count can continue across RTSP restarts. Therefore
these numbers MUST NOT be interpreted as 26 frames from one
uninterrupted decoder or a clean 60-second RTSP test.

**What improved:** V1.3.38 had 27 native YUV decoder resets
associated with 27 late decoded outputs at ~48s. V1.3.39 no longer
resets the native decoder for an aged output alone, and the
observed secondary resets/errors remain 0 in the first 45 seconds.
This is a real, scoped on-device improvement.

**What still blocks #14:** Even at t45, 829/912 (90.9%) decoded
outputs had expired the strict local-receipt age, so only 16
samples reached the YUV callback. This output cadence is inadequate
for reliable continuous 12-channel temporal/ONNX inference. Do not
weaken the 550ms age gate or inject stale YUV into the AI path.
The output rate may be constrained by running two decoders, one
of which is `c2.android.avc.decoder [SOFTWARE]` on this phone.
This is a throughput hypothesis, not a benchmark of all Android
codec implementations.

## Separate, explicit codec selection experiment

V1.3.40 adds a button BEFORE starting Direct playback:
- `CODEC YUV PHỤ: PHẦN MỀM (MẶC ĐỊNH)` — existing V1.3.39
  software-preferred selection, preserving prior preview behavior.
- `CODEC YUV PHỤ: HỆ THỐNG (THỬ NGHIỆM)` — owner explicitly asks
  Android's `MediaCodec.createDecoderByType("video/avc")` to choose
  the decoder for the SECONDARY YUV path. Android may choose hardware
  OR software; this is never claimed to guarantee hardware decoding.

Choice cannot change while IJK or Direct playback is active;
it takes effect only for a NEW Direct session, before YUV is armed.
The Direct Surface renderer, network/RTP, IJK fallback, RTP age
logic, 550ms YUV freshness checks, non-temporal sample export
and ONNX interface remain unchanged.

Diagnostics display the ACTUAL secondary codec name, and the
`isSoftwareOnly` classification from platform CodecInfo when API
29+ permits it. This label no longer falsely treats any default
codec as hardware based only on its initialization path.

## One narrow on-device trial

With vehicle parked, download Lite from the successful POST-MERGE CI:
1. START with YUV codec preference `PHẦN MỀM`. Launch Direct,
   YUV on, ONNX off, observe 20s YUV counters/Surface and
   sidecar resets/stale drops.
2. Stop playback. Switch preference to `HỆ THỐNG (THỬ NGHIỆM)`
   while stopped; relaunch Direct, YUV on, ONNX off, observe 20s.
3. Record selected codec name and whether `[SOFTWARE]` is shown.
   Compare YUV count delta / time, stale output deltas, decoder
   resets and preview Surface speed. A second hardware decoder
   may be unavailable and trial may cause resource contention:
   immediately turn YUV OFF if preview degrades.
4. Do not run inference or trust any predicted distances.
   No need to export another non-temporal TTXYUV02. Strict
   TTXYUV01 is relevant only if consecutive frames are actually
   delivered within 500ms.

## Acceptance: unchanged

Gate A **8/20 = 40%**; Gate B **0/5**.
No new proof of #14, #16, #17, #20; source-to-screen optical
latency still not measured. A green build or codec listing does
not count as physical performance evidence.
