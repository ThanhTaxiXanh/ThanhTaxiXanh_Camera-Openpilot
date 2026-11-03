# TTX Camera AI — 70mai / xADAS Playback Lite

**Package**: vn.thanhtaxixanh.camera.ai  
**Version**: 1.1.2-xadas-rebuild  
**Target**: 70mai A500S (DR2500), Android arm64-v8a.

## Two playback tests — nothing else

- **70MAI · IJK/FFMPEG**: Open-source IJKPlayer 0.0.26 + FFmpeg 4.0, RTSP/TCP and preferred MediaCodec. This **does not claim to be the same binary/version/settings** as the official 70mai 4.5.0, which the owner reports plays much smoother than xADAS. The official APK contains a version-dependent IJK-based player.
- **xADAS · MEDIACODEC**: independent RTSP/RTP receiver with MediaCodec, UDP first and TCP fallback if zero RTP; a clean-room implementation based on observable architecture in xADAS, **not xADAS's original binaries**.

Both use rtsp://<A500S Wi-Fi gateway>:554/livestream/12 and Android Surface. Test only while parked or with a passenger capturing screen while in motion.

## Removed from the build and working tree

LibVLC, ONNX Runtime, OpenCV, TFLite, Media3/ExoPlayer, CameraX, phone camera, Openpilot parser, ADAS overlay/settings, screenshots, test models, old 17+ diagnostic reports, unused clock/pacing experiments. Historical code remains available through Git history and previous commits.

## Packaging and size

- Only arm64-v8a native binaries; **no fat APK containing x86/armv7**.
- Artifact contains exactly **one APK**, not APK + mapping files + assets.
- upload-artifact ZIP uses compression level 9. APK itself is a ZIP archive and is already compressed; the ZIP will be approximately the size of the APK. Removing libraries is how the total size is reduced.
- Actual sizes are printed during GitHub Actions build; do not estimate in advance.
- The official 70mai player is not redistributed: uses a publicly available LGPL open-source IJKPlayer 0.0.26 AAR release. Check license terms before distributing derivative binaries. This open-source build is **experimental only** and may fail on newer devices or expose unfixed decoder issues.

## Key limitation

A successful build does not prove smoothness. Prior versions displayed A500S RTP=0 at times and had fluctuating access-unit frame rates. Compare on the same camera and same Android phone, with AI disabled, before adding any ADAS functionality.

## How to install

Open GitHub Actions, choose latest successful build, download artifact ZIP, extract its single APK, and install on arm64 Android. Connect to A500S Wi-Fi and allow the phone to stay on a Wi-Fi network without internet access.


### Package validation fix (1.0.1)

The 1.0.0 test build was discarded: legacy `com.mcxiaoke...` Android Maven artifacts are Java-only stubs in Maven Central and produced **no IJK .so files**. V1.0.1 uses `io.github.jdpxiaoming:ijkplayer-java/ijkplayer-arm64:0.0.26`, distributed as Android AARs with native IJK/FFmpeg; CI now fails if any of the three required IJK shared libraries (libijkplayer.so, libijksdl.so, libijkwdzffmpeg.so; FFmpeg is renamed in this upstream build) are missing. Build status alone is not enough: check the APK native manifest in Actions logs.


Native validation: the published 0.0.26 ARM64 IJK variant intentionally calls System.loadLibrary("ijkwdzffmpeg") rather than "ijkffmpeg". Verified against its upstream IjkMediaPlayer.java loader. CI checks the matching .so name.

## V1.0.2 — Fix black video surface

Confirmed from the user's 18.87-second recorded V1.0.1 phone screen: RTP ~340 packets/s, H264 ~24 AU/s, decoder ~27–29 fps, yet black. IJKPlayer also reports first video frame at 640x480, yet the screen is black. Both independent decoders fail to *show* frames on the same shared `SurfaceView`.

Root-cause candidate fixed: the V1.0.1 Android `SurfaceView` had `setBackgroundColor(Color.BLACK)` directly on the view. The SurfaceView normally exposes video from a separate layer via a transparent region in the Activity window; drawing an opaque View background can conceal it. Now explicitly `background=null`, `PixelFormat.OPAQUE` for the underlying video surface, leaving the parent layout dark, and adds a CI regression guard.

