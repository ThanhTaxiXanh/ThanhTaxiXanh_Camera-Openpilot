# V1.3.22 — GPS HUD unit preference and label isolation

## Actual defects

The Settings UI persisted `SPEED_UNIT = "mph · ft"` but AdasHudView
always displayed GPS speed in km/h and any verified lead in metres.
This is misleading to a driver who chose imperial units.

The Settings toggle `SHOW_LOGO` was also connected to
`AdasHudView.hudVisible`, hiding ALL HUD content — including valid
GPS speed — when the user only wanted to hide the TTX brand label.

## Correction

- A pure Kotlin `HudDisplayFormat` converts the already validated
  GPS km/h readout to either km/h or mph and any independently verified
  metric lead to metres or feet. No new sensor input or model estimate
  is introduced. Unrecognized persisted unit values fail back to metric.
- The `SHOW_LOGO` setting only affects the brand caption; it does
  not suppress GPS speed or legitimately validated lead values.
- `ROUND_DISTANCE` now acts on verified metric distance at >=20m:
  first round to 5m then convert to display units. Below 20m is exact
  to the existing whole-unit display granularity.
- MainActivity re-reads these user preferences on resume from the
  Settings screen. No need to restart RTSP or recapture camera images.
- Fourteen pure JVM tests verify both units, unknown preference fallback,
  null/NaN/overflow safety, rounding and independence of the brand flag.

## Safety and proof limits

This is **rendering only**. GPS itself is not an ADAS speed reference.
VisionSafetyGate still requires a real model, genuine camera calibration,
fresh time and high confidence before any lead distance can be displayed.
The new converter MUST NOT fabricate a detected vehicle or lead metres.

These host tests do not validate hardware GPS provider behavior, Android
PiP Surface lifecycle, screen layout or actual driving. Item #19 remains
CODED until an independently reviewed phone/Android lifecycle test covers
GPS permission and stale fixes, PiP enter/exit/rejection and persisted
settings in both metric/imperial modes. The official engineering status
remains Gate A **7/20=35%**, mandatory Gate B **0/5**.
