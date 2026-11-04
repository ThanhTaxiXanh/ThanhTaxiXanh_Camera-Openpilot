# TTX Camera AI — Independent xADAS Functionality Rebuild

**Package**: vn.thanhtaxixanh.camera.ai  
**Version**: 1.2.8-xadas-axis-timing-audit (Lite / AI flavors)  
**Target**: 70mai A500S (DR2500), Android arm64-v8a.

## Playback and ADAS development modules

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

## V1.2.1 — development before the next phone test

Owner requirement: do not request a new phone/camera installation test until pre-device engineering readiness is at least 90%. This release has NOT reached that threshold.

- Lite: ARM64 IJK/FFmpeg + RTSP, camera/GPS/PiP and settings; no ONNX native runtime.
- AI: includes ONNX Runtime 1.31.1 and an independent model loader for explicitly authorized ONNX models; no xADAS proprietary models or code are distributed.
- Real Phone Camera2 YUV can feed an optional OFFSCREEN raw inference diagnostic: stride-aware YUV420 -> temporal calibrated-warp API -> two 12-channel tensors -> ONNX -> Openpilot 1576-value parser. It is NOT calibrated, so no live lanes/distances/warnings are emitted.
- Unit-tested temporal frame suppression, one-slot AI worker, perspective projection, model-confidence and age gate, headway/TTC passive decision logic, virtual-camera warp and YUV420 packing.
- A500S IJK 3-second playback delay and 30-second RTSP completion are not resolved; Android Surface-configured MediaCodec cannot supply raw YUV to AI via getOutputImage.
- USB/UVC and multiple other vendor-specific camera adapters are not implemented; real-world model warp/calibration and metric lead detection are still missing.

See docs/PREDEVICE_90_GATE.md for required completion evidence. CI separately compiles/tests Lite and AI and checks all native libraries.


## V1.2.2 — nonblocking offscreen YUV acquisition for Direct RTSP

- Added `H264YuvSidecar`: a **second, optional** Android MediaCodec decoder
  configured with a null Surface so output frames can be copied as YUV_420_888.
  It receives the exact same already-assembled H264 Annex-B access units used
  by the Direct preview. **No new RTSP connection** is opened.
- The Direct Surface decoder remains independent and never waits for the YUV
  decoder. The optional sidecar uses a 16-AU bounded queue; on saturation it
  resets its own decoder and waits for a new IDR rather than decoding a broken
  prediction chain. The IJK player's proven path is unchanged.
- The **YUV A500S: TẮT/BẬT** button is an explicit opt-in for *raw offscreen
  acquisition diagnostics*, not an ADAS enable switch. Default OFF. HUD
  displays sidecar decode, output frames, IDR skips, resets and errors.
  Switching OFF releases the auxiliary decoder without stopping preview.
- Annex-B start-code and IDR recovery logic have repeatable JVM regression
  tests. Both Lite and AI flavors receive the YUV acquisition adapter.
- This is **not** live Openpilot inference or xADAS-equivalent ADAS.
  Source intrinsics, temporal model input, legal ONNX model, real ground truth
  and calibrated lead geometry remain unverified. Distance, lanes and warning
  activation remain safely disabled. An offscreen second decoder may be
  unsupported on devices with limited hardware codec instances.
- Engineering gate #14 is now **CODED (Direct only), NOT PASS**. This version
  does not reach the pre-device >=90% bar; no new on-phone test is requested.

CI also runs on pull requests so both variants must pass before merging.

## V1.2.3 — A500S raw ONNX path, intentionally without ADAS claims

- Independent Direct RTSP path can now be assembled as **RTP H264 AU → opt-in
  offscreen YUV420 MediaCodec → CPU-owned YUV420 frame → stride-aware ARGB
  conversion → chronological temporal pairing → OpenpilotInferencePipeline →
  a user-imported, contract-checked ONNX graph**. The Lite APK cannot run the
  model. The existing IJK video path is unchanged.
- Owner starts the Direct player, explicitly enables **YUV A500S** and then
  **ONNX A500S: RAW-ONLY**. No automatic inference, recording, upload,
  warnings, vehicle control, metric lead, or lane visualization.
- The model's exact camera intrinsics/warp and temporal/channel conventions
  remain unvalidated. Two research-only warp matrices are passed as
  experimental tensor-layout placeholders. Model output is **untrusted raw
  diagnostic data** regardless of input validity or output plausibility.
- `A500sRawVisionBridge` accepts only the observed 640×480 source format,
  rejects frames >550ms old / from the future, and will not pair reversed
  frames or observations separated by >850ms. Reconnect resets the pair.
  Its original access-unit timestamp is only the **local RTP receipt time**,
  not a measured camera exposure time.
