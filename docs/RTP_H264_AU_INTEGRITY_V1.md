# V1.3.8 — RTP/H264 AU integrity hardening (RFC 6184)

The Direct player now uses the pure-JVM RtpH264AccessUnitAssembler
instead of its minimal nested H264 AU assembler. This is a functional
packet-loss handling change, not only an instrumentation addition.

- Parse RTP version 2, CSRC, extension and padding bounds.
- Assemble single NAL, STAP-A aggregate and FU-A (with start/middle/end
  continuity), respect marker/timestamp AU boundaries and max AU size.
- Never forward an unterminated FU-A (e.g. missing final fragment).
- When an RTP sequence gap straddles two timestamps, discard BOTH AUs,
  because it is unknown which AU owned the lost packet.
- Following a damaged AU, suppress dependent non-IDR frames until a complete
  IDR is received. Sidecar GOP invalidation still fires as before.
- RTP reception, Surface renderer, MediaCodec options, network negotiation,
  existing physical pairing, and display-PTS scheduling are unchanged.
- Host JVM tests cover IDR/P frames, STAP-A, FU-A reassembly, RTP sequence
  wrap, corruption, cross-boundary loss, RTP extension/padding and AU bounds.

This can reduce corrupt output after loss but does NOT demonstrate
a camera exposure-to-glass latency reduction from 3s to 1s. The A500S
live-stream output characteristics and MediaCodec surface hardware behavior
still need future owner-provided measurements after the 90% predevice gate.
No proprietary xADAS binaries or weights are used; the existing inference
result/HUD fail-closed policy remains unchanged.

Gate: 7/20=35% PASS CI. #14–17/#20 remain NOT PASS.
