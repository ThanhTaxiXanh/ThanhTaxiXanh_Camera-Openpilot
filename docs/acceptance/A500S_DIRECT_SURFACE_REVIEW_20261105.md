# Independent owner-device review — A500S Direct live Surface preview (2026-11-05)

## Evidence and custody

Owner submitted the V1.3.35 Android screen recording directly in the
project conversation, following the repaired SurfaceView layout build #204
on main. Original 16,576,951-byte H.264/AAC MP4, 25.033 seconds,
512x1136, 30fps / 751 video frames.

SHA-256 of exact owner-uploaded media:
`25088e332fc58415d4d1d7b613096777fd7378b6ae747a120583e589f9d59161`

For privacy, the publicly committed review includes no original video,
screenshot, license plates, GPS location, scene coordinates or
identifying imagery. The SHA is a file identifier, NOT cryptographic
certification of device origin or APK provenance.

## What the physical recording shows

- At 0–2 seconds the top video frame is dark while the playback session
  is beginning. From approximately 3 seconds forward, an actual
  low-light changing A500S dashcam scene occupies the top 4:3 viewport.
- Manual sampling at seconds 3, 6, 9, 12, 16, 18, 20, 22 and 24 shows
  changing imagery; no persistent black Surface after startup.
- At 18–24 seconds the user scrolls the controls/diagnostics underneath
  the video. The video stays pinned at the top and continues changing,
  consistent with V1.3.35's separated ScrollView/FrameLayout fix.
- At 24 seconds diagnostic overlay reports A500S RTP/UDP,
  RTP 376.6 packets/s, access units 26.5/s,
  codec decode 29.5/s, Surface release 29.5/s.
  RTSP heartbeat 1/1 OK; reconnect 0/3 during observed window.
  Ten-second cadence reports source ~24fps, native release ~24fps,
  Android onFrameRendered callback ~16.1fps.
- The codec's AU→MediaCodec output P95 around 747 ms and
  native release→render callback P95 around 138.3 ms describe
  LOCAL processing telemetry, NOT A500S scene-to-screen optical delay.
- **YUV OFFSCREEN: TẮT, 0 frames** and RAW ONNX disabled. This recording
  does NOT validate the optional secondary YUV decoder.
- The previously reported error `Offscreen input queue full; await next IDR`
  occurred in the separate V1.3.34 YUV-ON experiment, not in this
  YUV-OFF preview trial.

## Scoped conclusion / remaining tasks

**Observed physical evidence that the V1.3.35 xADAS Direct MediaCodec
Surface preview shows live moving A500S video and remains visible as
controls scroll on the tested phone.** Its main viewport layout issue
appears resolved on this Android handset.

Criterion #3 also explicitly requires **RTSP fallback**, which was not
tested in this 25-second Direct-only clip; keep #3 at CODED pending a
separate independent IJK/reconnection/fallback evaluation.
Criterion #14 requires genuine A500S offscreen YUV→authorized ONNX;
this clip has YUV OFF. #20 requires >=300 seconds and independently
measured near-1s exposure-to-glass delay, not shown here.

Gate A **8/20 = 40%**, Gate B **0/5**. No PASS is promoted from
this evidence alone.
