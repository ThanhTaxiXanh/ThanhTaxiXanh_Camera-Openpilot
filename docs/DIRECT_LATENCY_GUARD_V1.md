# V1.3.0 — Direct A500S backlog guard and decoder-latency audit

## Why the experiment matters

Owner observations on the **same handset + 70mai A500S**:

- Official 70mai 4.5.0 live preview: approximately **1 second**
  behind the real scene.
- TTX IJKPlayer and independent Direct MediaCodec preview:
  approximately **3 seconds** behind the real scene.
- Prior IJK packet-buffering settings and tiny IJK video-cache metrics
  did not eliminate the approximately two-second difference.

**These are user-reported visual observations**, not new measurements.
They cannot be turned into a claimed speedup from GitHub Actions. Possible
locations of the extra delay include camera encoding/stream choice,
network-side RTP/TCP backlog, in-app access-unit buffers, MediaCodec
hardware output, Surface queue and screen scanout. The new instrument
only measures a subset.

## Playback switch: experimental, OFF by default

In MainActivity there are **two independent player profile controls**:

- IJK: existing TCP smooth/low-latency and UDP smooth profiles,
  unchanged in this version.
- Direct: new `DIRECT: MƯỢT · BỘ ĐỆM GỐC` (default) or
  `DIRECT: THỬ GIẢM TÍCH LŨY TRỄ` (opt-in). A restart applies the
  selected Direct profile; RTSP reconnects preserve the active profile.

| Direct setting | Smooth default | Experimental freshness guard |
|---|---:|---:|
| Queued compressed H264 access units | 48 | 12 |
| Queue-full offer timeout | 900 ms | 80 ms |
| Max age of queued AU | 1,800 ms | 450 ms |
| Max wait for MediaCodec input slot | 1,500 ms | 350 ms |
| Request vendor MediaCodec low-latency | no | yes, fallback to no |
| Max receipt-to-codec-output age | no limit | 850 ms |
| Consecutive old outputs before reconnect | never | 4 |

The default retains the previous nominal 48-AU buffer, 900ms offer
behavior, and smooth-oriented decoder setting; it adds a 1.8s
fail-fast backlog guard, so it is not literally a byte-for-byte old
implementation. Experimental settings are hypotheses and may cause
reconnects or stutter on a device with slower hardware decoders.

**Do not drop arbitrary queued H264 P-frames.** They reference earlier
pictures and silently skipping them may produce decoding artifacts.
When the compressed queue is stale or blocking, the player terminates
and reconnects, reacquiring a clean RTSP/H264 sequence. When a
picture has already passed MediaCodec decoding, it is safe to suppress
just that old output buffer (`releaseOutputBuffer(index,false)`) from
Surface display without breaking the compressed H264 reference chain.

This is a diagnostic playback option, **not a safety-critical feature**.

## Instrumentation: correctly scoped latency

Each access unit is stamped with Android `elapsedRealtime()` on
reassembly/receipt, before waiting on the preview queue. Immediately
before `queueInputBuffer`, the app records the
`presentationTimeUs → localReceiptMs` correlation in a bounded table
(96 in-flight, 128 recent samples). On the MediaCodec output callback,
`BufferInfo.presentationTimeUs` is matched back to the receipt time.

UI reports:
- Oldest currently waiting H264 AU age (ms).
- P95 and most recent **local H264 AU receipt → codec output callback**
  duration (ms).
- Matched and unmatched PTS count.
- Number of old decoded outputs suppressed by the opt-in mode.
- Age watchdog reconnects and RTP, H264, Surface counters.

The timing table never retains native images or buffers and is bounded.
PTS duplicate/reuse, missing metadata and timebase regressions do
not generate false latency numbers.

**This is NOT glass-to-glass latency**. It does not include exposure or
camera encoder, propagation to the local phone, nor actual Surface
scanout timing. A P95 of e.g. 20ms does **not** mean 20ms real-world
preview lag. The official 70mai 4.5.0 comparison remains **UNRESOLVED**
without A500S exposure-to-display experiments.

## Host CI evidence and limitations

Pure Kotlin tests check default/opt-in profile values, queue/codec age
limits and output suppression boundaries. PTS correlation unit tests
cover out-of-order codec output, missing PTS, duplicate/expired records,
clock regressions, bounded memory, 95th percentile and restart/reset.

Android GitHub Actions verifies Lite and AI APK compilation/native
packaging. A CI green check cannot establish a physical latency
improvement, uninterrupted A500S hardware video decoding, correct
YUV/ONNX image transforms, safety-ready distance, or xADAS parity.

The 20-item `PREDEVICE_90_GATE.md` remains under the 18/20 gate.
No owner trial is requested before the stated engineering criteria.