- Raw frame pairs and pipeline model results are counted on-screen.
  Model loading runs on its own thread; cancellation prevents stale session
  results from being re-enabled; the inference worker releases the native
  session only after its processing loop exits.
- Pure JVM tests cover invalid dimensions, staleness, reordered frames, gaps,
  reconnect and rejected pipeline input; GitHub Actions builds Lite/AI.
- This milestone does **NOT** satisfy the pre-device 90% gate: real A500S
  YUV path, model output correspondence, calibration and hardware FPS/latency
  remain unverified. No new owner device test is requested.


## V1.2.4 — offscreen decoder epoch recovery and test-vector correctness

- A frame's local access-unit receipt time is captured before any preview
  queue wait, so a stale frame cannot appear newly arrived to the AI gate.
- Sidecar MediaCodec presentation timestamps now use a unique **local monotonic
  input-receipt clock** for offscreen output correlation, avoiding ambiguous
  32-bit RTP clock wraps or multiple access units sharing a timestamp.
  The primary preview RTP PTS/pacing is unchanged. This local time is **not**
  camera exposure time or a measurement of glass-to-glass latency.
- Every sidecar overflow, detected damaged H264 access unit or secondary codec
  exception resets a **secondary decoder epoch** and waits for a valid IDR.
  Queued inputs and output callbacks from previous epochs are suppressed;
  the live Surface decoder is not touched.
- Resets propagate to the ONNX temporal pair bridge without blocking the
  RTSP receiver: receiver threads only set an atomic discontinuity flag;
  the offscreen frame worker consumes it before constructing another pair.
- The secondary worker handles decoder errors during *idle output draining*,
  rather than terminating silently and leaving a permanently full queue.
- JVM tests verify monotonic offscreen PTS, repeated same-millisecond access
  units, clock reset/overflow, prevention of temporal pairs across corrupted
  GOPs and a deterministic synthetic 12-channel YUV 2x2 fixture.
- **Not a real xADAS golden model fixture**. We cannot assert model/channel
  parity, real camera calibration, trustworthy lead metres or safety readiness
  from synthetic generated vectors. Live ADAS rendering and warnings stay OFF.
- Both APK flavors remain ARM64. No xADAS binaries or weights are included.
  No hardware test required until the separately tracked >=90% engineering
  pre-device gate is achieved.

## V1.2.5 — preprocessor fail-closed controls and authorized ONNX reference audit

- Camera homographies are **immutable**, finite and invertible. Perspective
  sampling reports in-source pixel coverage and refuses a mostly black
  projected frame for ONNX diagnostics (<80% coverage). This does **not**
  calibrate camera intrinsics or prove model-coordinate equivalence.
- Both model inputs are constrained to the current independently implemented
  studio-range YUV layout: 12 float32 channels [1,12,128,256],
  luma 16..235 and chroma 16..240. An accidentally normalized [0,1]
  tensor, invalid warp, NaN or out-of-range sample fails closed.
  Real xADAS preprocessing parity remains **UNVERIFIED**.
- The AI-flavor ONNX loader checks **float32 dtype and exact shape** of both
  model inputs and the 1,576-float output. Lite has no native ONNX backend.
- Model screen adds **ĐỐI CHIẾU VECTOR ONNX THAM CHIẾU** for a user-supplied,
  legally usable offline `.ttxref` fixture bound by the exact imported
  model's SHA-256. It runs that model against two supplied float32 tensors and
  compares all 1,576 outputs using absolute+relative tolerances.
- Fixture reader strictly bounds length, header, raw-input channel ranges,
  finite expected outputs and rejects trailing bytes. Nothing is uploaded
  or persisted from the comparison.
- Regression tests verify crop coverage, degenerate transforms, immutable
  matrix ownership, deterministic interpolation, studio channel ranges,
  the reference fixture binary protocol and numeric tolerances.
- **PASS of a user-provided reference is not proof of correct reference
  provenance, calibration or road safety**. No actual reference model/vector
  artifacts are included, model weight redistribution is excluded, and
  warning/HUD features remain gated.

See `docs/ONNX_REFERENCE_VECTOR_V1.md` for the fixed reference format.

## V1.2.6 — Offline reprojection diagnostics and gap-safe lane geometry

- A new **Camera Geometry Audit** screen in Settings accepts a locally owned
  plain-ASCII `.ttxcal` marker file (max 64 KiB) containing assumed pinhole
  height/FOV/pitch/yaw, image size, and measured forward/right/up marker
  coordinates with their observed pixels. No image or video is uploaded.
