# V1.3.11 — Surface preview GOP epoch fence

## Problem

The RTP/H264 depacketizer rejects malformed or lost AUs and the separate
offscreen YUV decoder already drops its H264 prediction chain on damage.
But the preview MediaCodec uses a Surface and may own previously queued
H264 reference pictures in native buffers even after the RTP source emits
another valid IDR.

Silently feeding the newly recovered stream into the SAME Surface decoder
does not establish a clean decoder epoch. Selectively dropping compressed
P frames or calling MediaCodec.flush() on an RTP network/callback thread
would be unsafe.

## Implementation

A pure Kotlin per-session PreviewGopEpochFence tracks whether any H264
access unit was actually accepted into the preview queue.

- Before the first accepted AU, damaged RTP only triggers H264 IDR wait.
- Once an AU was accepted, a broken RTP AU revokes the preview decoder epoch.
  The receive callback blocks subsequent native output callbacks via
  alive=false, closes the socket, and forces full player teardown.
- MainActivity's existing reconnect worker waits on the old MediaCodec
  asynchronous release latch before a new RTSP player is started.
- RAW inference is invalidated immediately and owner-armed YUV evidence
  is canceled. The sidecar decoder still drops its own GOP independently.
- A repeated damage event requests at most one decoder restart. Existing
  3-per-60-second reconnect budget limits persistent connection instability.
- Direct diagnostics expose accepted AUs, damaged GOP events, reconnect
  requests, and whether this preview epoch was revoked.

Synthetic JVM tests check the state machine and loss/IDR transitions.
No Android codec is executed in those tests. A previously in-flight
Surface callback may still be completing during the gate transition;
this is NOT proof that every image displayed on actual hardware is
fresh, nor proof of a one-second camera-to-glass delay.

Model rights, calibration, lane/lead ground truth and mandatory physical
acceptance criteria #14–17 and #20 remain unverified.
**Predevice gate: 7/20 = 35% PASS CI.**
