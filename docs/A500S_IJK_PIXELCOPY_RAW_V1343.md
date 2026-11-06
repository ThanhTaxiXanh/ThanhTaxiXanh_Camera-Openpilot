# V1.3.43 — opt-in IJK Surface PixelCopy → ARGB → temporal ONNX RAW

## Architecture and correct terminology

The existing IJK/FFmpeg + native MediaCodec video player decodes the A500S
H264 stream ONCE into its display Surface. This experimental tap requests a
bounded Android `PixelCopy` of that already-present `SurfaceView` into
640x480 `ARGB_8888`, then supplies two fresh ARGB arrays to the existing
`OpenpilotInferencePipeline` warp and 12-channel temporal packer.

**This is NOT a native FFmpeg decoded YUV callback.** Android Surface
readback may scale, composite, retain an older frame, use extra GPU
bandwidth, differ in color space from the camera decoder, and incur extra
latency. Repeated content cannot be distinguished from actual new video by
IJK's moving-average `videoOutputFramesPerSecond` alone.

This makes a diagnostic model input technically possible without competing
for a second H264 decoder, but DOES NOT meet #14 A500S native YUV→AI
or validate any xADAS-equivalent coordinate transformation.

## Safety and controls

- Existing IJK SMOOTH TCP primary behavior is unchanged. The additional
  `IJK → ARGB → ONNX: TẮT · PIXELCOPY THỬ NGHIỆM` button is hidden
  inside the Advanced panel until explicitly selected by the owner.
- A model is required in app-private storage, opened only on explicit
  opt-in in the AI flavor. Lite cannot import/run ONNX Runtime.
- Keep a single PixelCopy in flight; permit only 640x480 native IJK
  video, valid Surface, `isPlaying=true` and finite output FPS >2.
- Request spacing >=350ms. Reject copies older than 550ms. A temporal
  model pair requires fresh, increasing local request timestamps no more
  than 500ms apart; any gap, stop, PiP, reconnect, model-off or UI pause
  invalidates the current pair and in-flight ONNX results.
- Callback pixels are copied and processed on a dedicated HandlerThread.
  No network, decoder, preview or UI thread performs pixel readback or
  synchronous model inference.
- Bitmap bytes are transient; never saved to a file, ZIP, log or GitHub.
  Surface request times are local monotonic clocks, NOT sensor exposure.
- Raw model output is diagnostic only, with `calibration=null`. It never
  drives HUD lane drawing, metric lead distances, sound or braking.

## Test and remaining evidence

Deterministic JVM tests cover one-in-flight admission, video-source
identity/dimensions, bad native FPS, sampling cadence, failed/stale
PixelCopy callbacks, session revocation, ARGB pair temporal gaps,
worker rejection, source invalidation and no stale model delivery.
Static wiring checks protect default IJK and no second codec.

The following remain unverified: pixel readback availability on owner's
A500S handset, phone GPU overhead, frame originality and optical delay;
native color/stride parity; any actual trained model rights or Android
inference parity; calibrated A500S intrinsics and metric lead quality;
5-minute IJK continuity. The existing IJK 300s local TTXLIVE01 reports
local native player FPS but does not prove photon-to-glass delay.

**Gate A stays 8/20 = 40%; Gate B stays 0/5.** Do not promote #3,
#11, #14, #15, #16, #17 or #20 because CI is green or a synthetic
fixture paired into a 12-channel tensor.
