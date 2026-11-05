# TTX Camera AI — twenty-item historical acceptance register

Updated: 2026-11-05. V1.3.17 introduces **two independent acceptance gates** defined by docs/TWO_GATE_ACCEPTANCE.md and accounted by docs/acceptance/two_gate_ledger.json. The history below retains the original 20-item statuses and previous implementation notes. Gate A is engineering-readiness 8/20 (40%) after independent review of real phone Camera2 evidence for #8; Gate B is mandatory A500S/ADAS field verification 0/5. **Final 90% requires BOTH Gate A >=18/20 and Gate B 5/5.** Neither a new version nor GitHub Actions green promotes real-world criteria.

## Historical twenty-item engineering register

Twenty criteria must be independently implemented and demonstrated with unit or offline integration tests. At least 18 must pass; mandatory live-video-to-AI, model-coordinate validation, calibrated lead results and functional overlays cannot be waived. Hardware performance validation is an additional later gate.

| # | Requirement | Status |
|---|---|---|
| 1 | Stable package and CI build | PASS CI |
| 2 | Lite and AI ARM64 variants with native packaging checks | PASS CI |
| 3 | 70mai A500S RTSP playback and fallback | CODED; earlier owner video confirms RTSP |
| 4 | Owner-approved 70mai CGI pairing and heartbeat | CODED; earlier 7/7 CGI OK |
| 5 | RTP/RTCP, loss and jitter diagnostics | PASS CI |
| 6 | Real 70mai/Vietmap/AZCAM vendor adapter parity | NOT READY |
| 7 | USB/UVC live video adapter | NOT READY |
| 8 | Phone Camera2 preview plus YUV420 source | **PASS**; reviewed owner Android 60.12s Camera2 trace, four SHA-256-valid YUV 640x480 frames and changing screen preview; see docs/acceptance/PHONE_CAMERA2_REAL_DEVICE_REVIEW_20261105.md |
| 9 | Stride-aware YUV420 frame converter | PASS CI |
| 10 | Temporal 12-channel YUV preprocessing and virtual warp | PASS CI; camera warp unverified |
| 11 | ONNX Runtime graph loading and worker | CODED; float32 shape/type gate and optional external reference comparison, but no authorized genuine reference yet |
| 12 | NCNN alternate engine | NOT READY |
| 13 | xADAS 1576-output lane/lead/road-edge parser | PASS CI |
| 14 | Non-disruptive A500S decoded YUV to AI (mandatory) | CODED RAW DIAGNOSTIC: sidecar YUV→temporal pair→optional ONNX, now with epoch/continuity guards and synthetic end-to-end tests; no actual model and physical device evidence. NOT PASS |
| 15 | Verified A500S intrinsics, model axes and warp (mandatory) | CODED camera pinhole and Brown-Conrady distortion audit for user supplied marker files; no measured A500S coefficients, optical validation or model-axis parity. NOT PASS |
| 16 | Licensed model with ground-truth inference results (mandatory) | NOT READY |
| 17 | Validated live lane and metric lead HUD (mandatory) | NOT READY |
| 18 | Passive headway/TTC decision logic and fail-closed unit tests | PASS CI |
| 19 | GPS, PiP and settings | CODED; device unverified |
| 20 | >5 minute uninterrupted stream and near-1s glass-to-glass latency | Experimental Direct AU freshness guard, codec-output age, P95 diagnostics and clean reconnect; NO real exposure-to-glass measurement. NOT PASS |

Historical pre-device register baseline: **7/20 = 35% Gate A engineering evidence**, superseded for #8 by verified owner Camera2 evidence on 2026-11-05. Current **8/20 = 40%**. Gate A target >=18/20; separate Gate B real A500S/ground-truth validation is **0/5** for mandatory #14–17/#20. A green build alone does not advance either gate. The old single-pass terminology in earlier version notes is historical, not a claim of physical completion. Official final verdict: **BOTH GATES BELOW TARGET**.

## Priority development remaining

1. V1.2.2 introduces a nonblocking H264/YUV sidecar to the Direct receiver, sharing its compressed access units and preserving its Surface. Validate the offscreen codec on real hardware; then provide an independent way to capture the IJK stream without disrupting its preview. Neither variant is verified for end-to-end live AI yet.
2. Secure a compatible model with explicit redistribution rights and build golden offline model-input/output fixtures.
3. Match camera warp, normalized YUV channels, model coordinate convention, pitch/yaw/intrinsics and metric lead geometry against reference data.
4. Connect validated model results to fail-closed HUD and passive warnings. Never display a synthetic lead or stale lane.
5. Develop USB/UVC and real multi-vendor camera protocol adapters; test against recorded non-mutating protocol fixtures.
6. Add protocol and decoder timing simulations to CI; then re-evaluate the 18/20 gate before requesting an owner phone test.

