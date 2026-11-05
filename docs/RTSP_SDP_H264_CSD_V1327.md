# V1.3.27 — RTSP SDP H.264 payload/CSD isolation

## Defect corrected

The previous Direct RTSP player scanned the **entire SDP** and selected
the first occurrence of sprop-parameter-sets. In a valid SDP with several
media sections or payload types, that CSD could belong to a different
stream than the H.264 video track selected for SETUP. Supplying unrelated
or malformed SPS/PPS to two MediaCodec instances risks decoder startup
failure, invalid dimensions or repeated recovery.

## Implementation

A bounded, pure-JVM H264SdpParameterSets parser now:

- Restricts CSD extraction to the **first video media section** used by the
  current SETUP track selection; never reads audio or later video sections.
- Requires a matching numeric fmtp payload type. Prefers declared
  a=rtpmap:<PT> H264/90000 and rejects explicitly non-H.264 video.
- Supports the A500S compatibility case where an rtpmap line is omitted,
  but only when SPS/PPS are otherwise well-formed and the SPS dimensional
  parser accepts them.
- Restricts SDP length, CSD base64 and NAL lengths; validates SPS NAL type 7,
  PPS NAL type 8, and parsed SPS dimensions before offering CSD to either
  the preview Surface or offscreen YUV decoder.
- Preserves the existing explicit 640x480 A500S fallback if no validated
  SPS is present. Unvalidated CSD is **not** given to MediaCodec. No IJK
  playback option, RTSP pairing, transport pacing or application permission
  is changed.

Six additional deterministic JVM tests cover multi-section SDP, wrong
payload association, missing rtpmap, explicit H.265, corrupted base64 or
NAL and size caps. Existing SPS tests still cover geometry/cropping.

## Acceptance boundary

This is defensive codec configuration, not proof that the live A500S
camera/phone can deliver continuous YUV, smooth RTSP playback, legal
ADAS model inference or ~1 second optical latency. Without independent
physical evidence, criterion #3/#14/#20 remain CODED, not PASS.

Gate A: **7/20 (35%)**. Gate B: **0/5**.
