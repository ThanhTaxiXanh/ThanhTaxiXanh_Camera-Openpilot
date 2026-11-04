# V1.3.1 — RTP 90 kHz clock, H264 AU pacing and Surface rendering callbacks

## Goal and evidence boundary

Owner previously observed ~3 seconds real-world preview lag in TTX
(IJKPlayer and Direct) vs ~1 second in official 70mai 4.5.0 on the
same phone and A500S. V1.3.1 does NOT claim any physical reduction.
It adds separate diagnostics that distinguish **video timestamp cadence**,
**phone H264 decoder delay**, and **MediaCodec-to-render callback delay**.

## A — RTP timestamp continuity & wrap-around

`RtpAuClockAudit` consumes whole H264 access unit timestamps from the
Assembler (not individual RTP packet arrival bursts). Every AU has a
source 32-bit unsigned 90 kHz timestamp and local
`SystemClock.elapsedRealtime()` receipt time.

- Normalize each RTP session's first AU to a 1,000,000 microsecond PTS.
  Subsequent PTS accumulate signed modular 32-bit tick deltas, preventing
  a spurious MediaCodec time reset at RTP 2³² wrap-around.
- Permit a **small negative timestamp delta** up to 500 ms because
  B-frame reordering cannot be ruled out on unknown encoders.
- Reject any forward jump above 5 seconds or backward jump beyond
  500 ms; the Direct player reconnects to recover an H264 reference
  sequence instead of inventing monotonically valid PTS.
- Calculate each RTP frame interval (ms), each local AU receipt
  interval (ms), interval error `RTP_delta_ms - receipt_delta_ms`,
  and cumulative session pace drift.
- Keep a bounded 128-sample window of absolute pacing errors for P95,
  32-bit wrap count and rejected timebase transitions.

The **cumulative pace drift is not stream delay**: it compares two
relative running clocks anchored at the *first observed AU*. A
camera can have a constant 3-second encoder delay and zero pace drift.
The local RTP packet jitter counter remains independent.

## B — MediaCodec output → Surface render-event instrumentation

The existing `PreviewDecodeTimeline` measures local AU receipt to
`MediaCodec.Callback.onOutputBufferAvailable`.

V1.3.1 adds `SurfaceFrameTimeline`:

- Immediately before `releaseOutputBuffer(index, true)`, record
  `System.nanoTime()` keyed by the output `presentationTimeUs`.
- Register `MediaCodec.setOnFrameRenderedListener` on the codec
  handler. Correlate the callback's frame PTS and supplied render
  timestamp in nanoseconds with the release timestamp.
- Reject missing, duplicated, evicted, negative-clock samples.
- Hold at most 96 pending PTS references, 128 recent valid
  release-to-render durations, and report last/P95 (ms).
- Suppressed decoded outputs (`render=false`) are not treated as
  Surface-presented samples, and no native Image/Surface buffer is held.

**A MediaCodec frame-rendered callback is not guaranteed to be the
precise moment the pixels become visible to the driver.** It is
not camera exposure time or a photodiode/high-speed screen
measurement. Some vendors may not emit the callback consistently;
unmatched and empty counters must remain visible instead of being
converted into fake zero latency.

## C — Experimental Direct profiles preserved

The V1.3.0 Smooth (default) and FRESHNESS_GUARD (opt-in) settings are
unchanged. The new timestamp normalization and render callback
diagnostics apply to both. Neither path skips compressed H264 P-frames.

The app does NOT call any undocumented 70mai CGI to change encoding,
request a privileged RTSP stream, or bypass device authorization.
There is no change to xADAS model/lead/hardware safety acceptance.

## Host CI tests

- 30fps (3000 ticks/90 kHz) AU cadence against a 33ms local-arrival clock.
- RTP signed modular timestamp wrap, small reverse B-frame ordering,
  large forward/backward jumps, monotonic local-clock regressions.
- 128-sample P95 rolling error and long-term cadence drift.
- Callback PTS match when codec renders frames out of input order,
  invalid clocks, evicted entries, `render=false`, bounded 96/128
  capacity and reset semantics.
- Both Lite and AI Android variants build their ARM64 debug APKs.

A PASS proves only code, pure fixture logic, and CI compilation.
It does not verify A500S-specific optical/encoder delay,
Android GPU/SurfaceFlinger latency, 70mai official playback parity,
or the >=90% pre-device gate.

See `docs/DIRECT_LATENCY_GUARD_V1.md` for the playback guard
and `docs/PREDEVICE_90_GATE.md` for acceptance requirements.