Privacy: no recording or model upload, no proprietary xADAS weights, no camera-auth bypass, no steering or braking.

V1.2.2 offline evidence: H264 Annex-B/IDR tests + both flavor build checks; no YUV from A500S hardware has been captured in this milestone. A successful CI run verifies compilation only, not camera throughput, additional decoder capacity, frame timestamps, or actual model safety.

V1.2.3 validation note: Offline unit testing covers timestamp, frame dimensions,
pair reset and fail-closed behavior, and CI can verify both APK flavors compile.
A second AVC decoder may be unsupported by the handset; calibration, valid
model artifacts, observed confidence, lead geometry and true source timestamp
remain unknown. Raw tensor output must NEVER count as ADAS acceptance.


V1.2.4 evidence: additional JVM regression cases and CI compile/packaging
for offscreen PTS uniqueness, H264 IDR recovery, decoder epoch isolation,
nonblocking signal for temporal reset and synthetic reference-YUV ordering.
This makes Direct YUV handling more fail-closed but does NOT change #14 to PASS:
no genuine A500S image/ONNX golden results or live hardware evidence. The
synthetic tensor fixture is expressly not a license or parity proof.


V1.2.5 evidence: CPU/JVM tests now enforce homography immutability,
invertibility, geometric coverage, studio-YUV channel ranges, fixed reference
fixture parsing and 1,576-value numeric comparison. The ONNX AI UI can compare
a separately authorized reference linked to the model SHA-256. No genuine
licensed reference or physical A500S calibration data is bundled, so criteria
#11, #14–17 remain incomplete. These checks cannot authorize ADAS output.


V1.2.6 host-test evidence: synthetic pinhole reprojection, CSV evidence
validation and contiguous-only projected lane segments. An imported candidate
calibration is always marked unverified and no UI action can promote it to
ready. The geometric consistency report is not real model ground truth; the
90% gate remains below threshold. No owner on-device trial requested.

V1.2.7 host-test evidence: candidate Brown-Conrady optics (fx/fy/cx/cy,
k1/k2/k3,p1/p2), TTXCAL02 distortion marker comparisons and a separate
TTXLEAD01 CSV range discrepancy audit. Lead MAE/RMSE/P95/MAPE/bias cannot
be accepted as ground truth unless input source/timestamp/metric model
conversion have independent verification. The app does not convert raw
lead outputs into metres, does not auto-validate model coordinates,
and does not change item #15/#16/#17 to PASS.

V1.2.8 offline evidence: synthetic 3D model-lateral/vertical sign-hypothesis
audits using a bounded TTXAXIS01 file and monotonic paired-range/maximum
skew validation from TTXLEAD02. Both protocols accept user-declared
source/model metadata and cannot validate its provenance. The tests do
NOT validate real model axes, synchronized exposure timestamps or physical
lead distance and do NOT advance mandatory #14-#17 to PASS. Real device
acceptance and the owner's >=90% threshold remain outstanding.


V1.2.9 offline evidence: synthetic end-to-end A500S YUV→ARGB→temporal pair→
model inputs→fake inference→1,576-head parser, plus nonblocking inference
GOP epochs, CPU queue replacement, previous-frame freshness and receipt
clock timing tests. No measured A500S exposure timestamp or actual camera
model output is available. The earlier ~3s glass-to-glass observation and
camera intrinsics remain unresolved, so #14–17 and #20 are NOT PASS.

V1.3.0 synthetic CI: a deliberately opt-in Direct MediaCodec mode bounds
H264 queued AU residence and codec-input wait, suppresses only stale
already-decoded Surface outputs, and reconnects on sustained backlog.
A bounded PTS correlator reports H264 access-unit local receipt-to-output
callback age, which EXCLUDES real camera exposure/encoder and Surface
scanout latency. Existing 3s vs official 70mai 1s owner observation is
UNRESOLVED, so mandatory acceptance #20 does not advance to PASS.

V1.3.1 CI: source RTP 90kHz-to-local-receipt pacing, timestamp wrap,
decoder-to-render callback duration and bounded PTS correlations.
This only separates pipeline latency mechanisms; it cannot measure
true camera exposure, official 70mai transport latency, SurfaceFlinger
scanout, live ONNX geometry, or real speedup. Criterion #20 remains
NOT PASS; the owner >=90% gate is unchanged.

