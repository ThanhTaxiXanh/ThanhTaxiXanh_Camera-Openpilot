# V1.3.26 — Exact H.264 SPS dimensions for A500S Direct/YUV codecs

## Observed configuration defect

DirectRtspPlayer and the optional H264YuvSidecar previously both
created MediaCodec with a hardcoded **1280×720** initial frame size.
The reported A500S live profile has been **640×480**. Although some
Android decoders adjust the output format when they later process SPS,
others allocate oversized buffers or perform a midstream format change.
This is a correctness mismatch that should be eliminated at startup,
not claimed to have solved end-to-end camera-to-glass delay.

## Implementation

The new pure Kotlin H264SpsDimensions parser reads only a bounded,
SPS NAL type 7 from the owner-approved RTSP SDP sprop-parameter-sets
before MediaCodec.configure. It handles AVC baseline/main/high profile
and High-profile chroma formats, Exp-Golomb fields, NAL emulation
prevention, frame-mbs-only versus field-coded dimensions and
chroma-dependent cropping.

The Direct Surface preview decoder and the optional offscreen YUV
decoder now start with the **same** SPS-derived width and height.
The app logs the selected dimensions and whether they came from SPS
or the explicit fallback. An unavailable/corrupt SPS does not produce
a guessed arbitrary resolution; it uses the known A500S 640×480
fallback, which is not asserted to be correct for other camera models.

Eleven JVM regression tests create deterministic SPS RBSP fixtures,
including baseline 640×480, High 1280×720, cropped 1920×1080, 4:2:2
crop, interlaced height, malformed/truncated NAL and invalid crops.

## Safety and acceptance limits

This changes decoder startup negotiation, not RTP packet timing or
video scheduling. A green GitHub build shows parser and packaging
coverage, not that a real A500S decoder is smoother or that measured
optical latency dropped from approximately three seconds to one.
A fair A/B test must measure the same A500S profile, phone and optical
scene-to-glass reference, recording decoder format and reconnects.

A physical A500S stream, a native MediaCodec/YUV recording and
independent latency analysis remain necessary before #3, #14 or #20
are counted as accepted. This does not enable uncalibrated ADAS lead
warnings. Gate A stays **7/20 (35%)**, Gate B stays **0/5**.
