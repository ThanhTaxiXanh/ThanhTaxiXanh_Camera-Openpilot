# V1.2.6 — Camera geometry reprojection audit (diagnostic only)

This is an **offline geometric consistency check**, not a physical
camera-calibration certificate, automatic camera calibrator, or ADAS unlock.
Even a passing audit does NOT make `Calibration.ready=true`.
User-supplied world points/model coordinates must be independently measured
and validated with the camera mounted in its final, real vehicle position.

## Import protocol

In the app, open **Cài đặt TTX Camera AI → Hình học camera →
KIỂM TRA MỐC ĐO (.TTXCAL)**.

A `.ttxcal` is plain ASCII CSV (no images, executable data, or JSON),
at most 64 KiB. It uses exactly the following columns:

- Line 1: `TTXCAL01`
- Line 2: `image,<width_px>,<height_px>`
- Line 3: `candidate,<camera_height_m>,<horizontal_fov_deg>,<pitch_deg>,<yaw_deg>,<dataset_id>`
- Each remaining line: `marker,<unique_id>,<forward_m>,<right_m>,<up_m>,<pixel_x>,<pixel_y>`

World axes are **forward/right/up**; the camera center is at the
configured height, yaw positive to the right and pitch positive downward.
Pixel origin is image top-left. Marker coordinates are image pixel
observations; they are not derived from inferred xADAS lead values.

A complete **synthetic-only testing example** (NOT real vehicle data):

```text
TTXCAL01
image,640,480
candidate,1.5,90,0,0,synthetic-fixture
marker,M01,4,-1.5,0,200,360
marker,M02,4,0,0,320,360
marker,M03,4,1.5,0,440,360
marker,M04,7,-1.5,0,251.42857,308.57143
marker,M05,7,0,0,320,308.57143
marker,M06,7,1.5,0,388.57143,308.57143
marker,M07,16,-1.5,0,290,270
marker,M08,16,0,0,320,270
marker,M09,16,1.5,0,350,270
marker,M10,32,-1.5,0,305,255
marker,M11,32,0,0,320,255
marker,M12,32,1.5,0,335,255
```

This example is intentionally fabricated by the same pinhole equation
used by the audit and **cannot** independently validate camera calibration.

## Fail-closed rules

- Require 8–256 unique, finite, in-frame, forward-facing measured markers.
- Reject implausible height/FOV/pitch/yaw, duplicate IDs or 3D points,
  malformed CSV, unsupported encoding and oversized files.
- Demand markers spanning >=20% of image width, >=10% of image height,
  and >=10 m of forward distance; narrowly clustered points are not enough.
- Project every 3D point to pixels using the candidate geometry. Reject if
  any point is behind camera/outside image.
- Compute Euclidean residuals in pixels: RMS and 95th percentile. Diagnostic
  thresholds: RMS <=4 px and P95 <=8 px.
- Report `GEOMETRY_CONSISTENT_UNVERIFIED`, not 'calibrated'. User data
  provenance and model axes remain unverified even when RMS is very small.
- All inputs and results stay on-device. No persistence, background capture
  or model weight upload is introduced.

## Remaining independent proof

The synthetic reprojection test does not establish camera intrinsics
(Fx/Fy/distortion), exact optical center, lens radial distortion, model
coordinate axes, calibration transfer to 640x480 RTSP images, lead-target
ground truth, or live glass-to-glass latency. Mandatory criteria #15–17
in `PREDEVICE_90_GATE.md` remain NOT PASSED.

The HUD lane projection additionally preserves breaks at unprojectable
points (no stitching across missing image data), which is independent of
whether valid calibration has ever been supplied.
