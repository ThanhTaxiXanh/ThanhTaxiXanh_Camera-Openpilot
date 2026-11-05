# V1.3.28 — SDP-selected H.264 RTP payload fencing

## Fault mechanism

The V1.3.27 SDP parser selected an H.264 payload type for codec headers,
but the Direct player still accepted any RTP payload type on the chosen
RTSP TCP interleaved channel or UDP socket. A foreign but syntactically
valid RTP packet could enter the UDP reorder queue, switch its SSRC/PT
epoch, or cause the H.264 assembler to revoke an in-flight GOP. This
could trigger decoder resets or reconnects unrelated to actual video loss.

## Change

- After DESCRIBE, instantiate a pure Kotlin **RtpSelectedPayloadGate**
  from the SDP video H.264 payload type.
- Apply the gate to early interleaved media, live TCP interleaved RTP and
  UDP packets BEFORE the UDP jitter queue or H.264 assembler.
- Exclude foreign payload types from RTP health/progress statistics;
  retain malformed RTP packets for the existing assembler to diagnose
  and quarantine rather than silently conceal video damage.
- Retain the previous permissive behavior if SDP provides no usable
  selected payload type. No authentication or new camera protocol is
  inferred from such a missing declaration.
- Expose cumulative filtered foreign-payload and malformed RTP counts
  in Direct diagnostics. Keep IJK playback unchanged.
- Add seven pure JVM tests, including an RTP/UDP reorder-to-H264
  integration with a foreign SSRC/PT interleaved between valid IDR/P frames,
  validation of real selected-stream packet loss, corrupt RTP, no-PT
  compatibility and SDP H.264 PT 98 selection.

## Safety boundary

No independent A500S phone stream, verified on-device decoder behavior,
camera optical timing, licensed ONNX model, measured lens geometry or
metric lane/lead ground truth was introduced. This is a protocol ingress
integrity change, not a claim of faster 70mai playback.

Gate A **7/20 (35%)**; Gate B **0/5**. Mandatory #14/#15/#16/#17/#20
remain NOT PASS; #3 remains CODED. A successful GitHub Actions build
does not promote the acceptance ledger.