This is a UI composition correction, not another codec/RTSP/UDP tweak. Success still requires an on-device test: a successful build and decoded frame counters cannot prove displayed image visibility.


## V1.0.3 — A500S playback recovery and UDP packet jitter

The owner reports that IJK/FFmpeg is smooth but stops after ~30 seconds, while xADAS-style MediaCodec continues but stutters about once per second.

- IJK keeps the exact successful V1.0.2 decoder/RTSP settings. It now detects OnCompletion, OnError, and native video output FPS stalls. Old native playback is released before opening a new RTSP session with bounded retries. HUD reports FPS, cache duration, stream age and failure cause. This is recovery, not confirmed uninterrupted streaming; A500S registration heartbeat still needs verification.
- Direct MediaCodec adds a bounded 55 ms UDP RTP reorder window, up to 32 packets, ahead of the H264 FU-A assembler. Late packets can fill a gap before the AU is discarded. The TCP mode is unchanged.
- The diagnostic HUD now reports out-of-order, recovered and missing packets every second. No heavy native dependencies or AI are added; APK remains ARM64 playback-only.


## V1.0.4 — Video evidence review + optional A500S owner heartbeat

Two videos supplied by the owner: IJK/FFmpeg 39.7s and xADAS-style MediaCodec 29.9s, both Android screen recordings at 30fps (not the dashcam FPS). At approximately t=30s, IJK reports **RTSP kết thúc (OnCompletion)**; automatic recovery restarts its session and delivers video again. Direct remains connected, but around t=25s UDP incoming packet rate collapses to ~64.5 packets/s and decoder/display ~2fps, returning above 300 packets/s afterwards. Out-of-order/recovered/skipped UDP counters stay stable throughout most of the clip, so extra jitter buffer is not justified.

**NEW – opt-in, owner-approved A500S pairing/registration.** The upstream A500S research describes `BindByBanya.cgi`, `UserconfirmByBanya.cgi`, `client.cgi?&-operation=register&-ip=...` and signed requests. TTX adds a **GHÉP ĐÔI & DUY TRÌ CAMERA A500S** button: the user initiates pairing, confirms physically on their camera, token is stored in private app storage with Android backup disabled, and signed `client.cgi` registration is sent every 5 seconds while IJK is active. Without pairing, IJK continues in legacy RTSP-only mode. An invalid registration result is shown; the app never forges a token, bypasses camera confirmation or changes camera settings. This tests whether the 30s completion originates from missing owner registration; **not proven on firmware yet**.

The Direct HUD additionally shows RTP marker/s, RTP timestamp changes/s and estimated jitter to separate source pacing from decoder timing. Two players remain and the build remains ARM64-only. The Openpilot/AI and LibVLC stacks remain removed.

Use while parked: first pair and accept on camera, then view IJK for >2 minutes. If the register counter stays 0/0 or returns nonzero ResultCode, the test has NOT confirmed heartbeat success. If it stays OK but IJK still completes at 30s, another cause must be investigated. The phone may need to re-pair after camera reset/firmware change. Pairing with TTX can replace the official 70mai app's active client session; do not run both apps simultaneously.

A500S protocol research: https://alu.dog/posts/reverse-engineering-the-70mai-android-app/ ; findings are specific to the author's DR2500 firmware, not guaranteed for all A500S versions.


## V1.0.5 — independent smooth vs. experimental IJK lower-latency setting

A500S owner reports both plain IJK and owner-paired IJK output with the **same 3–4s glass-to-glass delay**. Expected: pairing/CGI heartbeat is control-plane registration, **not a second decoder or latency optimization**. It should be evaluated by whether the RTSP stream continues beyond ~30s and CGI registration returns OK, not by FPS/latency changes.

The IJK playback screen now adds a **profile selector**, default **MƯỢT · ĐỆM MẶC ĐỊNH** (same packet-buffering, codec and RTSP options as V1.0.4) or **THỬ TRỄ THẤP** (turns player-side packet-buffering off, min-frames=5, max_cached_duration=600ms, FFmpeg RTSP max_delay=300000µs; unverified with A500S). Selecting a mode while video runs does not mutate the active IJK instance: press the 70mai play button to restart with new settings. The experiment may increase freezes/stuttering. The previously smooth profile remains one tap away. IJK HUD prints the selected mode and native videoCachedDuration (internal queue only; not physical camera-to-screen delay). No AI and no extra native dependencies added.

