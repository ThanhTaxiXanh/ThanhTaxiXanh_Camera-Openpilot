# V1.0.3 — 70mai A500S 30-second IJK disconnect and xADAS periodic stutter

Owner observation: IJK/FFmpeg is smooth but stops after around 30s. Direct MediaCodec lasts longer but stutters roughly every second.

## IJK recovery
- Keep original decoder and RTSP settings, do not sacrifice observed smoothness.
- OnCompletion/OnError now report the specific reason and elapsed session time, release native player and reconnect with bounded backoff.
- Watchdog samples IJK decode/output native FPS; if output previously worked but goes silent for more than ~5.5 seconds, restart. Report cache duration and isPlaying.
- Do not open concurrent RTSP streams or fabricate camera pairing CGI/signkey. A 30-second camera registration expiry is a hypothesis, not yet proven.
- A successful 15-second session resets the quick-failure counter; persistent immediate failures stop after five attempts.

## xADAS-style MediaCodec
- UDP RTP packets can arrive out of order; previously the FU-A assembler consumed every packet immediately and marked the frame broken on a sequence gap.
- The new RtpJitterQueue buffers at most 32 UDP packets for up to 55ms, then releases/skips ahead rather than blocking indefinitely. RTSP/TCP and codec timing remain unchanged.
- Unit tests cover reordering, gaps, sequence wrap and duplicates. Counters show UDP out-of-order/recovered/skipped to correlate with once-per-second stutters.

## Validation and limits
- Test parked for >= 90s, verify whether IJK emits OnCompletion or OnError near 30s and whether the new session reconnects.
- The reconnect may interrupt video briefly; actual uninterrupted playback still requires verifying the authenticated A500S app registration/heartbeat.
- For Direct, observe RTP AU/s and reorder counters; if the counters stay zero, stop attributing the periodic stutter to packet ordering and investigate H264 IDR cadence and decoder.
- Only two playback backends, ARM64-only, no AI, no LibVLC or official 70mai closed binaries.
