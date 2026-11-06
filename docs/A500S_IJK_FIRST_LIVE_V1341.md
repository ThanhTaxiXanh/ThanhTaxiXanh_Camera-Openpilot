# V1.3.41 — IJKPlayer-first A500S live screen: operator decision

## Why the project changed direction

On 2026-11-06 the camera owner explicitly requested abandoning the
slow-to-stabilize dual-MediaCodec xADAS preview/YUV investigation in
favor of the existing 70mai-style IJKPlayer/FFmpeg playback path.

The owner's latest Android video recording has a duration of 75.466667s,
H264 512x1136 at 30fps, 2264 video frames; SHA-256:
`1a32cd853ef88faf76bf916ec9c65edc74c204103b82264a0faab5724bf8e63e`.

A late screen sample at about 72 seconds still displays xADAS Direct
RTSP primary Surface and OPTIONAL YUV sidecar diagnostics with
`Offscreen input unavailable after 3 drain/wait attempts`.
The optional platform-default YUV codec was shown as
`OMX.qcom.video.decoder.avc [DEFAULT]` with errors reported.
This validates the owner's operational objection to relying on the
current TWO-decoder setup. It does NOT prove the IJK route meets the
official 70mai 4.5.0 latency; the uploaded video does not demonstrate
an IJK-only comparison or physical optical scene-to-glass clock.

The raw video, scene data, license plates and camera overlays are
not published in this repository. The SHA above is a file identifier,
not a signed camera attestation.

## User-facing architectural decision

1. The IJKPlayer/FFmpeg action `PHÁT LIVE 70MAI · IJK/FFMPEG`
   is the PRIMARY and permanently visible control directly below
   the fixed SurfaceView/ADAS HUD viewport; it does not disappear
   while scrolling diagnostics.
2. Current proven IJK default remains `SMOOTH`: RTSP/TCP,
   packet-buffering on, native IJK mediacodec=1, framedrop=1,
   unchanged probing parameters. No speculative low-latency
   flags were added. Low-latency TCP and UDP-smooth remain
   explicit user-selected experiments.
3. The xADAS-style Direct MediaCodec + offscreen YUV/RAW ONNX
   experiments are kept for forensic/research use but collapsed
   behind an explicitly labeled `THỬ NGHIỆM xADAS / YUV` control.
   No automatic fallback from IJK to Direct is introduced.
4. Existing IJK owner heartbeat, native FPS/cached-duration diagnostics,
   initial frame watchdog, IJK-only reconnect/release logic, PiP,
   GPS and the 300s local TTXLIVE01 recorder remain available.
5. IJK streaming renders frames to the Android Surface. **It does
   NOT currently supply native YUV420 frames to A500sRawVisionBridge.**
   There is no claim that IJK playback enables xADAS/Openpilot
   12-channel temporal ONNX input, lens calibration or trustworthy
   lane/lead distances. Building a licensed, authorized frame tap
   from IJK/FFmpeg requires separate design and hardware parity.
6. The repository uses the existing open-source IJKPlayer dependency.
   This is not the proprietary 70mai 4.5.0 native library or SDK
   and does not imply access to its private low-latency tuning.

## Owner verification on parked vehicle

- From successful post-merge main Actions artifact, install
  V1.3.41 Lite (arm64-v8a) and connect to own 70mai A500S Wi-Fi.
- Press the prominent IJK LIVE action; check that the top video
  shows actual moving frames and the IJK native FPS >2 sustained
  over at least 60 seconds. Start with the default MƯỢT TCP mode.
- If playback stalls, the existing IJK watchdog may reconnect;
  record those details. A reconnect is not proof of uninterrupted
  field stability.
- If possible, record an owner-triggered 300-second TTXLIVE01
  in IJK mode for independent local continuity review. No need
  to enable Direct, YUV or ONNX.
- Near-1-second glass-to-glass latency needs external synchronized
  camera/scene measurement. Do not infer it from FPS or cache ms.

## Acceptance boundaries

Gate A 8/20 = 40%, Gate B 0/5 remain unchanged.
IJK-first usability and CI checks cannot promote physical
#3 fallback, #14 A500S YUV→ONNX, #17 HUD metric parity
or #20 actual optical latency.