V1.3.2 CI evidence: 10-second rolling, five-stage local AU→input→decoded
→Surface-release→render-callback throughput and PTS-media-gap diagnostics,
with synthetic prolonged slowdown/3s backlog and a 20-minute fake stream.
A renderer may report healthy local FPS while optical camera-to-glass
lag remains high. A missing native render callback is not proof of
a display stall. No real A500S timestamps or exposure-ground truth
are introduced; #14–17 and #20 remain NOT PASS and do not advance
the 18/20 owner gate.

V1.3.3 offline CI: a bounded per-session H264 freeze watchdog now
separates RTP silence from RTP-without-complete-AU, decoded-output
and Surface-release stalls. A persistent 3s+ unchanging PTS with
8+ AU arrivals causes controlled reconnect, whereas isolated
B-frame reversals are only counted. Reconnects are limited to 3
per actual 60-second rolling window, with an async old-MediaCodec
release completion latch before any new decoder starts. Synthetic
20-minute host tests are not hardware stream uptime proof.
No owner camera trial, exposure-to-display measurement, licensed
model, validated lane/lead outputs or 18/20 PASS gate claim is made.

V1.3.4 offline evidence: bounded, SHA-256 checked TTXYUV01 test frames
can replay the production YUV→temporal-pair→dual-warp→FLOAT32 tensor
preprocessing without opening camera/ONNX or enabling alerts. User
declares model-use rights before import; an actual AI-flavor graph
check and atomic same-directory replacement protect a previously
working ONNX file from invalid imports. Neither self-provided frame
hashes nor a valid graph prove video from A500S, legal model provenance,
real-world lane/lead accuracy, or real hardware latency. No acceptance
criterion is promoted to PASS solely from these offline features:
**7/20 = 35% verified, 11 more independently evidenced PASS criteria
needed for 90%, including the mandatory model/camera gates**.

V1.3.5 offline evidence: optional TTXYUV01→TTXREF01 three-way audit
compares exact owner-imported ONNX SHA, BOTH actual production
preprocessing tensor inputs and genuine AI-flavor ONNX output against
the supplied 1,576-dimensional reference. Fail-closed on model hash,
either input, missing engine or output mismatch. The unit tests use
synthetic reference values and FAKE engines: these neither license
third-party weights nor demonstrate physical model truth. RTSP custom
source/gateway validation gains malformed URI/IP guardrails but
does not implement missing vendor protocols. No PASS promotion:
**7/20 = 35% verified; 11 further PASS required for >=90%**, with
mandatory #14–17 and #20 still not met.

V1.3.6 offline provenance evidence: TTXEVD01 owner-selected
eight-line ASCII manifest binds full SHA-256 of the selected
TTXYUV01 bytes, the selected TTXREF01 bytes, the installed ONNX
file and the reference's embedded model hash. The app rejects
tampering, malformed metadata, invalid UTC and missing files.
Source, instrument and permission identifiers are **self-declared**,
with no independently trusted signature, original A500S capture,
optical calibration or legal provenance evidence. Therefore
MATCHED_SELF_DECLARED **never** promotes #14–17 or #20:
**7/20 = 35% PASS CI, 11 additional verified PASS needed for 90%**.
Mandatory physical-device criteria cannot be independently proven
with synthetic tests alone, a dependency that must remain visible.


V1.3.7: bounded, owner-armed live YUV capture and SAF TTXYUV01 writer, with JVM tests for planar stride/interleaved conversion, integrity round-trip and fail-closed gaps. New data collection is not on-device validation. Criteria #14-17 and #20 remain NOT PASS; **7/20 = 35%**.


V1.3.8: independently unit-tested RTP/H264 FU-A/STAP-A reassembly guards and conservative cross-boundary packet-loss quarantine in the Direct player. This is packet integrity work, NOT measured phone-camera playback or model calibration: **7/20=35%** and mandatory #14–17/#20 remain NOT PASS.


V1.3.9: the RTP/H264 assembly path and UDP reorder window now isolate SSRC/payload-type stream epochs; inconsistent FU-A NAL identities are rejected, with deterministic synthetic replay and bytewise AU tests. No real A500S model/latency evidence. **7/20=35%**, mandatory #14–17 and #20 NOT PASS.


