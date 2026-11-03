# TTX Camera AI — 70mai / xADAS Playback Lite

**Package**: vn.thanhtaxixanh.camera.ai  
**Version**: 1.0.1-playback-lite  
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
