# V1.3.35 — Fix disappearing live preview on A500S Direct MediaCodec

## Actual owner evidence

The owner reported that opening XADAS · MEDIACODEC did not visibly
display live video. Their actual Android screen recording was 124.9
seconds at 512x1136 pixels and 30 FPS. On-screen RTP and Surface
frame counters continued around 27–30 FPS. Most of the screen was
occupied by buttons and diagnostics; at around 62 seconds a narrow
strip of moving A500S footage appeared near the top.

This is strong evidence for a video VIEWPORT layout defect, not
proof of RTSP failure. MainActivity previously put the video
FrameLayout inside the same vertical LinearLayout as the entire
large fixed-height control stack, with LayoutParams(height=0,
weight=1f). When the control stack consumed the phone viewport,
SurfaceView received almost no vertical space, even if its decoder
continued releasing actual video frames to the Surface.

Separately, the owner video showed repeated YUV offscreen
IllegalStateException: Offscreen input queue full; await next IDR.
That is the optional SECONDARY YUV decoder, not the primary Surface.
It remains a distinct unresolved issue and is not fixed here.

## Code change

- Replace the single oversized vertical column with an outer shell.
- Give the direct/IJK camera Surface and transparent HUD a dedicated,
  NONZERO, approximately 4:3 top preview region (capped at 45%
  of display height; policy is testable in pure Kotlin).
- Place buttons, source options and diagnostics in a separate
  ScrollView below the pinned live preview.
- In Android PiP, hide other panes and give video weight=1 so
  the Surface fills the PiP window. Restore normal positive
  preview height on exit.
- Preserve the original RTSP player, MediaCodec Surface,
  IJKPlayer, HUD transparency and ownership semantics.
- Add seven geometry tests and three source-layout regression
  checks to CI. Tests verify code invariants, not the live display.

## Required device re-test

Park the vehicle and connect to the owner's A500S Wi-Fi.
From a successful post-merge V1.3.35 Lite APK:

1. Leave YUV A500S OFF and ONNX RAW OFF.
2. Tap XADAS · MEDIACODEC and observe a large preview above buttons.
3. Record 15–30 seconds showing live scene motion; scroll the
   control area and verify the video area remains visible.
4. If it works, turn on YUV as a SEPARATE trial and check whether
   the offscreen input-queue error still occurs. Do not enable ONNX.

Do not conclude that a Surface release counter measures photons or
glass-to-glass latency. A camera-to-glass latency measurement needs
a separate simultaneous view of real scene and phone display.

## Acceptance

No new phone test has yet been done on this revision.
Gate A remains 8/20 (40%), Gate B 0/5. Criterion #3
(A500S preview), #14 (A500S YUV→ONNX) and #20 (5-minute
optical latency) remain unverified by this UI fix.
