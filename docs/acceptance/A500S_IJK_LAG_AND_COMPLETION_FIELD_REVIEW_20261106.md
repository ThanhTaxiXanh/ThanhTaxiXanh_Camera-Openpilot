# Independent review: owner A500S IJK optical delay and RTSP completion (2026-11-06)

## Provenance

Three owner-supplied private files, reviewed locally without committing photos,
video, street/vehicle/person identity or camera-address details to the repo:

| Material | File SHA-256 | Technical scope |
|---|---|---|
| 73.80s screen recording, 512x1136 H264 at 30fps | `f061d441204be79434673b83ea3656c127d711458e696657c67e140fc7f8c9a4` | Player UI, smooth TCP settings, native stats and error text |
| 30.667s simultaneous road+handset camera recording, 512x910 H264 at 30fps | `0254aa14144f890f3f12207929906be5f566831eb75d56fa2153e9cea5de5bff` | Optical scene and handset video in common field of view |
| TTXLIVE01 owner local audit | `8a7a41ef436083ef62af8609d5d19b2a5dcbe26bf6d1c1d5115a24f836859edb` | Local IJK run telemetry and interruption |

Do not interpret these SHA strings as cryptographic camera/device attestations.

## Verified observations

- Owner reports ~3s scene-to-screen lag for moving road objects in TTX;
  the A500S's own camera view is immediate. The 30.667s owner video shows
  the scene and the TTX handset simultaneously, but frame-accurate optical
  synchronization and a quantitative 3.000s estimate have NOT been independently
  established. Treat ~3s as an owner-reported field estimate.
- The screen shows the owner-selected **SMOOTH TCP** profile. In sampled
  screen frames the player reports **internal video cache 0 ms** while
  owner-reported scene lag is ~3s. Native `videoCachedDuration` is NOT
  a photon-to-glass metric and therefore is not evidence of low latency.
- TTXLIVE01: backend=IJK, 37 local-progress samples; FPS min **17.39**,
  max **30.77**, average **25.21** (native output FPS estimates);
  `event:reconnect,37438`; `end_reason=PLAYER_UNAVAILABLE`;
  `elapsed_ms=37470`; `reconnect_events=1`; 5-minute target=300000ms.
- The screen UI itself shows `Lỗi cuối: RTSP kết thúc (OnCompletion)`,
  and `khôi phục 1/5`; subsequent video resumes. The source/transport
  reason for the RTSP completion cannot be assigned solely from callback:
  camera session policy, network, FFmpeg and player lifecycle remain hypotheses.
- Original TTXLIVE01 recorder sealed immediately when `ijk` was null
  during an asynchronous reconnect, so it lost later recovery telemetry.
  This is a separate diagnostic continuity defect, not a fix for RTSP itself.
- YUV tap/ONNX data not armed, `optical_latency_seconds=NOT_MEASURED`;
  native Surface output FPS does not prove motion originality or safety HUD.

## V1.3.44 experiment and strict acceptance

Keep SMOOTH (untouched) as baseline. Add owner-selected **BALANCED TCP**:
packet-buffering=1; min-frames=10; max_cached_duration=1000ms;
FFmpeg max_delay=500000 microseconds. Existing LOW_LATENCY TCP remains
more aggressive: packet-buffering=0, min-frames=5, cached-duration=600ms,
max_delay=300000us. UDP_SMOOTH retains previous settings.

No undocumented FFmpeg flags, extra decoder, forced auto-seek or
blind cache-triggered reconnect. Limits are IJK queue controls,
not a 1s promise for camera-to-display delay. Only optical A/B on
the A500S can determine whether internal buffering is causal.

Keep a TTXLIVE01 recorder active when `ijk=null` during a reconnect,
record `NO_PROGRESS` and `event:reconnect,...,RTSP_ON_COMPLETION`;
on later local progress, record gap `event:local_progress_resumed`.
**Any reconnect still means #20 cannot PASS** even if 300s total
duration is reached. Run user-led comparison and collect fresh owner
video/trace to assess reconnect frequency and scene delay.

Current acceptance Gate A 8/20=40%; Gate B 0/5. No new PASS.
