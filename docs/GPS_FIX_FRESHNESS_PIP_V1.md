# V1.3.13 — GPS fix-time freshness and safe PiP rejection

## Actual defect fixed

GPS speed was previously stamped on the Android listener's receipt timestamp.
An Android Location callback can carry a previously cached fix, so marking it
"fresh" at receipt time can display incorrect/stale speed for another 3.5s.
A newer fix without speed also left the prior display alive temporarily.

GpsSpeedTrustGate instead compares Location.elapsedRealtimeNanos with
SystemClock.elapsedRealtimeNanos, rejecting fixes older than 3.5s, fixes
from the future, duplicate/out-of-order fixes, invalid speeds and a
reported speed accuracy worse than 5m/s. Missing speed or invalid newer
fix clears prior output. Speed remains null after fix expiry, permission
revocation or disabled GPS provider. Speed accuracy absent from OS remains
an unverified provisional GPS speed, NOT ADAS-grade ground truth.
All monotonic timestamps share one boot clock; UTC is not used.

Android PiP has an overlooked failure path: enterPictureInPictureMode()
returns Boolean and may return false without throwing. Previous code left
pipTransitioning=true; a later Surface destroy would then skip normal
cleanup. V1.3.13 resets the flag on explicit PiP rejection.

## Evidence boundary

Pure JVM unit tests validate freshness, cached callback delays, expiry,
duplicates, no-speed fixes, speed range/accuracy and reset. Build and APK
packaging can run in GitHub Actions. No emulator, GNSS satellite data,
Android PiP lifecycle or camera A500S physical verification is present.
This is a real behavior fix, but **not a #19 PASS**, not a validated GPS
speed sensor, and does not permit audible lane/lead warnings or automation.
Criteria #14–17 and #20 are still mandatory and unverified.
**Acceptance remains 7/20=35%.**
