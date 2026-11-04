# TTX Camera AI — acceptance criteria before the next phone test

Updated: 2026-11-04. V1.2.5. Goal from owner: build until >=90% functionality before a new on-device A500S trial. Compilation and synthetic unit tests do not equal xADAS functional equivalence.

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
| 14 | Non-disruptive A500S decoded YUV to AI (mandatory) | CODED RAW DIAGNOSTIC in Direct: sidecar YUV -> temporal pair -> ONNX; no calibrated model and no device validation. NOT PASS |
| 15 | Verified A500S intrinsics, model axes and warp (mandatory) | NOT READY |
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
