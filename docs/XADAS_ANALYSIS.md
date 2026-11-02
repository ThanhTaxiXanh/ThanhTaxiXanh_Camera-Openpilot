# Báo cáo phân tích tĩnh xADAS 0.2.7 (02-11-2026)

## Mẫu
- Đầu vào: XAPK APKPure `xADAS_+Cảnh+báo+khoảng+cách+AI_0.2.7_APKPure.xapk`.
- Manifest XAPK: package `com.kaibatech.drivedeck.xadas`; versionName `0.2.7`, code `44`; minSdk 29; target 36.
- Base APK SHA-256: `52888da7b03ab5153b518cba178dfd98113ab818b934f34f48fa9a833da4f593`.
- Split arm64 SHA-256: `53c2d4d74c38a7a99d107d1b3168f940c66395b5cfbbaa616df6fb3259599830`.
- Phương pháp: kiểm kê ZIP, parse DEX string/method ID và string xrefs gần đúng, đọc symbol JNI, parse cấu trúc ONNX và NCNN, đọc điều khoản. **Chưa chạy app và chưa có packet capture thực từ A500S**.

## Thành phần đã xác nhận
- `classes.dex`: 26.134 chuỗi, 4.025 type, 26.773 method IDs.
- Assets `driving_vision_fp32.onnx` (92.936.299 byte), `driving_vision_ncnn.bin` (92.232.032 byte), `driving_vision_ncnn.param` (22.800 byte).
- JNI `Java_com_xadas_app_cv_NcnnVisionEngine_nativeInit`, `nativeRun`, `nativeClose`; thư viện ONNX Runtime, OpenCV, UVC.
- ONNX: inputs `img` và `big_img` (mỗi input `[1,12,128,256]`); output `vout_pass` (`[1,1576]`); 264 nodes và 267 initializers.
- NCNN param: 273 layers, 316 blobs. Việc so sánh đầu ra hai backend chưa được thử.
- Định danh trong DEX: `OpenpilotLane`, `SupercomboResult`, `LeadState`, `LaneCurve`, `TuningParams`.
- Chuỗi `tune_lead_min_prob`, `tune_lead_max_x_std`, `tune_lead_rel_x_std`, `tune_lead_max_valid_x`, `tune_lane_min_prob`, `tune_lane_outer_prob`.
- DEX có mặc định được biểu diễn trong chuỗi `TuningParams(leadMinProb=0.4, leadMaxXStd=4.0, leadRelXStd=0.22, leadMaxValidX=150.0, laneMinProb=0.3, laneOuterProb=0.2, maxDrawM=80.0,...)`. Đây là tham số debug/toString, cần xác minh áp dụng thực tế theo cấu hình chạy.
- Tên model + URL GitHub của openpilot xuất hiện, nhưng **không chứng minh mô hình là phiên bản openpilot được cấp phép để phân phối**.

## Camera / RTSP
- Các nhóm `RTSP_70MAI_CGI`, `RTSP_70MAI_HISI`, `RTSP_70MAI_MSTAR`.
- Các marker `70mai-handshake`, `70mai-heartbeat`, `RtspDiscovery`, `RtspCameraSource`.
- URL candidates/host literals: `192.168.0.1`, `192.72.1.1`, `192.168.1.1`, `192.168.1.254`; paths `/livestream/12`, `/livestream/0`, `/liveRTSP/av4`, `/liveRTSP/av2`, `/liveRTSP/av1`, `/liveRTSP/v1`.
- Có dấu hiệu RTSP OPTIONS/DESCRIBE/SETUP/PLAY, UDP và RTP-over-RTSP TCP, codec MediaCodec, tái khởi động phiên và reconnect.
- Chuỗi CGI: `/Config.cgi?action=`, `/cgi-bin/client.cgi?&-operation=`, `Camera.Preview.RTSP.av`, `getcamchnl.cgi`, `getcapability.cgi`.
- **Không xác thực** chính xác tổ hợp lệnh/giá trị CGI cho A500S hoặc quyền tương tác nếu chưa ghép nối. Bản TTX chỉ thử các endpoint RTSP trên thiết bị người dùng đã ghép nối hợp lệ.

## Phân biệt xADAS và dự án mới
xADAS sở hữu các mô hình/thư viện độc quyền hoặc giấy phép chưa rõ; không copy/repackage. TTX Camera AI là phiên bản **clean-room research MVP**, lấy cảm hứng ở cấp độ chức năng và dùng SDK nguồn mở. Điểm thiếu: chưa có bộ giải mã đầu ra 1576 giá trị tương đương và chưa kiểm định A500S; chưa có hiệu chỉnh metric distance chuẩn, theo dõi lead, TTC hay cảnh báo được kiểm định. Không công bố tính năng lái tự động.

## Nguồn tham khảo công khai
- Openpilot: https://github.com/commaai/openpilot
- Openpilot docs: https://docs.comma.ai
- 70mai A500S: https://www.70mai.com/vn/a500s/
- A500S livestream trao đổi cộng đồng: https://4pda.to/forum/index.php?showtopic=1018592
- Media3 RTSP: https://developer.android.com/media/media3/exoplayer/rtsp
