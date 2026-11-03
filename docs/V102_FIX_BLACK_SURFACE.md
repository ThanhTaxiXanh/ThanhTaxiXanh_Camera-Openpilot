# TTX V1.0.2 — SurfaceView black frame fix

User report: uploaded video duration 18.8667 s, 512x1136, recorded at 30fps. TTX Direct RTP/UDP panel showed roughly 340 packets/s, ~24 AU/s, 27–29 decoded/rendered buffer callbacks per second, but content area was entirely black. Switching to IJK mode showed IJK first-frame event and 640x480 video dimensions, still black.

The new Lite MainActivity mistakenly applied `setBackgroundColor(Color.BLACK)` to the **SurfaceView itself**. SurfaceView's dedicated video Surface is normally behind the application's Window; a hole in the Window exposes it. The explicitly black View background can cover that hole while leaving MediaCodec and IJK decoder counters unaffected.

Fix: remove setBackgroundColor from SurfaceView; keep parent dark; explicitly `background=null`, set SurfaceHolder format `PixelFormat.OPAQUE`, and add CI guard against the exact regression.

Not changed: IJK AARs, arm64 filter, RTSP endpoint, Direct RTSP TCP/UDP/MediaCodec, no AI, small ZIP packaging.

Android official SurfaceView documentation: https://developer.android.com/reference/android/view/SurfaceView

The hypothesis is strongly supported by the simultaneous black result across two decoders, but must be verified on the physical Android phone. If the display remains black, next test is a one-shot PixelCopy of the underlying video Surface and a TextureView render alternative to distinguish image-content from composition issues.
