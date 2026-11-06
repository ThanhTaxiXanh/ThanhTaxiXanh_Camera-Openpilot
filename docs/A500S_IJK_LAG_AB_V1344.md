# V1.3.44 — field-directed IJK buffering A/B and reconnect evidence

## User-facing installation

**Install V1.3.44 Lite ARM64 FIRST**, from the successful POST-MERGE
main GitHub Actions run. The AI APK is unnecessary for measuring
preview/optical lag and adds an unused ONNX runtime. Do not enable
PixelCopy, Direct/YUV secondary MediaCodec or ONNX during this test.

## Owner-only A/B protocol (car safely parked, A500S Wi-Fi)

1. Start IJK SMOOTH TCP; record 30s single-shot external video showing
   an actual road movement/hand wave AND phone's IJK viewport. Note
   visually estimated scene->screen delay, jitter, blackouts, if any.
2. Tap the IJK profile button ONCE: CÂN BẰNG TCP, then press the pinned
   PHÁT LIVE 70MAI · IJK/FFMPEG to create a fresh session. Capture
   equivalent 30s external video at roughly the same camera scene.
3. Optionally tap profile ONCE MORE: TRỄ THẤP TCP, press IJK LIVE to
   re-open; compare. This can stutter on some Android devices.
   STOP if preview stops updating; use the original SMOOTH mode again.
4. On the most usable profile, press THU BIÊN BẢN VIDEO 5 PHÚT,
   let it run until it exposes XUẤT BIÊN BẢN TTXLIVE01. Export and
   share the text. **If a reconnect occurs, leave the recorder running**;
   the resulting report will include the interruption and resumed
   telemetry and must still FAIL field #20.
5. Report the most effective profile, visible lag estimate, movement
   smoothness, any RTSP end/reconnect, and whether the 70mai official
   app shows materially lower lag on the SAME handset.

Modes:
- MƯỢT TCP: unchanged source baseline.
- CÂN BẰNG TCP: packet buffering ON, internal max cached duration 1000ms,
  FFmpeg max_delay 500000us, min 10 frames (experimental).
- TRỄ THẤP TCP: packet buffering OFF, 600ms cache bound,
  FFmpeg max_delay 300000us, min 5 frames; may stutter.
- RTP/UDP SMOOTH: transport comparison only; use later if TCP fails.

**NOT proven:** 1s optical latency, RTSP source cause, sustained
5min without reconnect, valid A500S native YUV/ONNX, model output
accuracy. Do not count a green CI as an extra PASS.
