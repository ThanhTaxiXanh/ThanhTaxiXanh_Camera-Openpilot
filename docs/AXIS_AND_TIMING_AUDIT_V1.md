# V1.2.8 — Model axis hypotheses and timestamp-paired lead audit

This milestone only offers **offline, non-authoritative diagnostics**. It
does NOT identify xADAS/Openpilot's physical 3D axes, certify A500S camera
calibration, recover lead distance in metres from a raw 1,576-float model
tensor, or enable driving warnings. A successful synthetic vector can be
constructed to match any chosen hypothesis.

## 1. TTXAXIS01 — hypothesis ranking of 3D model-axis signs

Use **Cài đặt → Hình học camera → KIỂM TRA HỆ TRỤC MODEL (.ttxaxis)**.
The ASCII CSV file is at most 64 KiB and has 12–256 paired samples.

```text
TTXAXIS01
meta,synthetic-only,unverified-markers,aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
point,S001,4,-2,0.9,4,2,0.9
point,S002,4,0,0,4,0,0
point,S003,4,2,-0.9,4,-2,-0.9
...
```

Each `point` row is
`point,ID,modelForwardRaw,modelLateralRaw,modelVerticalRaw,referenceForwardM,referenceRightM,referenceUpM`.

**All "reference" fields must come from independently measured geometry
with an explicitly documented clock and matching procedure.** The CSV
`modelSha256` field is declared metadata, not checked cryptographically;
model coordinate units are NOT assumed to be metres. The use of metres in
the scoring function is only a hypothesis that must be tested against real
evidence; do not feed unchecked network captures or proprietary model data.

The program compares four sign hypotheses: ±lateral and ±vertical.
It requires at least 20 metres of reference forward spread, 2 metres of
model lateral spread, 0.5 metres of model vertical spread and reference
positions on both sides of centre. It computes 3D RMS error, rejects high
error and marks nearly tied alternatives `AMBIGUOUS_AXES`, never forcing
a winner when a sign cannot be established. A tight match is reported
as `MATCH_UNVERIFIED` rather than calibration or ground truth.

**Important:** The current public openpilot-style lane X sample grid
`forwardMeters(i)` is a structural parser convention, not proof that
an unverified ONNX model's Y/Z values already use metres or car axes.

## 2. TTXLEAD02 — pair metrics and receipt-time alignment

Use the existing **SO SÁNH KHOẢNG CÁCH ĐO THỰC (.ttxlead)** action.
`TTXLEAD01` remains supported, but has no timestamps and reports
"chưa có timestamp để đồng bộ". V2 adds a clock declaration:

```text
TTXLEAD02
meta,independent-example,range-meter-01,aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
clock,shared-monotonic-ms,session-01
sample,F100,1000,1070,6.0,7.0
sample,F101,1200,1270,10.0,11.0
...
```

V2 `sample` columns:
`sample,frameID,referenceAtMs,predictionAtMs,referenceMeters,predictedMeters`.

Time values must come from the **same monotonic clock domain, same device
and recording session**; users must independently verify this. A self-declared
`clock` row is not proof of synchronization. The parser requires:

- 12–512 unique samples, strictly increasing reference and prediction time.
- Nonnegative bounded monotonic timestamps and no duplicate frame IDs.
- An absolute per-pair skew of at most 100 ms; V2 reports maximum and
  P95 skew. It refuses to calculate range statistics from over-skewed pairs.
- The existing TTXLEAD01 metric checks (1–200 m, span >=30 m, >=3 range bands).

MAE, RMSE, P95 absolute range error, MAPE and bias only compare numbers
which are already in **metres** from an independent conversion pipeline.
The 100 ms skew threshold is an offline **fixture validity guard**, not
the measured A500S glass-to-glass latency. Camera/network RTP receipt
timestamps are NOT camera optical-exposure timestamps.

## 3. Safety acceptance remains blocked

Even when `MATCH_UNVERIFIED` and `SYNCHRONIZED_STATISTICS_UNVERIFIED`
are returned, this proves only numerical agreement with user-provided
fixture fields. Neither imported source metadata nor sample pairing is
authenticated, and both can be fabricated.

No new `VisionSafetyGate.Calibration` is saved, flags
`groundTruthValidated`/`modelCoordinateFrameValidated` are unchanged,
no inferred `leadDistanceMeters` is produced, and no HUD/warning is
enabled. Independent sensor provenance, calibrated A500S optics, genuine
ONNX/RAW coordinate matches, frame-exposure alignment and actual device
testing remain separate prerequisites for the 18/20 pre-device gate.
