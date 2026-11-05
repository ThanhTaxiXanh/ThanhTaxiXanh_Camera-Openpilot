# V1.3.20 — Camera2 sensor-time freshness for diagnostic RAW ONNX

## Defect

PhoneCameraActivity used `SystemClock.elapsedRealtime()` *when an
ImageReader callback ran* as the temporal timestamp of every YUV frame.
Callbacks may be delayed by an Android handler backlog or decoder load:
old sensor images then appeared to have age zero, and
OpenpilotInferencePipeline.PairSample inherited the callback timestamp.
This could create a false "fresh" ONNX pair, contaminate the continuity
checks, and misreport source-to-model timing.

## Clock-domain proof, not assumptions

Android camera SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME explicitly reports
that image timestamps share the `elapsedRealtimeNanos` boot clock. An
UNKNOWN sensor timestamp source cannot safely be compared to that clock
and must not be guessed or "freshened" at callback receipt.

PhoneCameraActivity now:

1. Reads CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE for the
   selected camera *before* opening CameraDevice.
2. Keeps phone preview and YUV ImageReader separate from the RAW ONNX
   diagnostic. With UNKNOWN timestamp source, camera preview may run
   but RAW ONNX cannot be activated.
3. For REALTIME sources, validates Image.timestamp against
   SystemClock.elapsedRealtimeNanos(), at most 550 ms old, not from
   the future, strictly newer than the last accepted sensor image.
4. On stale, unknown, duplicate or impossible clocks, breaks the
   temporal pair and invalidates pending model work rather than
   passing an invented capture instant downstream.
5. Emits a PairSample using the sensor capture timestamp; callback
   receipt time is used ONLY to throttle CPU copies, never as exposure.
6. Clears the gate when the owner Camera2 session closes/restarts.

Ten deterministic JVM tests exercise native clock source UNKNOWN,
callback delay, 550 ms boundary, out-of-order frames, clock faults,
pair break/recovery, reset and bounds.

## Evidence boundary

This is a real correctness fix in Android Camera2 source code, with
host-only deterministic tests. The tests do NOT run Camera2 hardware,
validate 640x480 concurrent preview/YUV support, establish physical
sensor-source accuracy, or demonstrate model output correctness.
Criterion #8 stays CODED until actual device/Camera2 proof is reviewed.
Mandatory A500S gates #14–17/#20 remain unverified. The official ledger
still reports **Gate A 7/20 (35%), Gate B 0/5**.

No native model weights, private images or model-derived warnings are
included. RAW inference remains diagnostic only, independent of driving.
