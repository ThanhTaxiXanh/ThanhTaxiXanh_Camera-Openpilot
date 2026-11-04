# V1.3.12 — Criterion #11 independent ONNX acceptance protocol (proposal)

Status: **NOT PASS**. This document defines a reproducible acceptance protocol; it does not claim a model, license, reference capture, hardware result, or working inference.

## Required evidence before #11 may be promoted

1. Record the exact Git commit, AI APK SHA-256, ONNX Runtime version and ABI. Verify Lite contains no ONNX native runtime and cannot invoke the engine.
2. Use a model with documented owner authorization for this particular use. Record model bytes SHA-256 and the permission/provenance source **outside** self-declared fixture metadata. Do not redistribute the model in CI artifacts.
3. Independently produce a reference result using a separate trusted runtime/process on the **same exact model bytes and exact two FLOAT32 inputs**, not the app's own output recycled as expected values. Record runtime/version, input tensor hashes, shapes, dtype, element ordering, output name/shape and reference output hash.
4. Run the actual production Android AI ONNX Runtime path against those inputs. Confirm both tensor byte hashes match reference inputs before inference; compare all 1,576 output values with documented absolute/relative tolerance, NaN/Inf policy, and per-index maximum discrepancy. Capture raw results and execution log. A fake engine or deterministic synthetic fixture is only regression coverage.
5. Negative cases must fail closed: model hash mismatch, missing/invalid authorization, wrong input 1 or input 2, shape/dtype mismatch, truncated/non-finite outputs, missing runtime, interrupted worker, and stale inference epoch. No HUD, warning, metric lead or vehicle control may be activated by this audit.
6. Store independently reviewable evidence with provenance, exact SHA links and reproducible commands, excluding proprietary weights and private camera footage. Reviewer must confirm the reference is independent, not a self-signed claim.

## Scope boundaries

Passing #11 demonstrates **graph loading, execution and reference parity only** for the tested model/input combination. It does not establish real A500S image capture (#14), camera intrinsics/model axes (#15), legal distribution or ground-truth model behavior (#16), calibrated live HUD (#17), or exposure-to-glass latency (#20). Criterion #19 requires separate Android device evidence for GPS/PiP/settings.

## CI merge gate

- One PR at a time. Exact PR head SHA must pass JVM/unit/integration and ARM64 Lite+AI builds, native packaging checks and both artifact uploads.
- Squash merge only after the exact head passes. Verify a fresh post-merge `main` workflow run belongs to the actual squash commit and succeeds.
- Do not promote any acceptance criterion from this document or a green build alone.

Baseline: **7/20 = 35% PASS CI**; follow-up alert threshold **9/20**, final engineering target **18/20** with mandatory #14–17/#20. No new on-device trial is requested by this proposal.

## Implemented local audit receipt

TTXONNX-AUDIT-01 records exact SHA256 of installed ONNX model, canonical big-endian IEEE754 Float32 digests for each input and reference/real runtime output, plus comparison metrics. Owner can share the text report via Android ACTION_SEND. No weights, source frames or public upload. A matching self-imported vector remains MATCHED_UNVERIFIED, not independently accepted. Four JVM synthetic regression tests verify endianness and failure behavior. Criterion #11 remains NOT PASS until legal model/reference independently corroborated.
