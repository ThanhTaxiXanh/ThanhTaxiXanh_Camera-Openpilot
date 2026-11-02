# Bóc tách xADAS 0.2.7 – Openpilot/Supercombo: kiểm toán chuyên sâu

Ngày: 02/11/2026. Đây là phân tích tĩnh tệp XAPK người dùng cung cấp, với kiểm tra ONNX protobuf, NCNN param và DEX method/string references. Không phải kiểm toán 100% runtime, không được hiểu là xác nhận độ an toàn thực tế.

## Nguồn và định danh

- Package: com.kaibatech.drivedeck.xadas; versionName 0.2.7; versionCode 44.
- Base APK SHA256: 52888da7b03ab5153b518cba178dfd98113ab818b934f34f48fa9a833da4f593
- DEX SHA256: e72126d42a20d5fa37a29ac5d6908f86d737b7278ef6b9c82456d7ba5e3058d4
- ONNX SHA256: 096488845e816bd59e7c639c1f861e7f43172ea62105d8ad09e3f60b81e3c5b7
- NCNN weights SHA256: 1cc36c1495ccd3ca25f2e49551a6439dc2ef1fffbb115293dcfe7eff7084eb24
- NCNN param SHA256: 46c5ed87401fe3a54d156a58a9c411a62932e0d56b181df523433423c2db7ed3
- DEX: 26.134 strings, 4.025 type refs, 26.773 method IDs, 22.244 method entries, khoảng 2.646 class entries.
- Model: 92.936.299 bytes ONNX, 92.232.032 bytes NCNN weights, 22.800 bytes NCNN param.

## Mô hình thị giác xác minh được

- ONNX graph có 264 nodes và 267 initializers.
- Hai tensor đầu vào float32: img và big_img, mỗi tensor [1,12,128,256].
- Một tensor đầu ra: vout_pass [1,1576].
- Bộ chuẩn hóa là hằng số bên trong graph: 24 kênh mean đều 127.5; std đều 63.75. Ảnh đầu vào phải ở thang 0..255, không chia 255 trước.
- Graph cuối có 11 nhánh concat theo thứ tự dưới; NCNN tương ứng có 273 layers, 316 blobs.

| Head từ tên trọng số ONNX | Offset (đầu bao gồm) | Length | End exclusive |
|---|---:|---:|---:|
| meta | 0 | 55 | 55 |
| desire_pred | 55 | 32 | 87 |
| pose | 87 | 12 | 99 |
| wide_from_device_euler | 99 | 6 | 105 |
| road_transform | 105 | 12 | 117 |
| lane_lines | 117 | 528 | 645 |
| lane_lines_prob | 645 | 8 | 653 |
| road_edges | 653 | 264 | 917 |
| lead | 917 | 144 | 1061 |
| lead_prob | 1061 | 3 | 1064 |
| summarizer_features (L2-normalized) | 1064 | 512 | 1576 |

Quan trọng: các vùng và tên head đã xác nhận từ ONNX cuối, nhưng thứ tự các thuộc tính con, đơn vị mỗi tọa độ và phép chọn lead vẫn cần kiểm chứng. Không thể lấy 144 float và tùy ý gọi một phần tử là khoảng cách mét.

## Mô hình, framework và các lớp trong bytecode

- Lb5/q0 (log OpenpilotLane): tải driving_vision_fp32.onnx / NCNN param, xử lý geometry, temporal inputs, infer và trả về lane/lead/pose/road transform.
- Lb5/p (log FramePipeline): nhận RGBA/YUV, enqueue, điều khiển inference, calibration, atomic state, frame time, bỏ frame chậm, thermal và backend fallback.
- Lcom/xadas/app/cv/NcnnVisionEngine: JNI nativeInit/nativeRun/nativeClose, kiểm tra GPU/FP16 và input parity, phân biệt in0=img và in1=big_img.
- Lb5/c1 = SupercomboResult; Lb5/d1 = road transform; Lb5/b1 = pose; Lb5/e0 = lead state; Lb5/y = lead debug; Lb5/r0 = PipelineState.
- ONNX Runtime và cấu hình XNNPACK được ghi nhận; NCNN có cờ experimental_ncnn, GPU compatibility, fp16 và fallback về backend khác.

## Tiền xử lý 12 kênh

- Code có dấu hiệu YUV direct, YUV direct x2 và RGBA.
- Hình được warp theo intrinsics/độ nghiêng camera, sau đó đóng gói thành 6 kênh YUV420 cho mỗi khung và 12 kênh cho một cặp khung là cấu trúc phù hợp mã nguồn mở Openpilot. Thứ tự pixel và ma trận warp trong xADAS chưa được đo parity.
- Log temporal pair báo khung cách nhau ít hơn mức tối thiểu; thông số pairMinDtMs=120.
- Dòng big_img nhận camera ultrawide hoặc virtual warp, có log warpUMean và tình huống ma trận suy biến.
- Không được áp dụng đơn thuần resize ảnh 70mai lên model vì sai distortion, focal/pitch/yaw và hình học khung nhìn.
- TTX có bộ OpenpilotYuvPacker tham khảo ở thang 0..255, không chạy model và không được tuyên bố tương đương đầu vào xADAS cho đến khi có golden frame.

