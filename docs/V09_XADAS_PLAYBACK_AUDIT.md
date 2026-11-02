# xADAS 0.2.7: video playback architecture vs TTX

## Evidence from exact XAPK
- APK package com.kaibatech.drivedeck.xadas, version 0.2.7 / 44.
- Extracted 12 arm64 native .so files: libUVCCamera.so, libc++_shared.so, libimage_processing_util_jni.so, libjpeg-turbo212.so, libonnxruntime.so, libonnxruntime4j_jni.so, libopencv_java4.so, libsurface_util_jni.so, libusb1.0.so, libuvc.so, libxadas_ncnn.so, libxadas_usb.so, libyuv.so (some filename counts in prior report excluded one; see exact listing).
- Neither native libvlc nor VLC Android SDK types (org/videolan/) were found. A generic string "VLC" alone exists in classes.dex, so absolute proof of never loading VLC dynamically would require runtime profiling; **packaged RTSP implementation does not use LibVLC**.
- AndroidX Media3/ExoPlayer class names not present in DEX for xADAS 0.2.7.

## Direct method evidence
- La5/l3; -> H() : RTSP OPTIONS/DESCRIBE/SETUP/PLAY, header Content-Base, RTP TCP interleaved 0-1 / UDP client_port, RTP-Info, network-bound sockets, KEEPALIVE.
- La5/y2; -> run() : MediaCodec.createDecoderByType(video/avc or video/hevc), MediaFormat.createVideoFormat, csd-0/csd-1 (SPS/PPS), low-latency, priority, operating-rate, vendor-specific display delay, codec callback, configure(surface), start().
- La5/j3; -> onOutputBufferAvailable(MediaCodec,index,BufferInfo) : getOutputImage(index), image planes/row stride/chroma handling, frame dispatch, releaseOutputBuffer(index,render).
- La5/l3; -> j(Image,...): reads Image.planes and native ByteBuffers; handles YUV chroma order.
- La5/l3; -> u(Surface,ByteBuffer,...), plus FramePipeline Lb5/p: sends decoded frames to vision engine without PixelCopy being the primary video bridge.
- Lcom/xadas/app/MainActivity; -> j0/l0: TextureView SurfaceTexture -> Surface target.
- No documented openpilot policy model bundle is needed merely to play camera video.

## Correct conclusion
xADAS uses a custom RTSP implementation and Android hardware MediaCodec decode, with output to Surface and opportunistic output Image YUV -> AI. This can avoid the GPU readback latency caused by TTX PixelCopy.

## TTX V0.9 experimental mode
- Adds DirectRtspPlayer.kt, a clean-room RTSP/TCP + H264 RTP depacketizer, MediaCodec.Callback, Surface output, and optional Y-only sampling from getOutputImage (if device decoder permits surface+image retrieval).
- Adds button "70MAI / DIRECT MEDIACODEC · THỬ NGHIỆM" beside existing 70mai/VLC and Media3 fallback, preserving proven 800ms VLC mode.
- A500S RTSP path still discovered via gateway; checks dynamic SDP Content-Base, track1 and aggregate PLAY.
- Supports H264 RFC6184 packet types single NAL, STAP-A and FU-A; no H265/MJPEG, audio, UDP, advanced authentication or RTSP keepalive in this initial prototype.
- **Caution**: Live555 A500S may send control responses interleaved within TCP and custom decoder may not support surface + getOutputImage. When YUV unavailable, keep video running and do not claim AI works. Requires testing on actual Android.
- The OpenCV lane estimator is still a research demo, not the Openpilot model. No unlicensed xADAS weights/JNI are copied.

## Validation
1. Test with car parked, connect Wi-Fi A500S and press DIRECT MEDIACODEC.
2. Confirm "RTSP H264 PLAY 200" and "MediaCodec" + native decoder details; observe decoded/rendered FPS.
3. Compare with 70mai / VLC at 800ms. If direct fails, VLC remains untouched; screenshot the new diagnostics.
4. If direct works, enable AI and check "YUV AI" counter; if unavailable the decoder does not support getOutputImage with display surface and a different YUV fanout backend will be required.
5. Never use lane/distance research labels for driving decisions.

## Limits
Static DEX method-call and string analysis verifies packaged implementation mechanisms; it cannot prove runtime scheduling, exact CPU load or AI/GUI FPS on user's phone.