V1.3.10: 25ms RTP UDP socket timeout vs 55ms reorder window, multi-gap deadline drain, and bounded late-SSRC/PT retirement to avoid jitter buffer epoch flapping. CI tests alone do not establish physical playback latency, camera/AI correctness or ADAS readiness. **7/20=35%**, mandatory #14-17/#20 NOT PASS.


V1.3.11: PreviewGopEpochFence rejects any further AU in a Surface MediaCodec
session following an RTP/H264 GOP loss once a prior preview AU has been queued.
Old player closes its socket and uses the existing async decoder release latch
before recovery. RAW/ONNX epoch and YUV evidence are invalidated. Host JVM tests
do not demonstrate exact hardware frame-flush behavior, exposure delay or
calibrated lane/lead correctness: **7/20 = 35%**, mandatory #14–17/#20 NOT PASS.


V1.3.12: a canonical TTXONNX-AUDIT-01 text-only receipt binds installed model SHA256, both Float32 input tensors, and reference vs actual 1576 outputs with big-endian raw IEEE754 SHA256; the Android share sheet can export hashes without weights or camera images. Matching self-imported reference is MATCHED_UNVERIFIED and does not validate independence or permission. No #11 or mandatory gate promotion: **7/20=35%**.


V1.3.13: monotonic GPS fix-age gate replaces callback-receipt age, rejects cached/invalid/low-accuracy fixes and invalidates speed when provider or newest fix is unreliable. PiP entering false now clears transition state. Eleven pure-JVM tests do not prove Android GNSS/PiP runtime or meet criterion #19. Mandatory #14-17/#20 lack real A500S and model/geometry evidence. **7/20 = 35% PASS CI**.


V1.3.14: per-Direct player callback leases filter late YUV/ONNX/status/SAF callbacks across internal reconnects. Seven synthetic JVM tests. No hardware evidence, #14–17/#20 not PASS. **7/20=35%**.


V1.3.15: Android YUV420 image cropping now retains odd X/Y chroma phases and the native ByteBuffer valid limit. Long-safe plane-copy bounds check rejects malformed strides. Nine host tests are synthetic only, no physical A500S/ONNX or #14–17/#20 PASS. Engineering gate **7/20=35%**.


V1.3.16: compressed offscreen YUV AUs now have a 550ms local-receipt age guard at both producer admission and decoder submission. Any stale queue head causes a whole-GOP reset and fresh-IDR wait, protecting ONNX temporal continuity. Nine synthetic JVM tests cannot establish real A500S decode throughput, model accuracy or glass-to-glass latency. **7/20=35%**, mandatory #14–17/#20 NOT PASS.


V1.3.17 migration: two-gate policy and a fail-closed machine ledger separate engineering readiness (A: 7/20) from mandatory physical/independent validation (B: 0/5). CI verifies ledger format and threshold immutability; no acceptance criteria are promoted. Final 90% requires A>=18/20 AND B=5/5.


V1.3.18: phone Camera2/ONNX lifecycle now rejects stale `onOpened`, `onConfigured` and pending model load completions after Activity pause/Surface teardown. Added nine host lease tests; a real Camera2+YUV run is still missing. Two-gate totals unchanged: **A 7/20, B 0/5**.


V1.3.19: CI generates an original synthetic, arithmetic-only ONNX graph with exact two-input/1576-output shape, runs a real independent CPU ONNX Runtime inference, checks full outputs with a separately calculated formula, and exports TTXREF01 + hashes for Android AI smoke-testing. This does not demonstrate a licensed production ADAS model, independent driving ground truth or actual A500S data. #11 remains CODED; Gate A **7/20=35%**, Gate B **0/5**.


V1.3.20: Phone Camera2 RAW ONNX uses genuine Image.timestamp only when SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME shares elapsedRealtimeNanos. Unknown, stale (>550ms), duplicate and impossible sensor timestamps are excluded; invalid frames break inference pairing, while camera preview remains independent. Ten JVM regression tests do not prove Camera2 hardware. #8 stays CODED, Gate A **7/20 (35%)**, Gate B **0/5**.


V1.3.21: owner can explicitly start and SAF-export bounded TTXLIVE01 five-minute local preview progress traces for Direct/IJK. Sampling records counter/FPS, time gaps, freezes, reconnects and interruptions; reports ALWAYS mark exposure-to-glass latency NOT_MEASURED and field #20 NOT_VERIFIED until an independent optical camera+screen recording exists. Eleven pure JVM tests are synthetic only. Gate A **7/20 (35%)**; Gate B **0/5**.


