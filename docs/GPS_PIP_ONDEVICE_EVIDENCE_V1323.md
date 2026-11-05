# V1.3.23 — Owner-controlled GPS/PiP/settings physical trace

## Why this exists

Criterion #19 remained CODED because host tests did not prove Android GPS
permission/provider behavior, Picture-in-Picture entry/exit, retention of
the Surface video, or correct application of persisted HUD preferences
on a real handset. Green GitHub builds cannot substitute for these checks.

The Android MainActivity now includes the owner-triggered control:
**THU KIỂM TRA GPS / PiP / CÀI ĐẶT**. Press it to start a bounded in-memory
TTXGPIP01 session, then perform the following tests on the real phone:

1. Press the GPS speed control. If Android asks for fine location
   permission, grant or deny it intentionally. Check that the live speed
   remains blank when permission is absent, GPS provider is off or no fresh
   fix is available. Do not interpret blank as speed zero.
2. Connect to the A500S and play **IJK**, then use the PiP control.
   Confirm the physical screen enters small-window mode and the
   playback is still progressing. Return to the full-screen Activity.
   Repeat once with PiP disallowed in Android settings, if possible.
3. Open app settings, choose **mph · ft**, hide the TTX label, return
   to the app and check GPS still appears in mph. Change back to km/h
   and confirm the unit changes without restarting playback.
4. Return to the main Activity and press the recording control again.
   Select **XUẤT BIÊN BẢN TTXGPIP01** and save the UTF-8 text with
   Android Storage Access Framework. Save screenshots / a short
   independent second-device video of the PiP transition separately.

## What TTXGPIP01 contains

The trace records elapsedRealtime-based monotonic relative milliseconds,
boolean GPS active/permission/provider/fresh-speed availability, platform
PiP state, Surface validity, selected IJK/DIRECT/NONE backend and the
metric/imperial and brand-display settings.

It also records distinct events for PiP requested, request rejected,
request returned true, **actual platform PiP entry callback**, actual exit
callback, GPS permission granted/denied, GPS toggle and settings return.
A PiP request Boolean is NEVER treated as proof of an actual entry.

The trace stores NO coordinates, GPS raw fixes, numeric speed, route,
network URL, A500S credentials, camera video, faces/plates or model
tensors. Rows are bounded; invalid local clock or overflow prevents
reliable interpretation. A stop/rejection still yields an exportable
incomplete trace.

### Acceptance limitation

The report always contains:
    criterion_19=NOT_VERIFIED
    gate_A_19=CODED_PENDING_INDEPENDENT_DEVICE_REVIEW
    independent_phone_review=REQUIRED

Even a trace with PiP entry and exit callbacks is not a full proof
that the camera's physical video remained visible and continuous.
Independent reviewer must compare TTXGPIP01 with the handset screen
capture/observation, correct Android permission behavior, stable
Surface during PiP and preference persistence. Do not upload
unredacted street/video imagery or private GNSS positions to public
GitHub.

Unit tests of the trace guarantee only boundedness, clock/order checks,
fixed event types, privacy-safe fields and fail-closed label semantics.
They do not simulate Android GPS provider, native IJK or actual PiP.
The physical hardware test can be completed by the owner using only
an Android phone; no PC, paid service or rooted device is required.

Gate A remains **7/20 (35%)** and mandatory Gate B **0/5** until
independent review promotes a criterion with actual evidence.
