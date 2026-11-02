# V0.9.7 – xADAS RTP/UDP architecture, independent implementation

## 1. Verified from supplied xADAS 0.2.7 APK classes.dex
Method a5/l3.H (4484 Dalvik code units) includes:
- RTP/AVP;unicast;client_port= (UDP SETUP)
- RTP/AVP/TCP;unicast;interleaved=0-1 (TCP transport)
- DatagramSocket.<init>(I), setSoTimeout(I), setReceiveBufferSize(I), getLocalPort(), receive(DatagramPacket)
- RTSP socket initialization and keepalive, RTP-Info.
- Trace string "rtsp: cam answered TCP to a UDP SETUP, reading interleaved".
- Trace string "SETUP TCP rejected ...".
Method a5/j3.onOutputBufferAvailable uses MediaCodec.getOutputImage(index) and
MediaCodec.releaseOutputBuffer(index, boolean); the timestamp-ns scheduling overload is absent.
No packaged LibVLC native library was observed.

**Evidence boundary:** This confirms both UDP and TCP support in xADAS and MediaCodec
output handling. It does not prove every firmware/profile selects UDP or what jitter policy
the obfuscated code uses at runtime.

## 2. User A500S tests (03/11/2026)
- 800ms LibVLC + AI disabled was smooth.
- Direct TCP v0.9.5 and v0.9.6 stuttered standing and moving.
- 45.1 second v0.9.6 screen recording, phone screen 30fps, NOT camera fps.
- At sampled instants, the new camera-level AU/s estimate varied ~7–24, codec output
  similarly low, RTP gap=0, RTSP keepalive OK.
- This points upstream of (or at) decoder input pacing; TCP head-of-line blocking and/or
  RTP aggregation are hypotheses, not confirmed causes.

## 3. New experimental transport mode
- **Direct RTP/UDP**: network-bound UDP RTP/RTCP port pair with even/odd client ports,
  RTSP DESCRIBE/SETUP/PLAY and Session keepalive on TCP.
- Validate SETUP Transport response; if server chooses TCP interleaved, use that transport
  rather than accidentally listening to UDP.
- Feed complete H264 Annex-B access units to the existing MediaCodec engine.
- On UDP timeout or RTP cessation, use existing bounded auto-reconnect.
- TCP mode and proven VLC 800ms remain available as independent buttons.
- No MediaCodec output buffer retention, no PixelCopy when experimental AI is off.
- No unlicensed xADAS source/model/native binary is copied.

## 4. Validation requirements
1. Test parked: connect to A500S, ensure only one RTSP player active, tap
   70mai / DIRECT UDP, AI disabled.
2. Check SETUP response says camera chose RTP/UDP. If 461/no video, capture log;
   prefer VLC rather than guessing more camera endpoints.
3. When video plays, compare 2 minutes with Direct TCP under same stationary scene.
   Check received H264 AU/s, decoder feed/s, displayed-to-Surface/s, queue/48,
   RTP gaps, 20s session keepalive and reconnect counter.
4. Evaluate motion only if a passenger handles recording; driver must not operate phone.
5. If UDP increases RTP gaps, packet reordering/loss recovery needs work before relying
   on it. No ADAS warnings are enabled from this experimental path.

## 5. Open risks
Some camera firmware does not accept UDP SETUP or may choose TCP. UDP packets may arrive
out of order or be lost, especially in a moving car; RTP loss recovery, NAL corruption
and matching SPS/PPS/IDR still need testing. Build success only confirms compatibility
with source/tests; not playback quality.