V1.3.22: GPS HUD now respects persisted km/h/m vs mph/ft display units on resume. SHOW_LOGO hides only the TTX brand, never all GPS or verified lead data; 5m round-distance setting applies only to separately verified metres. Fourteen pure JVM formatting tests do not independently verify Android GNSS/PiP lifecycle. #19 remains CODED. Gate A **7/20=35%**, Gate B **0/5**.


V1.3.23: owner-controlled Android GPS/PiP/settings TTXGPIP01 trace now records permission/provider/current fix presence, PiP request/result vs real onPictureInPictureModeChanged entry/exit callback, Surface validity and persisted HUD options. Bounded privacy-safe text export through SAF; synthetic JVM tests cannot prove live GPS/PiP hardware. #19 still CODED pending independent review, Gate A **7/20 (35%)**, Gate B **0/5**.


V1.3.24: Corrected post-ARGB-copy freshness in the A500S RAW temporal bridge: reject now-expired frames, break the pair if only its previous image expired, clear rejected sink state. ONNX producer also rejects stale/future timestamps before queue admission instead of incrementing accepted counts and relying on worker drop. Nine host tests use controlled clocks and synthetic conversion delays; no actual A500S live/approved model evidence. #14 stays CODED. Gate A **7/20 (35%)**, Gate B **0/5**.


V1.3.25: Offline YUV activity now exports canonical 3,152,072-byte TTXREF01 from owner-selected TTXYUV01 + actual Android AI ONNX inference, model SHA-bound and exact to current dual warp/temporal packer. Independent CPU ONNX verifier checks graph contract, hashes and output parity; CI rejects tampered reference hash/output. Device-generated outputs are not independent ADAS ground truth; real A500S continuity, legal model, geometry and latency remain unproven. #11/#14/#16 still NOT PASS. Gate A **7/20 (35%)**, Gate B **0/5**.


V1.3.26: The Direct Surface and YUV sidecar MediaCodec decoders previously initialized at hardcoded 1280x720 despite observed A500S 640x480. Both now use bounded SDP H264 SPS-derived dimensions including valid AVC cropping, with logged 640x480 fallback. Eleven JVM tests cover baseline/high, interlace, crop and invalid SPS. The actual phone's decoder allocation and physical camera-to-glass timing have NOT been checked; #3/#14/#20 remain CODED. Gate A **7/20 (35%)**, Gate B **0/5**.


V1.3.27: RTSP SDP parsing now restricts H.264 SPS/PPS to the selected
video section and matching H264 RTP payload type, with bounded base64,
NAL and SPS dimension validation and six offline unit tests. This
prevents mismatched codec CSD from another SDP track, but **does not**
prove native A500S decoding, uninterrupted YUV, licensed model output,
calibrated lead geometry or optical latency. #3/#14/#20 remain CODED;
Gate A **7/20 (35%)**, Gate B **0/5**.


V1.3.28: Direct TCP, early interleaved and UDP video ingress now filter
foreign RTP payload types using the SDP-selected H264 PT BEFORE jitter
reordering or H264 assembly. Malformed same-stream RTP is still sent
through the loss quarantine and two new diagnostic counters are shown.
Seven synthetic JVM tests include mixed-payload UDP replay. No real A500S
optical, live YUV→model, ground-truth metric or independent hardware
measurement is added: **Gate A 7/20 (35%)**, **Gate B 0/5**; #3/#14/#20
NOT promoted and all mandatory criteria still NOT PASS.


V1.3.29: RTP reception resets per-SSRC expected/received counters before
accounting for the first new-source packet (SSRC zero is valid), and
RTCP Receiver Reports now encode the RFC 3550 signed 24-bit cumulative
loss plus 8-bit interval fraction. Report baselines advance only after
successful UDP/TCP RTCP send, not merely preparing a packet. Nine synthetic
JVM cases test source epochs, loss recovery, duplicates, interval fraction,
failed-send retry and wire limits. No new independent A500S stream/ONNX
or optical measurement: **Gate A 7/20 (35%)**, **Gate B 0/5**; all mandatory
#14–17/#20 remain NOT PASS.


V1.3.30: A500S RAW-YUV source epoch invalidation is now atomic and does
not wait for in-progress YUV→ARGB conversion. The temporal bridge checks
GOP epoch before and after copies/submission, and the ONNX worker checks
source lease both before and after inference and again at result delivery.
Five deterministic JVM tests reproduce callback/conversion and in-flight
worker races with synthetic pixels. These prevent known cross-GOP temporal
pairing races but do not validate a real camera YUV decoder, trained model,
calibration, metric lead or optical latency. Gate A **7/20 (35%)**; Gate B
**0/5**, mandatory #14–17/#20 remain NOT PASS.


