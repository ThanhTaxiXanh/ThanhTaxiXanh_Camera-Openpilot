# xADAS 0.2.7 → TTX Camera AI: reconstruction matrix

Source: user's xADAS XAPK (package com.kaibatech.drivedeck.xadas, v0.2.7, vCode44), analyzed on 2026-11-03. The source is proprietary. This repository uses independent modules and does not redistribute its model weights, licensing code, binaries or original graphic assets.

## Confirmed from supplied XAPK
- RTSP adapters: 70mai CGI/HiSilicon/MStar, Vietmap generic/KC01/M1/Novatek/V740, AZCAM and custom RTSP.
- Local source: CameraX phone camera, USB/UVC adapters.
- Media: RTSP OPTIONS / DESCRIBE / SETUP / PLAY, RTP/UDP and interleaved RTP/TCP, RTCP reports, vendor CGI registration/heartbeat, MediaCodec callback and YUV.
- Image inference: embedded ONNX ~92.9MB and NCNN ~92.2MB, input tensors img and big_img shape [1,12,128,256], output 1576 floats. 11 heads in exact output order: meta(55), desire_pred(32), pose(12), wide_from_device_euler(6), road_transform(12), lane_lines(528), lane_lines_prob(8), road_edges(264), lead(144), lead_prob(3), summarizer_features(512).
- Postprocessing: detected lanes, road edge, candidate lead vehicle, confidence, temporal and camera focal/pitch/yaw calibration, FOV and virtual warp.
- UX: video preview, ADAS HUD, distance, GPS-derived speed, warnings, camera-source picker, extensive settings, PiP, diagnostics and background tasks.
- Product/licensing exists in original APK but is NOT cloned or bypassed.

## Reimplementation status (this commit)
| Area | TTX source | Verified runtime state |
| --- | --- | --- |
| 70mai A500S live view | IjkMediaPlayer + RTC support, previously user-tested | Playback works smooth but ~3s behind official, RTSP completion ~30s |
| Independent xADAS-style RTP | DirectRtspPlayer | Plays, intermittent stutter observed |
| 70mai physical owner pairing | A500sOwnerSession | Owner confirmed CGI 7/7 OK, does not eliminate RTSP OnCompletion |
| Source picker | CameraSourceCatalog/CameraSourceSettingsActivity | A500S+custom RTSP active; other adapters listed, not implemented |
| ADAS settings | AdasPrefs/AdasSettingsActivity | Prefs saved; AI-dependent ones not yet executable |
| Openpilot contract/parser | SupercomboV027Contract, OpenpilotVisionDecoder, OpenpilotYuvPacker | Pure code + unit tests, **not connected to live decoder** |
| User model file | VisionModelActivity | Import locally only; inference not enabled |
| Transparent ADAS HUD | AdasHudView | UI only; no fake lane/distance estimates |
| NCNN native + ONNX runtime | Not bundled | Missing |
| Geometric projection / virtual warp | Not bundled | Missing, blocks meaningful distances |
| GPS speed | GpsSpeedMonitor + AdasHudView | Opt-in GPS_PROVIDER; speed hidden after 3.5s without valid fix |
| CameraX / USB UVC | Not bundled | Missing |
| Picture-in-Picture (IJK) | MainActivity + Android PiP mode | Implemented experiment; requires on-device Surface lifecycle test |
| Background service/notifications | Not bundled | Missing |
| Other vendor protocols | Catalog only | Missing |

## Why not ship the two xADAS models
The user's APK contains independently packaged 92.9MB ONNX and 92.2MB NCNN assets. The xADAS terms attribute ownership of its software and model assets to the publisher. Therefore repository publication must not assume the weights are freely redistributable. xADAS's paywall/license enforcement is not reproduced or bypassed. Openpilot's published repository is MIT licensed, but it is not proof the xADAS-specific weights or implementation are MIT-licensed. Any model integration requires a verified permitted model and preprocessing calibration.

## Engineering blockers for safe AI
1. Validate the A500S RTSP media clock and preserve <~1s end-to-end latency (official 70mai 4.5.0 achieves ~1s in owner observation; current TTX IJK ~3s).
2. Implement a decoder with genuine YUV frame access in parallel with stable preview. Android MediaCodec.getOutputImage() may return null if configured for Surface output; don't pretend SurfaceVideo can also yield an Image.
3. Validate 12-channel temporal YUV layout, virtual wide-camera warp and exact camera crop. Network native inference and output parser must be matched to a specific permissible model.
4. Calibrate camera height/pitch/yaw/focal and lane projection. Validate lead distance against known reference. If stale, uncertain, or uncalibrated, suppress lanes/distance/warnings.
5. Implement real GPS and warning audio only after measurement pipeline is validated. No brake/steering/CAN actuation.

## Engineering sequence
- Functional shell, source catalog, GPS and PiP experiments (v1.1.1)
- Camera source adapters and RTSP 30s live session robustness
- Licensed model inference and decoder YUV; model consistency tests
- Full calibrated HUD, GPS, alerts and confidence behavior
- PiP, offline diagnostics/recording and settings parity
- Physical A500S verification, same-phone latency comparison vs official 70mai and xADAS

This document is a truthful gap list; a successful Gradle build is not acceptance that xADAS functionality has been fully duplicated.