**Measure true latency** by briefly moving an object in front of the parked camera while viewing the phone and comparing timing, not solely comparing the camera OSD clock to phone time (the clocks may be unsynchronized). If low-latency sacrifices smoothness, return to MƯỢT. Also report whether paired CGI success prevents the former ~30s RTSP OnCompletion.


## V1.0.6 - Controlled RTSP transport A/B

Official 70mai 4.5.0 is ~1 second behind reality, TTX IJK both TCP profiles ~3 seconds on the same camera and phone. The earlier profiles both force RTSP/TCP. This build adds a third option: IJK/FFmpeg RTP/UDP with otherwise unchanged SMOOTH settings (packet buffering enabled, MediaCodec unchanged). It is a hypothesis test and NOT a claim that 70mai official uses UDP or that the delay will fall to one second. The selector cycles MƯỢT TCP, TRỄ THẤP TCP, THỬ RTP/UDP (GIỮ ĐỆM), then back. Restart playback to apply; automatic reconnect preserves the active selection. UDP may not work or may stutter: use SMOOTH TCP to return. No extra native libraries or AI.


## V1.1.0 – Independent xADAS full-feature rebuild foundation

The owner requested restoring the **complete xADAS feature set** instead of continuing playback-only experiments. This build restores independent modules from earlier TTX development history: settings, 11-head Openpilot result contract (1576 float values), lane/lead/road edge output parser, 12-channel temporal YUV packing, and their unit tests. It adds three active navigation entries: camera sources (A500S RTSP and validated custom URL; other xADAS adapter types documented but disabled), ADAS settings, and Openpilot model status / private ONNX import. App ID stays vn.thanhtaxixanh.camera.ai. Both IJK/FFmpeg and custom RTSP/MediaCodec playback remain; no proprietary xADAS code, model weights or graphics are packaged.

**Not a complete xADAS clone yet:** the ONNX import only stores a user-authorized file, not neural inference. The xADAS ONNX/NCNN runtime, direct YUV-to-virtual-warp pipeline, calibrated lane/lead projection, multi-brand camera handshake, GPS, PiP, audio warnings and UVC/CameraX still need independent implementation and validation. The HUD must never fabricate a lane or a distance. Full audited scope and acceptance criteria: docs/XADAS_REBUILD_FEATURE_MATRIX.md. Do not use the app as a safety control device.


### GPS live speed (owner opt-in)

GPS button requests runtime precise location permission and reads only Android GPS_PROVIDER speed. The HUD hides stale values after 3.5s; GPS is used for local display only and no history or coordinates are saved. It does not activate automatic distance warnings without calibrated AI lead detection.


### Picture-in-Picture test

PiP button now enters Android's supported windowed-video mode when IJK is active. Non-video control views are hidden while PiP is open; on returning they reappear. The SurfaceHolder is reattached on Surface recreation. Direct MediaCodec PiP is intentionally not enabled until its Surface lifecycle is validated. This is a first implementation; it requires on-device testing and is not guaranteed to preserve uninterrupted live RTSP on all Android vendors.


### Optional RTSP endpoint discovery (v1.1.2)

The camera source screen can probe only six read-only DESCRIBE requests that were found in the uploaded xADAS APK (`/livestream/12`, `/livestream/0`, `/liveRTSP/av4`, `/liveRTSP/av2`, `/liveRTSP/av1`, `/liveRTSP/v1`). A response 200 supplies a suggested URL that the user must explicitly save. It does not indicate rear-view support or guarantee SETUP/PLAY. No change to camera firmware or CGI configuration is performed.


### Phone camera source / Camera2

Selecting **Camera điện thoại** in the source manager routes playback to an independent Android Camera2 preview with a parallel YUV_420_888 capture Surface. CAMERA runtime permission is requested only when using the phone source. The YUV ImageReader consumes the latest frame without blocking preview; Openpilot inference and safety warnings are OFF until calibrated models are verified. This is groundwork for xADAS phone-camera support, not confirmed hardware compatibility on every Android phone.