V1.3.31: The independent A500S offscreen MediaCodec YUV delivery path
now rechecks active decoder/GOP epoch, 550ms receipt-age and 300ms output
cadence both before and AFTER potentially slow native Image plane copying.
Only successfully delivered YUV callbacks advance the local cadence clock.
Unsupported YUV output formats report faults and reset the sidecar chain;
three new counters distinguish stale, revoked-epoch and unsupported images.
Eight JVM tests exercise deterministic epoch change during copy, expiration,
reset, cadence and 10k virtual outputs. No camera or model was run: Gate A
**7/20 (35%)**, Gate B **0/5**. #14 and #20 still NOT PASS.


V1.3.32: Independent phone Camera2 is now directly launchable without 70mai
Wi-Fi. Explicit 60-second TTXPHONE01 session evidence measures repeating
CaptureRequest callbacks, ImageReader YUV callbacks, successfully copied
640x480 images, stalls and (when REALTIME) sensor-to-callback receipt age.
Owner-armed four-frame TTXYUV01 exports actual image pixels through Android
Storage Access Framework, separate from the RAW ONNX model. Ten JVM tests
validate recorder bounds, timebase, failures and trace output. The recorder
alone cannot prove a Surface is optically visible; independent screen review
and real phone artifacts are REQUIRED before criterion #8 can change to PASS.
This version does not provide any A500S camera, model or calibrated ADAS
physical evidence. Gate A **7/20=35%**, Gate B **0/5**; no new PASS.


V1.3.33: Physical Android Camera2 lens selection now surveys all camera
IDs for advertised YUV_420_888 640x480 instead of assuming the first
rear lens is usable. A compatible rear lens is preferred, then front/other;
within each category, SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME is preferred.
Missing support fails closed without fabricating dimensions. UNKNOWN source
clocks remain blocked from RAW ONNX but may be used for preview/YUV evidence.
Ten synthetic selection JVM tests do not prove device compatibility or
preview/YUV on any physical handset. Request owner to test V1.3.33 and
submit TTXPHONE01, TTXYUV01 and visual preview evidence for criterion #8.
Gate A **7/20 (35%)**, Gate B **0/5**; no new PASS.


2026-11-05 independent owner device review: criterion #8 Phone Camera2
preview plus native YUV420 source moved CODED -> **Gate A PASS**. The
submitted TTXPHONE01 covered 60,120ms, 1,791 Camera2 capture completions,
1,792 ImageReader YUV callbacks, 66 valid 640x480 snapshots, no clock faults,
no invalid/stale sensor frames and all sampled Surfaces valid. Original
TTXYUV01 contains four distinct, SHA-256-valid, nonblank 640x480 YUV420p
frames with 181/18/30ms receipt gaps. Owner screen video (19.79 seconds,
not the whole minute) visibly shows changing rear Camera2 preview; both
sources display a consistent scene after allowing for sensor orientation.
Reviewed SHA fingerprints, privacy boundaries and limitations are in
`docs/acceptance/PHONE_CAMERA2_REAL_DEVICE_REVIEW_20261105.md`.
This ONLY proves criterion #8 on the tested Android handset: **Gate A
8/20 (40%)**, target >=18/20; **Gate B remains 0/5**, because none of
#14/#15/#16/#17/#20 A500S/ADAS evidence was provided. Neither Camera2's
80ms max sensor-to-callback age nor the video proves optical latency.


V1.3.34: Existing TTXLIVE01 five-minute recorder now optionally captures
synchronous Direct Surface-release and independent A500S offscreen YUV
callback progress, per-session decoder error/reset deltas, and optional
RAW temporal-pair/ONNX-result counters. YUV must be explicitly enabled
before arming the trace. The recorder treats a stalled/disconnected YUV tap
as a failure even while the preview Surface counter still advances. Nine
JVM regression tests exercise healthy dual-stream signals and all key
failure paths. This is new physical-evidence COLLECTION capability, not
physical evidence or performance improvement: user must provide a real
A500S TTXLIVE01, 4-frame TTXYUV01 and preview footage. No optical clock
or legally trained driving model is added; #14 and #20 remain NOT PASS.
Gate A **8/20 (40%)** (includes independently reviewed phone Camera2 #8),
Gate B **0/5**.
