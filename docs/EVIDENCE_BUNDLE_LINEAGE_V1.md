# V1.3.6 — Reproducible evidence lineage for independent camera/model audits

## Motivation and strict distinction

Earlier releases support offline YUV-to-tensor replay and optionally compare
the generated first pair against the input/output reference for a
user-authorized ONNX model. This is reproducible engineering *only if the
three input files are the exact files intended by the evidence provider*.
A model could be swapped, a reference edited or a YUV clip replaced.

V1.3.6 adds a **bounded local manifest** tying three files together by
SHA-256. **The person who creates a manifest can also forge the declared
camera, instrument and license identifiers. A matching SHA chain is NOT
independent source authentication, legal authorization, signed provenance,
pixel calibration, xADAS model parity or correct physical ADAS output.**

## Canonical TTXEVD01 file protocol

The app's **Camera Source Settings → Offline YUV → third button**
lets the owner pick a local TTXEVD01 manifest after selecting the
TTXYUV01 and TTXREF01 reference fixtures.

Manifest is ASCII, LF-separated, at most 2 KiB, with **exactly** these
eight lines in order, each key present once, no comments or extra bytes:

```text
TTXEVD01
source_id=A500S-SUPPLIED-EXAMPLE
recorded_utc=2026-11-04T14:35:00Z
instrument_id=CLAIMED-INSTRUMENT-001
permission_id=CLAIMED-PERMISSION-001
yuv_sha256=<64 lowercase hex chars>
ref_sha256=<64 lowercase hex chars>
model_sha256=<64 lowercase hex chars>
```

The three SHA-256 fields are full hashes over the **exact on-disk
bytes** of the previously selected TTXYUV01 and TTXREF01 files and the
already-imported app-private `models/vision.onnx`.

The `recorded_utc` field requires valid ISO UTC timestamp to whole
seconds. `source_id`, `instrument_id`, `permission_id` are limited
to 3–96 ASCII characters using letters, numbers, dot, dash, underscore,
colon or slash, beginning with an alphanumeric. Values are
**self-declared**; dates are syntax-checked but not authenticated.

`EvidenceBundleManifest.verify` streams bytes in bounded memory:
the YUV file cannot exceed the TTXYUV01 maximum, the reference cannot
exceed the TTXREF01 fixed size, and the model cannot exceed 250 MiB.
After comparing each manifest digest, the app reopens and parses the
reference and ensures **its embedded ONNX SHA-256 matches the manifest's
model hash**, closing the three-file consistency loop.

Possible result codes are:
`YUV_HASH_MISMATCH`, `REFERENCE_HASH_MISMATCH`, `MODEL_MISSING`,
`MODEL_HASH_MISMATCH`, `REFERENCE_MODEL_MISMATCH`,
or `MATCHED_SELF_DECLARED`. An invalid manifest structure is rejected
before file hashing. No state can set `provenanceVerified=true` or
`permitsAdas=true` because no independent trusted signing keys,
source-of-record licensing authority or measured camera evidence are
available.

The validator does not upload, save or execute the selected materials,
does not change models or playback configuration, and never routes
outputs to driver warnings/HUD. It uses Android's local document picker
and a detached worker.

## What remains necessary for a truly independent evidence chain

A verified supplier or explicitly authorized source would need to
provide the original 70mai A500S measurement dataset and reference
tensor captures; camera intrinsic/extrinsic measurements against
independent ground markers; source timestamp, device firmware and
capture method; an unmodified, licensed ONNX model and corresponding
golden results; and ideally a **signature anchored in a public key
obtained independently of the data bundle**.

A self-signed or unsigned manifest does not establish who produced it,
even if its cryptographic file hashes match perfectly.

Synthetic CI proves the parser, digest binding, mismatch statuses,
invalid metadata rejection, manifest size limits and non-promotion of
unverified artifacts. It does not demonstrate any genuine video/model
ground truth, nor the observed A500S ~3s TTX / ~1s 70mai official
glass-to-glass difference.

## Acceptance accounting

The project's documented 20 criteria currently have
**7/20 = 35% PASS CI**. At least **18/20 = 90%** and mandatory
camera/model/geometry/physical-latency requirements must pass before
the requested full test milestone. Adding a manifest parser alone
does NOT promote #14–17 or #20: progress remains 35% at this stage.

There is an unavoidable evidence dependency: because some mandatory
criteria explicitly require live A500S and optical tests, a strict
pre-device 90% gate cannot be proven *solely from synthetic CI*.
This release prepares the evidence pipeline but does not misrepresent
the impossibility as a passed acceptance test.