## Hậu xử lý, confidence, calibration

- Chuỗi biểu diễn TuningParams chứa leadMinProb 0.4, leadMaxXStd 4.0, leadRelXStd 0.22, leadMaxValidX 150.0; laneMinProb 0.3, laneOuterProb 0.2, maxDrawM 80, pairMinDtMs 120, pitch/yaw override NaN, focal override 0.
- Các giá trị này là dấu vết cấu hình, không khẳng định là mặc định trong tất cả phiên chạy.
- Log có lead probability, x(m), y(m), v(m/s), a(m/s2), xStd; có laneNaN, laneMaxAbs, lane confidences; ám chỉ sanity gates và uncertainty.
- Có FocalCalibrationSnapshot, PitchCalibrationSnapshot, learned_focal_ratio, learned_fov_deg, auto-calib pitch/yaw, learned_focal_calibrated, và lane/lead age.
- Bộ giải mã biến 528/144 float thành tọa độ/phương tiện với kiểm định thời gian và độ tin cậy vẫn chưa được xây dựng lại và không được giả lập số mét.

## Kết nối camera đã xác minh

- RTSP_70MAI_CGI / HISI / MSTAR; handshake/heartbeat/discovery trong DEX.
- Các đường Vietmap, AZCAM và USB UVC dùng libUVCCamera, libuvc, libusb1.0; TTX chưa có các adapter này.
- Trên A500S người dùng: Wi-Fi IP 192.168.0.20, gateway 192.168.0.1, DESCRIBE 200 đối với rtsp://192.168.0.1:554/livestream/12, dynamic Content-Base rtsp://192.168.0.1/00000011/, a=control:*,track1, H264/90000.
- Media3 PLAY 404; phiên sau phát được bằng LibVLC. Giữ LibVLC làm adapter chính.

## Các nhóm chưa được tái tạo đầy đủ

| Module xADAS | Trạng thái TTX |
|---|---|
| CameraX điện thoại | Có |
| RTSP 70mai A500S | Có LibVLC, đã thử thực tế |
| Nhiều dòng RTSP | Chưa đủ profile |
| USB UVC | Chưa triển khai |
| ONNX 1.576 giá trị, head decoder | Có hợp đồng tensor, chưa suy luận |
| NCNN JNI/GPU/parity | Chưa triển khai |
| Pairing YUV temporal | Packer tham khảo, cần parity |
| Warp camera intrinsics/ultrawide | Chưa triển khai |
| Auto pitch/yaw/focal | Chưa triển khai |
| Lane/road edge neural | Chưa triển khai |
| Lead hypothesis + uncertainty | Chưa triển khai |
| GPS speed/signal accuracy | Chưa triển khai |
| Distance risk/chime | Chưa bật, vì chưa đo được đúng |
| HUD theo độ cũ của dữ liệu | Có hạn stale 1,8 giây |
| PiP/cửa sổ nổi/chạy nền | Chưa triển khai |
| Recording/replay/diagnostic regression | Chưa triển khai |
| Billing/Pro | Không sao chép entitlement |

## Tình trạng TTX V0.5.0

- Bổ sung hợp đồng tensor có kiểm tra 11 head và kích thước.
- Bổ sung YUV420 packer nghiên cứu với 2 frame và test; chưa cắm vào model runtime.
- UI có nút báo cáo Openpilot và thông báo rõ chưa có mô hình được cấp phép.
- Overlay tự ẩn hình học và khoảng cách khi kết quả quá cũ.
- GitHub Actions chạy unit tests rồi assemble APK.

## Giới hạn pháp lý và an toàn

Tệp điều khoản xADAS ghi rõ tài sản phần mềm/nhãn hiệu không chuyển quyền sở hữu. MIT license của kho openpilot công khai không tự động chứng minh Kaibatech cho phép phát hành lại ONNX/NCNN/JNI riêng từ ứng dụng xADAS. Không đưa model và mã nhị phân xADAS vào repo công khai, không bỏ khóa hoặc can thiệp xác thực trả phí. Dự án TTX là tái triển khai độc lập, không được tuyên bố phát hiện khoảng cách chính xác cho tới khi có dữ liệu chuẩn và phép hiệu chỉnh.

Nguồn đối chiếu: https://github.com/commaai/openpilot ; https://docs.comma.ai ; https://developer.android.com/media/media3/exoplayer/rtsp .
