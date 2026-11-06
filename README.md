# TTX Camera AI — Independent xADAS Functionality Rebuild

**Package**: vn.thanhtaxixanh.camera.ai  
**Version**: 1.3.41-ijk-first-a500s-live (Lite / AI flavors)  
**Target**: 70mai A500S (DR2500), Android arm64-v8a.

## Current acceptance: TWO separate gates (V1.3.17)

**V1.3.41: IJKPlayer/FFmpeg is now the owner-requested PRIMARY A500S live video mode.**
The IJK LIVE button is fixed below the camera viewport; Direct
MediaCodec and secondary YUV/ONNX experiments are collapsed into
an explicit advanced panel, NOT auto-started or used for IJK recovery.
Previous IJK smooth TCP profile, IJK-only restart/owner heartbeat,
native FPS diagnostics, GPS/PiP and five-minute IJK local trace remain.
This uses the repo's open-source IJKPlayer, **not proprietary 70mai
4.5.0 binaries**. It does NOT introduce an IJK-to-ONNX YUV adapter,
validated ADAS or one-second optical latency. See
`docs/A500S_IJK_FIRST_LIVE_V1341.md`.
Gate A **8/20 (40%)**, Gate B **0/5**.


V1.3.39 physical follow-up (owner 61s A500S screen video): at t45
software AVC sidecar delivered 16 real YUV frames/912 decoded outputs,
with 829 stale outputs but **0 native YUV decoder resets or errors**.
The V1.3.39 stale-output reset-loop fix is independently supported,
yet live YUV remains too sparse for continuous temporal ONNX. At t48
Direct RTSP reports Socket closed and later reconnect 2/3; optical
continuity is NOT verified. V1.3.40 introduces owner-controlled codec
comparison: existing SOFTWARE-FIRST default vs SYSTEM DEFAULT for
SECONDARY YUV decoder only (platform may still pick software).
Codec choice is locked while playing; original Surface preview stays
untouched; no stale YUV is admitted to AI. See
`docs/acceptance/A500S_V1339_FIELD_AND_V1340_CODEC_TRIAL.md`.
Gate A 8/20 (40%), Gate B 0/5.


V1.3.39 reviews the owner's 84.03s A500S screen recording and
actual TTXYUV02 with FOUR SHA-256-valid 640x480 YUV frames. Gaps
30.205s, 7.272s, 8.723s prove non-temporal only: no YUV→ONNX parity.
Video diagnostics at 48s show 27 decoder resets and 27 stale YUV
outputs but zero exhausted input-buffer errors; V1.3.38 reset the
whole sidecar GOP on every expired DECODED output. V1.3.39 drops
late output and invalidates temporal data WITHOUT resetting the
native decoder on age alone; actual corrupted/stale H264 input and
invalid clocks remain fail-closed. See
`docs/acceptance/A500S_REAL_FOUR_YUV_REVIEW_20261106.md`.
Real-device retest required; Gate A 8/20, Gate B 0/5.


V1.3.38: Independently reviewed the owner's real 2026-11-06
A500S field bundle: valid one-frame TTXYUV02 (embedded SHA-256
verified, 640x480 real scene), 300.548s TTXLIVE01 Direct Surface
progress with zero reconnect but YUV NOT ARMED, and 128.551s GPS/PiP
trace with two rejected PiP requests. See
`docs/acceptance/A500S_REAL_FIELD_BUNDLE_REVIEW_20261106.md`.
The phone video showed software AVC YUV input starvation and repeated
sidecar resets. The independent software secondary decoder now receives
a larger BUT BOUNDED 160ms maximum input wait/drain policy; hardware
remains 30ms, and expired frame/GOP epoch checks still fail closed.
This is not yet physically proven to fix YUV and is not ONNX inference.
Gate A remains **8/20 (40%)**, Gate B **0/5**.


V1.3.37 fixes the owner's inability to save A500S YUV after a real
106.27-second test: the strict TTXYUV01 collector required 4 frames with
every successive receipt gap <=500ms, and cancelled a damaged GOP.
The additional TTXYUV02 **non-temporal diagnostic** exports 1..4 real
640x480 YUV420p decoder frames from a bounded RAM cache even if the
sidecar later stalls. TTXYUV01 stays untouched and only accepts temporal
frames for OFFLINE input checks. TTXYUV02 cannot be used as validated
ONNX temporal pairs. See docs/A500S_YUV_SNAPSHOT_FIELD_V1337.md.


