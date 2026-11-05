# V1.3.33 — Camera2 multi-lens YUV source selection

## Defect and behavioral change

V1.3.32 selected the first rear camera from CameraManager.cameraIdList,
then tested only that selected camera for YUV_420_888 at 640x480. Several
Android phones advertise distinct logical, ultra-wide, telephoto and front
camera IDs, each with a different StreamConfigurationMap. Therefore
rejecting the first rear camera could falsely reject an entire phone
even if another rear or front camera could actually supply Camera2 YUV.

The new PhoneCameraCandidateSelector keeps Camera2 selection simple,
explicit and testable:

- Inspect all readable camera IDs for 640x480 YUV_420_888 support.
  CameraAccessException/invalid configuration on one auxiliary ID does
  not prevent considering other camera IDs.
- Rank compatible rear first, front next, other last. In a given class,
  prefer sensor timestamp source REALTIME for RAW diagnostics, then
  UNKNOWN; do not pretend UNKNOWN is monotonic boot-time.
- Keep stable CameraManager enumeration order on priority ties. Never
  create imaginary 640x480 output formats or resample camera pixels
  without a separate documented adapter.
- Show the actual selected lens category (BACK / FRONT / OTHER) in the
  running Camera2 preview status.

This is not automatic failover after createCaptureSession fails. Android
may advertise YUV 640x480 and still reject a specific simultaneous
Surface + ImageReader combination. Such failures stay visible and are
not silently described as success.

## Tests and acceptance boundary

Ten deterministic JVM tests cover an unsupported first rear ID, a
second compatible rear, REALTIME vs UNKNOWN in the same lens category,
front/other fallback, no compatible camera, stable ties, invalid IDs,
per-camera survey skipping and immutable input listings.

These tests and CI can only verify the selection policy and Android
compilation. They cannot prove that a given handset can open two
surfaces, deliver native Images, record an unbroken preview or meet a
latency target. The V1.3.32 hardware evidence flow remains available:

1. Install AI or Lite ARM64 APK from the successful main CI build.
2. Open CAMERA ĐIỆN THOẠI on the home screen. The status shows
   BACK / FRONT / OTHER and REALTIME / UNKNOWN timestamp source.
3. Keep camera aimed at a non-sensitive indoor test subject. Tap
   BẮT ĐẦU ĐO CAMERA2 · 60 GIÂY, then save TTXPHONE01.
4. Tap LẤY 4 KHUNG YUV THẬT, then save TTXYUV01.
5. Record a short screen video proving the camera preview changes.
6. Return the text report, YUV fixture and screen video for independent
   physical evaluation of criterion #8.

Neither a camera clock UNKNOWN nor a successful local trace is license
to display metric lanes, lead distance or ADAS alerts.

Gate A remains 7/20 (35%); Gate B 0/5. No automatic PASS promotion.
