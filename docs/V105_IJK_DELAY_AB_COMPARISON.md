# V1.0.5 — Delay 3–4 seconds shared by owner paired vs plain IJK

## Observation
Owner tested V1.0.4 and reports both the paired mode and plain 70mai/IJK play with approximately 3–4 seconds delay.
Pairing performs owner-approved client.cgi registration every 5 seconds; playback still uses the very same IJKPlayer/RTSP/MediaCodec pipeline. No reduction in presentation latency is expected as a direct result of pairing.

## Implemented A/B test
- Default SMOOTH mode retains original v1.0.4 IJK settings: rtsp_transport=tcp, mediacodec=1, packet-buffering=1, framedrop=1, analyzeduration=1000000, probesize=32768.
- New experimental LOW_LATENCY profile changes player packet-buffering to 0, min-frames=5, max_cached_duration=600ms, max_delay=300000 microseconds. These options might be ignored by this IJK fork or may introduce stutter; no claim of achieving <1 second.
- Both profiles keep the same 70mai `/livestream/12`, RTSP transport TCP and physical camera pairing mechanism.
- UI has a profile selector; changed profile applies on pressing IJK start again. Native decode and queue diagnostics stay visible.
- There are no Openpilot/AI/native library additions. Keep a small APK artifact.

## Root-cause investigation still necessary
- Native videoCachedDuration is player internal cache duration, not total glass-to-glass delay.
- If both profiles are delayed ~3–4s, inspect A500S encoder preview pipeline/GOP, RTSP RTP timestamps and clock synchronization; client-side cache alone may not explain it.
- If low latency reduces delay but stutters, prefer smoothness for any ADAS development; a safety overlay should never be presented as low-latency without measured end-to-end timing.
- Owner pairing/heartbeat should instead be tested for uninterrupted >30s playback: CGI OK counts and RTSP completion timing.

## Tests
1. Park the vehicle; maintain camera Wi-Fi; test IJK SMOOTH 90 seconds and log internal cache ms and visual delay.
2. Select THỬ TRỄ THẤP, restart IJK, test 90 seconds with the same physical motion reference and compare real lag, stutter and completion.
3. If still ~3–4s in both, revert to smooth; do not degrade stability to chase speculative subsecond performance.
4. Avoid operating camera and phone while driving; passenger or parked-only testing.