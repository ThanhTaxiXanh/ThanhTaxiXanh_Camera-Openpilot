# TTX Camera AI — twenty-item historical acceptance register

Updated: 2026-11-05. V1.3.17 introduces **two independent acceptance gates** defined by docs/TWO_GATE_ACCEPTANCE.md and accounted by docs/acceptance/two_gate_ledger.json. The history below retains the original 20-item statuses and previous implementation notes. Gate A is engineering-readiness 7/20 (35%); Gate B is mandatory real-device/independent verification 0/5. **Final 90% requires BOTH Gate A >=18/20 and Gate B 5/5.** Neither a new version nor GitHub Actions green promotes real-world criteria.

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
| 8 | Phone Camera2 preview plus YUV420 source | CODED; hardware unverified |
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

Legacy register baseline: **7/20 = 35% Gate A engineering evidence**. Gate A target >=18/20; separate Gate B real A500S/ground-truth validation is **0/5** for mandatory #14–17/#20. A green build alone does not advance either gate. The old single-pass terminology in earlier version notes is historical, not a claim of physical completion. Official final verdict: **BOTH GATES BELOW TARGET**.

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
