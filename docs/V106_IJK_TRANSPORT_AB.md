# V1.0.6: Compare FFmpeg RTSP/TCP and RTSP/UDP using the same IJK decoder

User verified the official 70mai 4.5.0 application is approximately 1s behind the actual scene on the same A500S and Android phone. Both TTX IJK SMOOTH and experimental LOW_LATENCY were approximately 3s behind. Physical pairing/CGI registration did not alter latency.

MainActivity V1.0.5 forced RTSP transport=tcp for both profiles. This makes prior tests an invalid comparison of UDP vs TCP. v1.0.6 adds a single-factor A/B option:
- SMOOTH TCP: existing buffer and decoder options unchanged.
- LOW_LATENCY TCP: existing experimental options unchanged.
- UDP_SMOOTH: only request rtsp_transport=udp, keep buffer/codec identical to SMOOTH TCP.

Requested UDP is not proof the A500S accepted or continued that transport; consult IJK startup error and actual RTSP SETUP packet trace if UDP fails. A500S 30-second OnCompletion is a separate issue and owner heartbeat had already succeeded without permanently removing completion.

Test parked: compare real-world physical motion latency and stutter, while watching the selected IJK profile. UDP might be faster, unchanged, or worse. If lag stays ~3s in all, focus on IJK native video timestamp synchronization, camera preview profile/bitrate, and RTSP setup negotiation; do not tune generic caches blindly. Keep the app ARM64 playback-only.