V1.3.35 owner's actual 25-second phone trial verified the repaired,
persistent xADAS Direct Surface preview remains visible with YUV OFF,
including while scrolling diagnostics. This is scoped A500S preview
hardware evidence only: no alternate IJK fallback or 5-minute optical
latency was tested, so #3/#20 are not promoted. See
`docs/acceptance/A500S_DIRECT_SURFACE_REVIEW_20261105.md`.

V1.3.36 addresses the previously observed OFFSCREEN decoder's native
input starvation without changing the Direct Surface player. The optional
A500S YUV sidecar now prefers software-only AVC when Android advertises
one, falling back to the platform default if absent or configuration
fails, and drains native outputs on bounded input-buffer waits before
resyncing to an IDR. Decoder identity and starvation counters are shown
in diagnostics. See `docs/A500S_SOFTWARE_YUV_SIDECAR_V1336.md`.


V1.3.35 fixes a video visibility defect shown in the owner's actual
A500S phone recording: the Direct Surface received video frame callbacks
but its FrameLayout had height=0/weight=1 alongside many fixed-height
controls, leaving no visible preview. Both Direct MediaCodec and IJK video
now occupy a persistent 4:3 top viewport; diagnostics and buttons scroll
independently underneath. PiP enlarges the frame to the window and restores
its normal size on exit. See docs/DIRECT_PREVIEW_LAYOUT_FIELD_DEFECT_V1335.md.
No pixel-data or decoder fix is claimed, no new criterion PASS.


V1.3.34: owner-triggered TTXLIVE01 5-minute trace now records independent
Direct preview Surface-release and A500S offscreen MediaCodec YUV callback
progress when YUV tap is enabled BEFORE starting the trace. It detects YUV
stall/disabled tap/missing decoder metrics/new resets/new errors and exports
local RAW/ONNX counters separately without declaring model accuracy or
optical latency. See docs/A500S_DUAL_STREAM_FIELD_TRACE_V1334.md.


V1.3.33 selects the Camera2 phone lens by its advertised physical
640x480 YUV_420_888 support instead of blindly taking the first rear
camera ID. Rear lenses remain preferred; a viable front/other camera
can be used if no suitable rear exists. REALTIME clocks are preferred
within the same lens category; UNKNOWN continues to fail closed for RAW
ONNX. Ten pure JVM tests cover this policy; a real owner Android Camera2
trial has now been independently reviewed for criterion #8.
See docs/PHONE_CAMERA2_MULTILENS_V1333.md.


V1.3.32: Direct entry to Android phone Camera2 (rear preview + YUV 640x480)
without the 70mai Wi-Fi. A 60-second user-armed Camera2 diagnostic recorder
exports TTXPHONE01 via Android SAF; a separate owner-armed four-frame native
YUV snapshot exports TTXYUV01 for independent offline inspection. Neither
requires ONNX, nor automatically promotes #8. See
`docs/PHONE_CAMERA2_FIELD_EVIDENCE_V1332.md`.


V1.3.31 fences the offscreen MediaCodec YUV output by source epoch and
receipt freshness BOTH before and after native Image plane copying.
Rejected/unsupported YUV never updates cadence; diagnostics expose discard
reasons. See docs/OFFSCREEN_YUV_DELIVERY_LEASE_V1331.md. No camera hardware
claim or acceptance promotion.


V1.3.30 applies a nonblocking source-GOP epoch fence to RAW A500S
YUV→ARGB copies, temporal pairs and in-flight ONNX inference. Delayed
results from a revoked GOP are suppressed. Synthetic JVM concurrency tests
only; see docs/RAW_GOP_ONNX_EPOCH_FENCE_V1330.md.


V1.3.29 corrects RTP source-epoch accounting and RFC 3550 RTCP Receiver
Report intervals (including signed cumulative loss, late packets and SSRC 0).
See docs/RTCP_RECEIVER_REPORT_EPOCH_V1329.md. This does not demonstrate
hardware playback or a camera-to-screen latency reduction.


V1.3.28 prevents foreign RTP payload types from entering the selected H.264
Direct video decoder or UDP reorder queue; tested with synthetic loss/reorder
fixtures. See docs/RTP_SELECTED_PAYLOAD_GUARD_V1328.md. No physical A500S
validation is claimed.

V1.3.27 restricts H.264 SPS/PPS in RTSP SDP to the selected video media
section and matching payload type; malformed/mismatched codec headers are
not forwarded to MediaCodec. See docs/RTSP_SDP_H264_CSD_V1327.md.
This is protocol hardening, NOT camera/AI or optical-latency acceptance.