- The independent `CameraReprojectionAudit` enforces >=8 non-duplicate
  markers, minimum horizontal/vertical/depth coverage, finite coordinates,
  visible projections and RMS/P95 reprojection residuals. Clear error statuses
  help reject inconsistent physical camera assumptions before model use.
- **A numerically perfect fit does not imply actual camera calibration**.
  Synthetic data can match any assumed geometry; imported evidence always has
  `groundTruthValidated=false` and `modelCoordinateFrameValidated=false`.
  Audit reports never authorize HUD overlays, lead metres or warnings.
- `LaneProjection` no longer draws continuous polylines across invalid or
  offscreen model points. It returns only runs of >=4 adjacent valid points,
  preserving genuine breaks rather than visually inventing road lines.
- Host-unit tests cover pinhole residuals, synthetic fit/wrong pitch, missing
  marker distribution, forged coordinates, CSV limits and lane gap handling.
  See `docs/CAMERA_GEOMETRY_AUDIT_V1.md` for the text fixture specification.
- The 90% pre-device gate is still unmet: A500S camera intrinsics, lens
  distortion, model-coordinate and metric lead ground truth are unverified.

## V1.2.7 — Candidate lens distortion and independently measured lead error

- New pure Kotlin `LensDistortionProjection` implements Brown-Conrady
  radial (k1/k2/k3) and tangential (p1/p2) terms, with fx/fy/cx/cy pixel
  intrinsics, finite bounds and offscreen rejection. **Diagnostic only**:
  it does not modify the live CameraProjection or activate ADAS.
- Existing `.ttxcal` import now also accepts `TTXCAL02` with
  `lens,fx,fy,cx,cy,k1,k2,p1,p2,k3`. Existing `TTXCAL01` remains
  backward-compatible. RMS/P95 use the specified model; all evidence
  still sets groundTruthValidated/modelCoordinateFrameValidated=false.
- Local `.ttxlead` comparator reads separately measured reference
  and already-metric predicted distance pairs, requiring >=12 unique
  frames spanning >=30 m and 3 range bands; reports MAE, RMSE,
  P95 absolute error, MAPE and signed bias. **Never** treats raw
  xADAS/Openpilot lead tensor values as metres. SHA-256 field is
  declared metadata, not an independently authenticated signature.
- Both options are accessible from Camera geometry diagnostics in
  Settings. Fully local: no telemetry, no recording, no weights.
- Regression tests cover zero-distortion parity with pinhole, barrel
  and tangential geometry, invalid optics, wrong lens detection,
  legacy fixture continuity, independent range statistics and malformed
  local data. See `docs/CAMERA_LENS_LEAD_AUDIT_V2.md`.
- Numerical agreement on synthetic or manually supplied evidence
  is NOT a camera certificate or safety acceptance. The 90% gate
  remains unmet, with no new phone test requested.

## V1.2.8 — 3D model-axis hypothesis and synchronized range-data auditing

- New pure `ModelAxisAudit` and 64 KiB `TTXAXIS01` local CSV reader
  compare user-supplied raw X/Y/Z hypotheses with independently measured
  forward/right/up coordinates. Four lateral/vertical sign hypotheses are
  ranked by 3D RMS error, enforcing spatial coverage, uniqueness and
  ambiguity gates. A synthetic or apparently best-matched sign is
  **never** trusted for production camera geometry or ADAS.
- New `TimedLeadAudit` checks 12–512 metric range pairs against strictly
  increasing timestamps declared to share one monotonic session clock.
  It refuses statistics if absolute per-pair skew exceeds 100 ms,
  if timestamps regress or ranges fail their existing coverage checks.
  Camera RTP receipt time is not verified camera exposure time.
- `LeadDistanceFixture` gains `TTXLEAD02` (clock row + reference/prediction
  timestamps per sample) and preserves legacy `TTXLEAD01` CSV behavior.
  The diagnostic screen displays P95/max timestamp skew for V2.
- Model axis and timed lead tests include synthetic correct/wrong signs,
  ambiguous axes, clock regressions, skew violations, malformed CSV and
  legacy V1 compatibility. See `docs/AXIS_AND_TIMING_AUDIT_V1.md`.
- SHA-256 and source/device names in these local CSV fixtures are
  self-declared, **not independently authenticated**. No real model weights,
  proven model axes, physical lead distance or ADAS warnings are included.
  The 90% device gate is still unmet and no new A500S phone trial is needed.
