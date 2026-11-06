# V1.3.42 — IJK/FFmpeg sustained-output watchdog

## Fix driven by V1.3.41 source review

The old MainActivity watchdog treated `videoOutputFramesPerSecond > 2`
as healthy even when native `isPlaying = false`. A cached positive FPS
could mask a stopped native player. Separately, a single
`MEDIA_INFO_VIDEO_RENDERING_START` event disabled the startup timer,
even if no sustained FPS ever followed; the stream could then hang indefinitely.

`IjkPlaybackHealthGate` now requires BOTH `isPlaying` and finite output
FPS above 2 to refresh health. After a new IJK session, absence of this
condition for over 12 seconds requests an IJK-only reconnect, irrespective
of the one-time first-frame notification. Following healthy playback, two
samples more than 5.5 seconds after the last healthy tick initiate recovery.
A healthy sample resets the failure countdown; reconnect resets the full
gate. Backward/non-monotonic samples never assert health.

These are bounded *native-player telemetry* guards, not a guarantee that
the Android display showed a new frame. Output FPS may itself be stale
even while native `isPlaying` remains true. Optical source-to-screen
delay cannot be inferred from FPS, output-cache duration or Info events.

## Scope

- Keep the V1.3.41 user-requested IJK primary button, stable TCP
  SMOOTH profile and IJK-only reconnect. No automatic Direct fallback.
- No native FFmpeg YUV frame tap or model import.
- Seven deterministic JVM unit tests exercise startup, stalled playback,
  stale non-playing FPS, recovery, bad values, reset and clocks.
- Validate exact PR head via Lite/AI GitHub Actions before merge.
- No inference safety/ADAS HUD changes and no proprietary 70mai SDK.
- Acceptance remains Gate A **8/20 = 40%**, Gate B **0/5**.
  Camera-specific live IJK, long-run and optical timing verification remain
  pending independent physical A500S evidence.
