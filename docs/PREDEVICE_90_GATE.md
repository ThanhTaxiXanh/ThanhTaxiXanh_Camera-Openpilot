# TTX Camera AI — acceptance criteria before the next phone test

Updated: 2026-11-04. V1.2.9. Goal from owner: build until >=90% functionality before a new on-device A500S trial. Compilation and synthetic unit tests do not equal xADAS functional equivalence.

## 90% engineering gate

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
| 20 | >5 minute uninterrupted stream and near-1s glass-to-glass latency | NOT READY |

Verdict: BELOW 90%. Do not ask the owner for a new on-device test yet. Only PASS CI items have offline evidence, CODED items need deeper validation, NOT READY items are incomplete.

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
