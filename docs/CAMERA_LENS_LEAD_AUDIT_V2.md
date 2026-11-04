# V1.2.7 — Lens distortion and independent lead-range audit

All camera and lead-range parameters in this milestone are **local-only
candidate data**. They do not configure the live vision pipeline or
automatically enable an ADAS display, lane overlay, warning, braking or steering.
Do not interpret model's raw `lead` head as a distance in metres.

## 1. Camera optical distortion — TTXCAL02

In Settings → Camera geometry diagnostics, select a `.ttxcal` file.
The previous `TTXCAL01` pinhole format remains fully supported.
New `TTXCAL02` adds a single `lens` row between `candidate` and `marker`:

```text
TTXCAL02
image,640,480
candidate,1.5,90,0,0,synthetic-lens-example
lens,320,320,320,240,-0.2,0.03,0.005,-0.003,0.005
marker,M01,4.0,-1.5,0.0,204.0,354.0
...
```

**Do not treat the illustrative marker coordinates above as camera
measurements.** A complete measured file requires 8–256 markers satisfying
geometric coverage tests. Each field is in the original 640×480 decoded
stream coordinate system, not the rescaled Android screen surface.

The `lens` parameters in exact order are
`fx_px,fy_px,cx_px,cy_px,k1,k2,p1,p2,k3`, representing the two focal
lengths, optical centre, radial and tangential distortion. Forward/right/up
vehicle axes and pitch/yaw conventions remain as documented in
`CAMERA_GEOMETRY_AUDIT_V1.md`.

For normalized image coordinates `x,y`:
- `r2=x²+y²`
- `radial=1+k1*r2+k2*r2²+k3*r2³`
- `xd=x*radial+2*p1*x*y+p2*(r2+2*x²)`
- `yd=y*radial+p1*(r2+2*y²)+2*p2*x*y`
- `u=cx+fx*xd`, `v=cy+fy*yd`.

All values must be finite and within deliberately broad plausibility bounds.
Offscreen/behind-camera rays fail closed. The existing marker residual
RMS and P95 logic is reused and its report is always
`GEOMETRY_CONSISTENT_UNVERIFIED` rather than certified calibration.

No automatic parameter fitting is included: a large set of free lens
parameters can overfit sparse data. Genuine physical validation requires
separate independently measured, held-out calibration/validation scenes and
the actual A500S camera/RTSP resolution.

## 2. Independent lead-distance measurements — TTXLEAD01

Use the new **SO SÁNH KHOẢNG CÁCH ĐO THỰC (.ttxlead)** option in the
same local diagnostic screen.

The input is ASCII CSV, at most 64 KiB:

```text
TTXLEAD01
meta,measured-example,laser-ranging-01,aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
sample,F001,6.0,7.0
sample,F002,10.0,11.0
sample,F003,14.0,15.0
sample,F004,18.0,19.0
sample,F005,25.0,26.0
sample,F006,28.0,29.0
sample,F007,32.0,33.0
sample,F008,42.0,43.0
sample,F009,55.0,56.0
sample,F010,65.0,66.0
sample,F011,85.0,86.0
sample,F012,110.0,111.0
```

**This is synthetic data for parser tests only**. The `model_sha256`
field in the `meta` line is **user-declared metadata**, not a verified
signature; a file can contain an arbitrary 64-hex value.

Each sample has a unique frame/sample ID, an independently measured
`reference_metres` and an **already metric** `predicted_metres`
from an authorized and separately validated inference-to-metres pipeline.
Do not enter raw `lead` tensor values in that last field.

The audit requires 12–512 distinct observations, reference/prediction
between 1 and 200 m, reference span at least 30 m and at least three
distance bands among [1,15), [15,30), [30,60), [60,200].
It reports:

- **MAE**: mean absolute error (m).
- **RMSE**: root mean square error (m).
- **P95**: 95th percentile of absolute range error (m).
- **MAPE**: mean absolute percentage error of each sample.
- **Bias**: mean signed (predicted minus reference) error (m).

A result `STATISTICS_ONLY_UNVERIFIED` means solely that the numerical
comparison was possible. It is not a `PASS` on physical distance accuracy,
model suitability, lens calibration, synchronized timestamps or ground-truth
source provenance.

## 3. Mandatory evidence still missing

Even a perfect synthetic pinhole or lens residual is trivial to fabricate.
The real A500S camera's lens coefficients, focal length, principal point,
IMU pose, timestamp alignment, true model axis interpretation, distance
ground truth and actual glass-to-glass camera latency remain unverified.

`VisionSafetyGate.Calibration.ready` is not changed by either import,
and these modules are never wired to the production HUD or live warnings.
See `PREDEVICE_90_GATE.md`; project remains below the 18/20 engineering
gate and no phone testing is required yet.
