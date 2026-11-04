# V1.3.7 — Owner-initiated A500S live YUV export

On the existing Direct MediaCodec preview enable YUV A500S, then press
LƯU 4 KHUNG YUV A500S. Four sequential 640x480 offscreen YUV_420_888
frames are copied with rowStride, pixelStride and crop offsets respected.
When complete, Android's Create Document picker asks the owner for a
destination. No model is needed, no persistent recording is enabled, and
neither RTP nor the main Surface preview receives disk I/O.

TTXYUV01 is big-endian: ASCII magic (8 bytes), width/height/count/flags
(u16 each), then per frame local RTP AU receipt timestamp (i64),
Y (307200 bytes), U (76800), V (76800), SHA-256 of the three planes
(32 bytes). Four frames use 1843376 bytes. Bad geometry, timestamp gaps
over 500ms, reconnect, broken GOP or user cancellation discard a partial
capture. The existing offline reader verifies written output.

This is NOT a source-authentication tool. Local receipt time is not
camera exposure time. The exported fixture does not independently prove
which camera produced the frames, model permission, optics, lane/lead
geometry, metric distance, glass-to-glass latency or safe ADAS behavior.
Native dual-decoder availability remains unverified on owner hardware.
No inference results are routed to driver alerts.

CI: pure JVM capture/serialization round-trip, cancellation, geometry,
and timestamp cases; Lite and AI ARM64 build checks. Hardware gate
unchanged: 7/20 = 35%, mandatory #14-17/#20 not yet PASS.
