# V1.3.15 — YUV420 crop phase and native buffer safety

## Correctness defect

Android MediaCodec Image.cropRect may start at odd left/top. The existing U/V plane base offset uses floor(crop.left/2), but the ARGB converter used col/2 from the cropped origin. For crop left=1, visible columns 0,1,2,3 must map to chroma columns 0,1,1,2; the old mapping was 0,0,1,1. Both left and top crop phases are now preserved in Yuv420FrameConverter.Frame.

The old reader called ByteBuffer.duplicate().clear(), which set limit to capacity. The new pure JVM YuvPlaneSnapshot copies only the required valid byte span and rejects any cropped plane outside the native ByteBuffer.limit. Long offsets avoid integer stride overflow. Default zero-phase images and all existing call sites remain compatible.

Nine synthetic tests exercise odd horizontal/vertical crops, even crop parity, stride/pixelStride, invalid source limits, untouched native buffer position, and invalid layouts.

This does not prove actual A500S MediaCodec images, warp geometry, ONNX model correctness or 1s live latency. Mandatory #14–17/#20 remain NOT PASS. Gate 7/20 = 35%.
