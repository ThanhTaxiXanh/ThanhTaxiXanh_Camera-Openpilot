# V1.3.9 — RTP stream-epoch isolation and deterministic replay

Direct RTP/H.264 reception now treats the RTP SSRC and dynamic payload type
as a stream identity. On a change, it invalidates the previous H264 access
unit, clears sequence continuity, and waits for a **complete IDR** before
permitting decoder input. UDP's bounded reorder window independently clears
stale buffered packets when the stream identity changes, preventing mixed
packet epochs. This is a packet integrity/synchronization improvement, not
a change to MediaCodec output pacing or camera transport.

FU-A now requires the NAL type and NRI/forbidden bits to remain consistent
across all fragments and rejects forbidden_zero_bit in single NAL, STAP-A
and FU-A payloads. No packet loss is concealed by synthesizing reference
frames; existing loss-handling and offscreen YUV discontinuity policies remain.

The JVM trace-replay suite sends a long deterministic **synthetic** stream
through the production UDP reorder window and production H264 assembler,
injects RTP packet loss, late/reordered FU-A fragments, an SSRC epoch switch
and repeated IDR recovery. Each emitted Annex-B picture is compared bytewise
with its expected synthetic NAL; any post-damage output must begin at an IDR.
It does not use an Android MediaCodec, A500S camera, original xADAS model or
real camera timestamps.

Limitations: network/transport simulation cannot establish physical RTP
behavior, 70mai 4.5.0 comparability, frame-to-glass latency, ADAS
calibration or lane/lead ground truth. There are no user-facing warnings
or metric lane/lead values newly enabled. Gate remains **7/20 = 35%**,
with mandatory items #14–17 and #20 NOT PASS.