- **Gate A engineering readiness:** 8/20 (40%); target >=18/20.
  Criterion #8 is independently evidenced by the owner's Android Camera2
  session/video and checksum-verified 640x480 YUV samples; see
  docs/acceptance/PHONE_CAMERA2_REAL_DEVICE_REVIEW_20261105.md.
- **Gate B real camera/model/metric validation:** 0/5 mandatory
  requirements (#14, #15, #16, #17, #20).
- **Final acceptance 90% requires BOTH A>=18/20 AND B=5/5.**
- PR/CI GREEN never proves camera exposure delay, legal model provenance,
  lane/lead distance or physical decoder capacity.

See docs/TWO_GATE_ACCEPTANCE.md and
docs/acceptance/two_gate_ledger.json. GitHub Actions validates the
machine ledger on every PR and push; separate independent physical
evidence is required before a mandatory field PASS.

The old build-and-protocol version history below does not override the
new acceptance policy.

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

## V1.2.9 — Synthetic A500S raw integration, GOP epochs and latency semantics

- An independent end-to-end host fixture runs 640x480 YUV420 through
  ARGB conversion, A500S temporal pair bridge, both 512x256 warps,
  FLOAT32 12-channel preprocessing, fake inference, and the 1,576-value
  Openpilot parser. No licensed real model or actual A500S image is bundled.
- Inference pipeline now uses a nonblocking **epoch invalidation barrier**:
  RTP packet damage, H264/GOP reset, offscreen YUV fault and reconnect
  discard pending and in-progress model results from the old source epoch.
  Camera2 RAW input gets the same temporal discontinuity protection.
- Source frame pairs are limited to 500ms maximum chronological spacing
  and the previous input is rechecked against the 550ms age budget.
- The new `VisionPipelineTiming` records queue, preprocessing, ONNX,
  postprocessing and A500S **H264 receipt-to-result** age on one monotonic
  clock, dropping impossible-clock or too-old outcomes.
- **Receipt-to-result latency is NOT camera exposure-to-screen latency.**
  The owner-reported ~3s visual delay versus ~1s official 70mai 4.5.0
  remains unresolved, as do actual camera model weights and calibration.
- JVM concurrency tests simulate mid-model invalidation, >550ms slow
  inference and one-slot latest-frame overwrite; Lite and AI builds
  remain separate. See `docs/RAW_LINEAGE_TIMING_V1.md`.
- The mandatory >=90% pre-device gate is not met. No fresh phone trial
  should be requested yet; never derive trusted lead metres from raw data.

## V1.3.0 — experimental Direct freshness guard and decoder output timing

- Direct MediaCodec now offers an **opt-in freshness guard** preserving
  the existing SMOOTH selection as default. It bounds compressed H264
  queue length (12 AU), offer wait (80ms), max queued age (450ms), codec
  input wait (350ms), and requests a vendor low-latency hint with
  safe fallback. Smooth continues to use 48 AU, 900ms queue wait and
  non-low-latency codec, with a 1.8s fail-fast queue-age protection.
- Overload or stale compressed input **reconnects** the stream;
  it never silently drops arbitrary H264 P-frames. A decoded image
  older than 850ms can be suppressed from Surface in experimental mode
  without corrupting inter-frame reference decoding. Four consecutive
  late decoded images trigger a clean reconnect.
- A bounded `PreviewDecodeTimeline` correlates input H264 RTP-derived
  PTS with MediaCodec output PTS and reports local receipt-to-decode
  callback age (last, P95, matched/unmatched) in the main UI. These
  measurements **are not glass-to-glass latency**, which remains
  owner-observed ~3s vs ~1s in the official 70mai player.
- Host tests cover default/opt-in queue safeguards, codec-output
  freshness, PTS alignment, window P95, invalid clocks and bounded
  memory. Read `docs/DIRECT_LATENCY_GUARD_V1.md`.
- This is a controlled experiment for a later approved A500S trial,
  not evidence that 70mai app performance has been matched. The
  pre-device 90% acceptance gate remains NOT PASS.

## V1.3.1 — RTP timestamp clock and MediaCodec Surface event tracing

- New pure `RtpAuClockAudit`: unwraps 32-bit RTP 90kHz access-unit
  timestamps into per-session microsecond PTS, tracks arrival-vs-source
  pacing/drift, rejects source clock regression/jumps, reports P95
  absolute cadence error and wrap count.
- Direct MediaCodec uses the per-session normalized timestamps instead
  of raw 32-bit time offsets. On irrecoverable RTP clock discontinuity
  it reconnects rather than silently presenting a false timeline.
- New bounded `SurfaceFrameTimeline` pairs
  `releaseOutputBuffer(index,true)` timestamps with
  `MediaCodec.OnFrameRenderedListener` callbacks by PTS, reporting
  last/P95 release-to-render callback duration, unmatched PTS, and
  invalid event clocks. Vendor callbacks may be absent.
- UI now shows RTP clock frame step, packet-arrival cadence,
  cumulative session pacing drift and real callback matches separately
  from existing H264 receipt-to-decode timing. Neither measurement
  equals physical camera exposure-to-screen latency.
- Pure Kotlin/JVM tests exercise 32-bit wrap, reverse B-frame steps,
  RTP jump failures, pacing drift, output-render PTS matching,
  out-of-order callbacks, bad timestamps and bounded buffers.
- The reported TTX ~3s vs 70mai official ~1s glass-to-glass delay
  remains **UNRESOLVED** until a real same-scene, same-handset
  measurement. No new A500S device trial requested yet. See
  `docs/RTP_CLOCK_SURFACE_RENDER_V1.md`.

## V1.3.2 — Detecting accumulated video lag even at healthy output FPS

- Pure JVM `StreamCadenceAudit` correlates **five** local video events:
  accepted H264 AU, codec input, decoded output, released Surface buffer
  and confirmed MediaCodec render callback. The Direct player and its
  UI expose rolling 10-second source/codec/Surface FPS estimates.
- Diagnostic classifications distinguish local source pauses, a
  difference between RTP media-clock cadence and AU-arrival rate,
  lagging codec input, decoding, Surface release, and missing vendor
  render-event callbacks (which do not prove display failure).
- Critically, reports **relative media-PTS backlog** from the most
  recently received AU to decoded or released frames. An internal
  3-second PTS gap can be detected even when decoder output FPS
  matches the source FPS. This is *not* physical glass-to-glass latency.
- Bounded 2,048-event-per-stage ring histories, clock validation and
  pure deterministic regression tests include steady/slow stages,
  3s delayed-frame scenarios and ~20 minutes of synthetic input.
- No changes to RTSP playback mode defaults, H264 frame-dropping,
  model coordinates, ADAS warnings or camera authentication. See
  `docs/VIDEO_CADENCE_BACKLOG_V1.md`.
- The owner's prior ~3s TTX vs ~1s official 70mai visual delay is
  still **unresolved**, and the >=90% pre-device criterion is NOT met.

## V1.3.3 — Detect actual pipeline freezes and avoid codec overlap during RTSP recovery

- Pure-JVM `DirectStreamHealth` watches received RTP, completed
  H264 AU, MediaCodec decoded output and successful Surface buffer
  release; classifies RTP silence, RTP-without-AU freeze, decoder
  stalls, missing Surface output and PTS permanently stuck behind
  the highest observed source timestamp.
- Single H264 B-frame PTS regression/duplicate is counted for
  diagnostics, not blindly dropped. A nonadvancing source PTS
  lasting >3 seconds and at least 8 AUs triggers a fresh RTSP
  session rather than pretending the video is live.
- `DirectReconnectBudget` replaces the flawed original 60-second
  **total uptime** retry reset with a bounded 60-second **rolling
  failure window**. Up to three retries are allowed; a fourth
  failure inside that window stops Direct. Long uninterrupted uptime
  no longer clears recent reconnect attempts.
- Direct recovery rejects duplicate/old session callbacks and waits
  on a background thread for the old MediaCodec release latch,
  up to five seconds, before opening the next RTSP/decoder session.
  No codec release wait blocks the Android UI/preview threads.
- Main screen reports RTP/AU/decoder/Surface silence ages, duplicate
  and reversed PTS counters and whether recovery is active.
- Deterministic JVM tests exercise healthy live flow, each frozen
  stage, repeated PTS, legitimate B-frame reordering, 20-minute
  synthetic playback and prolonged reconnect rates.
- IJK defaults and Direct SMOOTH buffering remain unchanged. No
  true A500S glass-to-glass test, legitimate model or real-world
  xADAS parity is inferred. See
  `docs/RTSP_VIDEO_FREEZE_RECOVERY_V1.md`.
- The mandatory >=90% engineering gate is **still NOT met**.

## V1.3.4 — Offline YUV420 pipeline replay + guarded ONNX model install

- New camera settings entry **CHẨN ĐOÁN VIDEO YUV OFFLINE** opens a
  local Android Storage Access Framework picker for a user-provided
  TTXYUV01 engineering fixture. Supports 2–8 planar 640×480 frames,
  per-frame SHA-256 checks, strict monotonic 1–500ms receipt stamps,
  fixed caps and rejection of trailing/foreign bytes.
- Replays the existing stride-aware YUV420 converter, live A500S
  temporal bridge, both experimental virtual-camera warps and
  validated FLOAT32 [1,12,128,256] preprocessing. Returns first
  pair's deterministic SHA-256 tensor fingerprints and pair counts.
  It does **not** run ONNX, prove actual camera footage or physical
  camera-to-glass latency, or unlock ADAS.
- ONNX import now requires explicit user affirmation of model-use
  rights and **verifies a staged local file with SHA-256 and real
  flavor-specific graph inspection BEFORE replacement**. On invalid
  graph, size, missing ONNX runtime or failed atomic move, the
  prior valid model remains intact. Lite still has no ONNX runtime.
- Added synthetic host tests for malformed YUV, temporal replay,
  deterministic tensor checksums, ONNX install atomicity, false
  graph approval, missing user rights and old-model preservation.
- Documents: `docs/OFFLINE_YUV_MODEL_IMPORT_V1.md`.
  **Engineering acceptance: 7/20 = 35% PASS CI** until these
  tests and packaging pass, and remains 35% afterward because no
  new mandatory real-world/ONNX-ground-truth gate is proven. This
  is not an xADAS feature parity percentage.

## V1.3.5 — Optional real ONNX parity after offline YUV replay

- Camera settings → **Chẩn đoán video YUV Offline** now has a second,
  optional **TTXREF01** picker after successful TTXYUV01 inspection.
  Compares model SHA-256, exact first-pair `img` and `big_img` FLOAT32
  tensor values generated by the existing live-processing path, then
  actual 1,576-element ONNX inference output versus the owner-supplied
  reference. No engine opens if the hash or *either* input is wrong.
- Uses real ONNX Runtime in the AI flavor when an authorized graph
  exists. Lite fails closed without a model engine; all inference
  occurs on a background thread and no result can reach safety HUD.
- New `OfflineModelParityAudit` reports specific failure categories:
  missing model, model hash mismatch, first mismatched input, absent
  runtime or model-output mismatch. A matched self-supplied reference
  is **NOT independent ground truth** or proof of A500S compatibility.
- `RtspSourcePolicy` makes custom RTSP source/gateway input reject
  non-RTSP schemes, credentials embedded in URLs, control characters,
  invalid ports, malformed IPv4, loopback and link-local targets.
  Unsupported vendor profiles remain unsupported.
- Host tests cover each fail-closed parity branch via generated YUV
  and fake inference engines, and valid/invalid RTSP camera endpoints.
  See `docs/OFFLINE_ONNX_PARITY_V1.md`.
- Predevice acceptance remains **7/20 = 35% PASS CI**; this is NOT
  a percentage of xADAS code/features cloned and real model, lane,
  lead and ~1-second glass-to-glass evidence remain unresolved.

## V1.3.6 — Evidence files bound to a canonical SHA-256 manifest

- New locally selected `TTXEVD01` eight-line ASCII manifest,
  available as the **third** step in Camera source → Offline YUV audit,
  after the user chooses TTXYUV01 and TTXREF01 files.
- Manifest binds the **full SHA-256** of the exact user-supplied YUV
  fixture, the 1,576-output ONNX reference file, and the app-private
  model; the reference's own embedded model hash must match too.
- Strict max 2KiB canonical manifest syntax, validated ISO UTC seconds,
  limited source/instrument/permission identifiers and bounded
  streaming digest reads; fail-closed on any file substitution,
  missing model, tampered hash, malformed fields or extraneous content.
- No recording, uploading or playback changes. `MATCHED_SELF_DECLARED`
  is only matching file integrity, **never proof of original camera
  capture, independent truth, legal rights, cryptographic signer
  identity, lane/lead accuracy or ADAS readiness**.
- Pure JVM tests include corruption and wrong-file cases, invalid
  metadata, missing model, forged reference model hash and size bounds.
  See `docs/EVIDENCE_BUNDLE_LINEAGE_V1.md`.
- Pre-device verified engineering progress remains **7/20 = 35%**;
  the user requires 18/20=90% plus real hardware/model prerequisites.


## V1.3.7 — Live A500S four-frame YUV export

Owner explicitly enables Direct YUV and captures four frames to a locally selected TTXYUV01 file (Android Create Document). Uses offscreen-decoder copies, never touching preview Surface or RTP work. No model is required. Disconnects, format errors and temporal gaps cancel an incomplete capture. The file does NOT validate provenance, model rights, camera geometry or ADAS; see docs/LIVE_YUV_EXPORT_V1.md. Acceptance remains **7/20 = 35%**.


## V1.3.8 — Direct RTP/H264 access-unit integrity

Extracted RFC 6184 depacketizer with strict FU-A start/end, STAP-A length, RTP header/padding bounds, cross-timestamp packet-loss quarantine and IDR reacquisition. Unit tests cover damaged/missing RTP fragments and recovery. See docs/RTP_H264_AU_INTEGRITY_V1.md. No claimed ~1s glass delay or calibrated AI; 7/20=35% PASS CI.


## V1.3.9 — RTP source epochs and deterministic H264 replay

The Direct RTP/H264 builder and UDP jitter window now isolate SSRC/payload-type stream changes, reject FU-A NAL mismatch/forbidden bits, and wait for a complete IDR after corruption. A synthetic long-running RTP replay tests loss/reorder/stream-switch recovery; see docs/RTP_STREAM_EPOCH_REPLAY_V1.md. This does not demonstrate the ~1s optical video delay or calibrated ADAS; acceptance remains 7/20=35%.


## V1.3.10 — Bounded UDP jitter deadline and retired-epoch quarantine

- RTP socket now wakes at 25ms instead of 100ms when idle, below the 55ms
  reorder target; real scheduling and camera latency are still unknown.
- An expired UDP reorder window releases all independently expired gaps
  during the same tick rather than withholding later expired packets.
- On SSRC or payload-type changes, the queue clears pending old packets and
  temporarily quarantines late packets from up to four retired identities,
  avoiding oscillation between old/new RTP streams. The guard is bounded to
  256 packet arrivals and does not block a legitimate later source return.
- Five pure JVM tests cover source fallback, late packets, payload changes,
  sequence wrap and multi-gap idle expiry. Preview/MediaCodec/pairing remain
  unchanged. See docs/UDP_JITTER_EPOCH_GUARD_V1.md.
- **7/20 = 35% PASS CI**; there is no measured camera-to-glass improvement
  or new A500S hardware, calibration, model or ADAS evidence.


## V1.3.11 — Preview H264 GOP epoch fence

The Direct RTP parser already discards incomplete H264 AUs and the
offscreen YUV/AI decoder resynchronizes on IDR. The Surface MediaCodec
preview may still contain reference P pictures from the old GOP in native
output/input buffers. Once *any* AU has been queued for preview, the first
damaged RTP AU now terminates the entire old RTSP/preview decoder session
and requires a new native decoder after async release completes. A damaged
AU before any preview input simply waits for a valid IDR.

This fail-closed fence invalidates pending RAW inference and bounded YUV
capture evidence, and adds a visible diagnostic counter for GOP-specific
reconnects. It NEVER attempts MediaCodec.flush on the RTP/codec callback
thread or drops arbitrary H264 P frames. The existing rolling reconnect
limit prevents unbounded retries. See docs/PREVIEW_GOP_EPOCH_FENCE_V1.md.
This is NOT a measured one-second A500S preview or verified lane/lead ADAS.
CI engineering gate stays 7/20=35%.


## V1.3.12 — Reproducible ONNX model parity receipt

The model screen produces TTXONNX-AUDIT-01 text-only receipt with exact model SHA256, canonical big-endian raw IEEE754 Float32 hashes for both input tensors, reference output and actual AI runtime output, plus numerical discrepancy metrics. Owner can share only text hashes without sharing weights or camera images. Numeric match is classified MATCHED_UNVERIFIED, pending independent reference source and permitted model evidence. Four synthetic JVM tests cover endianness, tamper and wrong-model/NaN rejection. Criterion #11 and mandatory #14–17/#20 remain NOT PASS. Gate 7/20=35%.


## V1.3.13 — Monotonic GPS freshness and PiP rejection fix

Android Location callbacks may deliver cached GPS fixes that are much older
than callback receipt time. GpsSpeedTrustGate now uses the original
Location.elapsedRealtimeNanos and the Android monotonic boot clock, rejects
outdated/impossible/poor-speed-accuracy measurements, ignores older or
duplicate fixes, invalidates the displayed speed when a newer fix has no
usable speed, and expires every displayed value from FIX age (not listener
age). Provider/permission loss resets the signal. This never feeds
uncalibrated ADAS alerts.

A PiP entry that returns false (without throwing) now clears its transition
flag so Surface destruction does not inherit a stuck PiP state. Settings
describes PiP as an experiment rather than an unimplemented feature.

11 pure JVM regression tests validate the GPS gate. Device GPS, Android
PiP lifecycle and actual 70mai video remain unverified. Criterion #19 is
not promoted solely from synthetic tests. Engineering gate 7/20=35%.


## V1.3.14 – Direct RTSP callback epoch isolation

Every Direct player now has a separate atomic callback lease. Automatic
reconnect revokes old leases before native decoder teardown, and the owner
checks the lease for YUV/ONNX, status, discontinuity and delayed SAF callbacks.
Activity generation previously remained unchanged across internal reconnects.
Seven JVM tests include 20,000 session changes; hardware/model parity is
not claimed and the engineering gate remains **7/20=35%**.


## V1.3.15 — Correct MediaCodec YUV420 odd-crop chroma phase

An odd YUV crop origin must shift chroma lookup: pixel col uses (col + left%2)/2, not col/2. The Android Image reader also used ByteBuffer.clear() which expanded the native plane limit to capacity. Both are corrected using explicit chroma phase and a limit-preserving, Long-safe plane snapshot helper. Nine JVM tests validate odd/even crop phase, padded interleaved UV and malformed/truncated buffers. No A500S or physical AI verification; acceptance 7/20=35%.


## V1.3.16 — Offscreen H264 decoder freshness and IDR reacquisition

The optional A500S YUV decoder used to feed already expired compressed
access units to MediaCodec, despite discarding their eventual YUV output
when more than 550ms old. Now the offscreen producer checks the queued
head age and rejects stale/invalid AUs, and the worker rechecks immediately
before native input submission. Any expired dependent P-frame resets the
WHOLE secondary GOP and reacquires a fresh IDR, without affecting the
primary Surface preview. Two counters identify stale compressed AUs and
expired backlog resets; nine pure JVM tests cover recovery and freshness.

No hardware/optical latency or licensed ONNX parity is asserted.
Formal predevice gate remains **7/20=35%**.


## V1.3.18 — Camera2 / RAW ONNX session ownership

Closes asynchronous Camera2 and ONNX lifecycle gaps: a model-opening
worker must not resurrect RAW inference after Activity pause, Surface
loss, session replacement or explicit cancellation. Native CameraDevice,
ImageReader and capture-session callbacks are verified again on the UI
thread using independent revocable camera/model leases. Invalid or late
callbacks cannot change active status. 640x480 YUV output is requested
only if advertised by Camera2 and actual crops are rejected when they
do not match the tensor source contract. Pausing no longer joins the
Camera2 HandlerThread on the UI thread. Nine pure JVM tests verify lease
revocation across 20,000 synthetic transitions. This remains CODED in
Gate A item #8, not a claim of hardware verification; A=7/20, B=0/5.


## V1.3.19 — Actual CPU ONNX contract fixture, independent arithmetic oracle

CI now generates an ORIGINAL arithmetic-only ONNX model with exact two FLOAT32
[1,12,128,256] inputs and [1,1576] output, executes it with actual
ONNX Runtime CPU and independently calculates all 1,576 expected outputs.
The exported ONNX/TTXREF01/manifest artifact supports an optional Android AI
smoke test without copying proprietary xADAS weights or including any model
in the APK. It is NOT a trained driving model, optical calibration or metric
lead reference. See docs/ONNX_CPU_CONTRACT_FIXTURE_V1319.md.
Gate A remains 7/20=35% and mandatory Gate B remains 0/5.


## V1.3.20 — Camera2 sensor timestamp correctness for RAW ONNX

Phone Camera2 YUV input previously stamped frames at callback receipt,
which could hide old ImageReader frames as fresh. The diagnostic branch now
uses `Image.timestamp` ONLY if the selected camera advertises a REALTIME
sensor timebase. Unknown timebase disables RAW ONNX, while preview remains
available. REALTIME frames older than 550 ms, duplicated or future-dated
cannot reach the temporal inference pipeline. Any invalid frame breaks the
pending pair; accepted PairSample uses sensor capture time, not delivery time.
Ten host regression tests cover clock domains and delayed callbacks.
See [Camera2 timestamp policy](docs/CAMERA2_SENSOR_TIMESTAMP_V1320.md).
Criterion #8 still requires independent on-device Camera2 verification.
Gate A **7/20 (35%)**, Gate B **0/5**.


## V1.3.21 — Export 5-minute stream continuity evidence from Android

The new **THU BIÊN BẢN VIDEO 5 PHÚT** button samples Direct Surface-release
counts or native IJK output/playing state every second. A gap over 2.5s,
local frame stall, reconnect, counter regression or early stop invalidates
continuity. All test runs (including failed ones) can be exported as
bounded UTF-8 `TTXLIVE01` traces via Storage Access Framework.

Local progress is **not optical camera-to-glass latency**. A real A500S +
external simultaneous scene/display recording and independent review remain
mandatory for #20. See
[5-minute/optical protocol](docs/FIVE_MINUTE_OPTICAL_ACCEPTANCE_V1321.md).
Formal progress: **Gate A 7/20 (35%), Gate B 0/5**.


## V1.3.22 — GPS speed unit and label-setting correctness

Changing `km/h · m` to `mph · ft` in settings now updates the live GPS HUD
speed when the app resumes; any separately verified lead distance uses
matching feet/metres without modifying ADAS model geometry. Turning off
`Hiển thị nhãn TTX` hides **only** the brand label, not GPS or calibrated
lead data. The `round distance to 5m` setting is also displayed correctly.
14 JVM regression tests cover unit conversion, invalid values and logo
isolation. See [HUD unit audit](docs/HUD_UNITS_AND_LABEL_V1322.md).
Gate A **7/20**, field Gate B **0/5**; hardware GPS/PiP still unverified.


## V1.3.23 — Android GPS/PiP phone evidence collection

The main screen now offers **THU KIỂM TRA GPS / PiP / CÀI ĐẶT**.
When started by the owner, TTXGPIP01 records non-identifying state:
GNSS permission/provider/fresh-speed presence, selected playback, Surface,
PiP entry/exit platform callbacks, request rejection, and saved HUD units.
The trace can be exported through Android's document picker. No GPS
coordinates, real numeric speed, camera footage, camera URL or passwords
are stored. A request returning true never counts as confirmed PiP entry.

See [on-device GPS/PiP acceptance](docs/GPS_PIP_ONDEVICE_EVIDENCE_V1323.md).
The trace itself is not independent Android verification; #19 remains CODED,
**Gate A 7/20 (35%), Gate B 0/5** pending a phone test and review.


## V1.3.24 — Close YUV→ARGB→ONNX stale admission gap

The A500S RAW temporal bridge now rechecks image receipt age **after**
the 640×480 YUV→ARGB CPU copy, refuses a pair if that copy makes its
current image expire, and starts a new pair if only the previous
image expires mid-conversion. Rejected ONNX sink submissions also
reset the temporal input rather than reusing discarded frames.

The ONNX worker's single-slot producer now rejects already expired
H264 receipt timestamps **before** queue admission and counter
increments, in addition to its existing in-worker checks. Nine
new JVM regressions cover stalled copies, exact 550ms boundary,
clock faults, previous-frame expiry and sink backpressure.

See [YUV/ONNX freshness audit](docs/YUV_ONNX_INGRESS_FRESHNESS_V1324.md).
This is not A500S hardware or licensed ONNX model parity proof;
**Gate A 7/20 (35%), Gate B 0/5** remain unchanged.


## V1.3.25 — Export Android YUV→ONNX reference and independently replay on CPU

The offline YUV audit screen now includes the owner-driven TTXREF01 export.
It replays captured TTXYUV01 through the actual dual virtual warp and
12-channel tensor packer, executes the installed authorized model with
Android ONNX Runtime, then serializes the exact input tensors, 1,576
output elements and model SHA256 into a canonical 3,152,072-byte TTXREF01.
The new tools/verify_device_onnx_reference.py recomputes that output
using independent CPU ONNX Runtime. CI rejects deliberately corrupted
model hashes and mismatched output references. Device-generated tensors
may expose private scene information and must not be uploaded publicly.

See docs/ANDROID_YUV_ONNX_CPU_PARITY_V1325.md. Runtime parity is not
independent ADAS ground truth or camera calibration.
Gate A **7/20 (35%)**, Gate B **0/5**.


## V1.3.26 — Fix incorrect 1280×720 A500S decoder initialization

The Direct Surface MediaCodec and optional offscreen YUV MediaCodec now
read the H264 picture size from SPS in the camera's RTSP SDP. The previous
hardcoded 1280×720 configuration mismatched the known 640×480 A500S
stream. A bounded pure-Kotlin parser handles H264 baseline/high profile,
chroma-dependent crop, interlaced size and invalid/truncated NALs.
Both decoders use the same parsed dimensions, with explicit 640×480
fallback when SDP SPS is unavailable or malformed. Eleven deterministic
JVM regressions cover SPS parsing and failure cases.

See docs/H264_SPS_CODEC_DIMENSIONS_V1326.md. A configuration correction
is not measured A500S latency improvement or physical camera proof.
Gate A 7/20 (35%), Gate B 0/5.
