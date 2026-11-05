# TTX Camera AI — Two-Gate Acceptance Protocol (V1.3.17)

This protocol separates the owner's wish to finish engineering before the
next phone trial from the physical measurements needed to certify xADAS-like
behavior. It replaces the ambiguous single percentage; it does NOT silently
promote any previously incomplete criterion.

## Gate A — Engineering readiness before phone testing

**Target: at least 18/20 engineering-ready criteria (>=90%).**

A criterion may be Gate A PASS only after its implementation is complete
for the defined scope, deterministic host or offline-integration tests are
green on the exact source revision, failure paths are covered, and evidence
is independently traceable in the ledger. Mere CODED status, compilation,
synthetic examples for unrelated behavior, or a green GitHub run alone do
not qualify. A PASS here means **ready for a field trial**, not real-camera
performance or deployable ADAS safety.

A standalone CI-only requirement (#1 or #2) may legitimately pass using
build/package evidence; other criteria need directly relevant unit or
integration evidence. Provenance that requires a legally authorized model
or independent reference must not be replaced with a fabricated fixture.

Current Gate A: **8/20 (40%)**, IDs 1,2,5,8,9,10,13,18.
Criterion **#8 Phone Camera2 preview and YUV420** was promoted only after
independent inspection of the owner's physical 60.12-second TTXPHONE01
trace, four valid YUV frames and a 19.79-second moving-preview recording
on 2026-11-05. This is subsequent physical evidence, not a promotion by
the two-gate migration or CI alone. See
`docs/acceptance/PHONE_CAMERA2_REAL_DEVICE_REVIEW_20261105.md`.

## Gate B — Mandatory physical and independent validation

**Target: 5/5 independently verified mandatory field requirements.**

- #14: real A500S H264 YUV420 frames through the uninterrupted RAW
  inference pipeline, with timestamped non-disruptive preview evidence.
- #15: measured A500S intrinsics, lens warp and independently validated
  model coordinate axes/optical reference.
- #16: legally permitted model, recorded model SHA256, independently
  verified real input/output and ground truth; ownership declarations
  alone are insufficient.
- #17: real lane/lead HUD with independently measured metric reference,
  geometry error and fail-closed behavior under stale/invalid inputs.
- #20: uninterrupted physical stream longer than five minutes and
  optical scene-to-screen latency measurement near 1 second, with a
  repeatable method and clear measurement uncertainty.

Current Gate B: **0/5 verified**. Even a PASS in Gate A for a mandatory
item remains *not field verified* until Gate B passes separately.

Other engineering items with physical dependencies (e.g., #3, #4,
#6–8, #19) still require phone/vendor hardware validation before any
claim of device parity; these field checks must be recorded separately
when asserted. The five numbered requirements above are the **hard final
gate** and cannot be waived.

## Final release rule

**90% completion is certified only if BOTH conditions hold:**

1. Gate A >=18/20 independently evidenced engineering criteria.
2. Gate B exactly 5/5 independently reviewed mandatory field criteria,
   with valid evidence for each requirement.

The app must remain passive; no brake/steer/CAN actuation and no calibrated
lane/lead warnings without the required verification. CI is an engineering
guard, not a trustworthy optical instrument.

## Machine-readable evidence and change control

- Canonical ledger: docs/acceptance/two_gate_ledger.json
- Validator: tools/verify_two_gate.py
- Host tests: tools/test_two_gate.py
- CI runs the validator and its tests before the Android APK build.
- Human-readable results are derived from JSON by running:
  python3 tools/verify_two_gate.py

Evidence for engineering PASS must point to a relevant repo test,
reviewed offline reference or traceable GitHub build. Evidence for each
Gate B PASS must include its complete set of mandatory evidence kinds,
a SHA256 for the referenced artifact, a UTC capture timestamp, and an
independent reviewer. The validator can verify STRUCTURE and consistency
only: a reviewer must independently inspect signatures, original
camera/video provenance, calibration procedure, model-use permission,
the real source clock and recorded experimental measurements.

Do not store the user's sensitive raw camera recordings, camera
credentials, proprietary ONNX weights or identifiable plate/face data
in public GitHub or CI artifacts without informed permission.

## Work sequencing

1. Finish Gate A's missing functions end-to-end, not just incremental
   version bumps. Prioritize offline real-model parity (#11, #16), real
   protocol/USB sources (#6–8), Camera2 correctness (#8), live pipeline
   robustness (#14), calibration workflows (#15), HUD fail-close (#17),
   test instrumentation (#20), and GPS/PiP lifecycle (#19).
2. Merge only after tests on the exact PR head and successful postmerge
   CI on the actual main SHA. No automatic PASS promotion.
3. Once Gate A reaches 18/20, prepare owner-controlled phone/A500S
   field test protocol and collect only authorized evidence. Tests can
   be run earlier for a blocker when useful but must not be claimed
   retrospectively.
4. Independently review the five mandatory Gate B results and stop
   the 90% automation only after the two gates BOTH pass.

The historical 20-item register and version log remain in
docs/PREDEVICE_90_GATE.md. The ledger is the source of progress truth.
