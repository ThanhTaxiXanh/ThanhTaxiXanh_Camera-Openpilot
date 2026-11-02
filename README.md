# TTX Camera AI

Ứng dụng Android nghiên cứu ADAS độc lập cho camera điện thoại và nguồn RTSP từ **70mai A500S**, applicationId **`vn.thanhtaxixanh.camera.ai`**.

## Tính năng ở bản MVP
- Camera sau điện thoại qua CameraX.
- Camera hành trình 70mai A500S / RTSP qua Media3; nhập URL hoặc tự thử các đường dẫn RTSP thông dụng khi đã ghép nối Wi-Fi hợp lệ.
- Giải mã H.264, lấy mẫu khung hình và phân tích vạch đường bằng OpenCV Canny + HoughLinesP.
- Hỗ trợ nhập mô hình phát hiện vật thể **TFLite Task Vision ObjectDetector** có metadata, nhãn car/truck/bus/motorcycle để hiển thị **ước tính** cự ly từ kích thước hình ảnh.
- Overlay làn đường, hiển thị trạng thái và chẩn đoán phiên.
- Xử lý offline, không upload video, không ghi hình ngầm.

## ⚠️ Giới hạn nghiêm trọng
**Đây là bản nguyên mẫu nghiên cứu, không phải hệ thống ADAS được kiểm định.** Kết quả phát hiện làn đường chỉ là ước tính dựa trên xử lý ảnh cổ điển; dễ sai với đường cong, bóng đổ, vạch mờ, mưa. Không có mô hình phát hiện xe kèm sẵn: chỉ khi chủ thiết bị chủ động nhập mô hình TFLite tương thích mới xuất hiện thông tin xe. Khoảng cách hình học dựa trên chiều rộng phương tiện giả định và FOV giả định, không thể xem là đo đạc chính xác. Không điều khiển vô lăng, phanh, ga hoặc kết nối CAN.

**Dữ liệu mô hình ONNX/NCNN và thư viện riêng lấy từ APK xADAS KHÔNG nằm trong repo** vì chưa có giấy phép tái phân phối. Tương tự, mã ứng dụng viết độc lập, không sao chép bytecode giải mã từ APK.

## Kết nối A500S
1. Dừng xe ở nơi an toàn; mở Wi-Fi trên A500S; sử dụng quy trình ghép nối/cho phép kết nối chính thức với thiết bị của chính bạn.
2. Trên điện thoại Android, kết nối đúng SSID Wi-Fi của 70mai (chấp nhận thông báo mạng không có Internet).
3. Mở ứng dụng, chọn **70mai/RTSP**; URL mặc định `rtsp://192.168.0.1:554/livestream/12`. Có thể thử `rtsp://192.168.0.1:554/livestream/0`, `rtsp://192.168.0.1:554/liveRTSP/av4`, `rtsp://192.72.1.1:554/liveRTSP/av4`. Các URL là ứng viên từ nghiên cứu tĩnh; **chưa xác nhận trực tiếp trên A500S của người dùng**.
4. Nếu vẫn báo lỗi: xác minh SSID, địa chỉ IP gateway, firmware camera và khả năng RTSP; không tìm cách bỏ qua xác thực của camera.
5. Nút **Nạp mô hình xe** cho phép chọn file .tflite theo định dạng Task Vision ObjectDetector có metadata, giấy phép cho phép sử dụng. Nếu không có model, app chỉ hiện kết quả làn đường.

## Build qua GitHub Actions, không cần máy tính
Mở **Actions → Build Android Debug APK → Run workflow**. Sau build thành công, mở run → Artifacts → `TTX-Camera-AI-debug-apk`. APK debug chưa được kiểm nghiệm trên thiết bị A500S và các cấu hình Android; kiểm tra Build trước.

## Cấu trúc
- `app/src/main/java/vn/thanhtaxixanh/camera/ai/MainActivity.kt` giao diện và luồng CameraX/RTSP.
- `app/src/main/java/vn/thanhtaxixanh/camera/ai/VisionEngine.kt` phân tích làn đường và TFLite tùy chọn.
- `app/src/main/java/vn/thanhtaxixanh/camera/ai/AdasOverlay.kt` lớp hiển thị.
- `docs/XADAS_ANALYSIS.md` kết quả nghiên cứu APK và giới hạn xác minh.

## Nguyên tắc
Không tự tải hoặc chép model xADAS vào bản build. Không dùng API đóng, không yêu cầu can thiệp firmware, không phá cơ chế ghép nối camera. Dành cho nghiên cứu và kiểm thử khi xe dừng, chỉ lái sau khi đã hoàn tất thiết lập.


## V0.3.1 · Cải tiến sau thử nghiệm thực tế ngày 02/11/2026

Video A500S đã chạy với LibVLC ở mạng `192.168.0.20`, gateway `192.168.0.1`.
SDP trả về `Content-Base: rtsp://192.168.0.1/00000011/`, `a=control:*,track1`, H.264/90000.

- Màn hình xem video chuyển lên phía trên, vùng hiển thị 16:9, bảng điều khiển cuộn bên dưới; chạm video để mở rộng/thu nhỏ.
- Lớp vẽ ADAS được căn theo FIT_CENTER (cả letterbox) và có dòng trạng thái vạch trái/phải.
- Hiển thị số khung hình đã xử lý để xác nhận pipeline PixelCopy → OpenCV đang chạy; không đồng nhất việc có video với việc AI nhận diện thành công.
- Phát hiện vạch theo Canny/Hough còn là thí nghiệm. Đo khoảng cách chưa hiệu chuẩn, không dùng đưa ra quyết định phanh/lái.


## V0.4.0 – Cài đặt ADAS (nghiên cứu)

Đã phân tích video xADAS 0.3.0 dài 152,67 giây (xem `docs/XADAS_VIDEO_0_3_0_REVIEW.md`). Nhấn **CÀI ĐẶT ADAS** để bật/tắt các chức năng HUD có thể áp dụng: vùng làn đường xanh, đường biên, thông tin xe (chỉ khi có model), mũi tên, làm tròn, đơn vị và ẩn thanh trạng thái. Ngưỡng cảnh báo, âm thanh, GPS, hiệu chỉnh, PiP và chia sẻ dữ liệu vẫn chưa có pipeline đủ tin cậy hoặc chưa triển khai; trong cài đặt nêu rõ chỉ lưu tham số hoặc mô tả kế hoạch, không giả làm tính năng hoạt động.


## V0.5.0 · Openpilot/Supercombo audit

Chi tiết báo cáo: [XADAS_DEEP_AUDIT_OPENPILOT_027.md](docs/XADAS_DEEP_AUDIT_OPENPILOT_027.md). Đã xác minh 11 head thuộc tensor 1576 giá trị, mean/std trong graph, có reference YUV temporal packer và JUnit tests. Chưa có model được cấp phép hoặc đủ warp/geometry decoder để công bố AI Openpilot hoạt động thực tế.


## V0.6.0 – Parser và mẫu ảnh A500S

Giải mã cấu trúc 4 lane/2 road edge/3 lead, mean/std và logits có JUnit tests. Nút **XUẤT 2 KHUNG HÌNH A500S (ZIP)** giúp lấy mẫu ảnh đang xem từ VLC khi xe dừng, lưu bằng trình chọn tệp Android mà không upload tự động. Xem [báo cáo V0.6](docs/OPENPILOT_V06_PARSER.md) và [hướng dẫn xuất ảnh](docs/V06_A500S_FRAMES.md). **Chưa có warp đã hiệu chuẩn, chưa được kích hoạt cảnh báo khoảng cách neural**.


## V0.7.0 – Ưu tiên độ mượt 70mai A500S

Tắt AI OpenCV mặc định để VLC phát video độc lập. Bật/tắt AI bằng nút rõ ràng, khi bật lấy mẫu 320×180 tối đa 1 FPS với PixelCopy callback trên HandlerThread riêng. Có hai preset bộ đệm RTSP 800ms và 300ms để so sánh; không tự reconnect khi VLC đã có video chỉ vì AI không có khung mới. Đọc [chẩn đoán hiệu năng](docs/V07_SMOOTH_PLAYBACK.md).


## V0.8.0 – Adaptive playback guard

- Bổ sung đo **FPS giải mã/hiển thị thực và số frame mất** qua thống kê native LibVLC (nếu thiết bị hỗ trợ).
- PixelCopy được cả khởi tạo và callback trên HandlerThread mức ưu tiên thấp, mặc định tối đa 0,5 Hz. OpenCV chạy riêng và đo thời gian xử lý.
- Nếu VLC mất từ 2 frame/chu kỳ 2 giây, PixelCopy quá 48 ms, hoặc OpenCV trên 100 ms thì AI tự giảm tải/tạm nghỉ 6–7 giây nhằm bảo vệ độ mượt.
- Đệm A500S giữ 800 ms mặc định; nút 300 ms chỉ để đối chiếu lỗi. Dự án cần đường YUV từ decoder để đạt AI fps cao mà không dùng PixelCopy.
- Kiểm tra khi xe dừng; các chỉ số này không chứng minh chất lượng ADAS.


## V0.9.0 – Bộ phát RTSP/MediaCodec trực tiếp (nghiên cứu xADAS)

Đã xác minh xADAS 0.2.7 đóng gói RTSP client riêng và MediaCodec hardware decode, **không có SDK/native LibVLC**. TTX có thêm nút **70MAI / DIRECT MEDIACODEC · THỬ NGHIỆM** chạy cơ chế độc lập RTP/H264 → MediaCodec → Surface, không dùng LibVLC/PixelCopy. Chế độ **70mai / VLC** (800ms) giữ nguyên như phương án ổn định đã kiểm chứng. Đọc [báo cáo kiểm tra playback xADAS](docs/V09_XADAS_PLAYBACK_AUDIT.md). AI Openpilot thật chưa kích hoạt, chế độ Direct chưa xác minh được trên A500S.


## V0.9.3 – Nhịp video Direct H264

Thêm H264PresentationClock đọc presentation timestamp để điều phối hiển thị qua HandlerThread (bắt đầu với đệm 550ms), tăng hàng đợi compressed H264 lên 48 khung và không tự ý bỏ P-frame. Thêm thống kê hàng đợi, frame trễ và RTP gap. Không thay đổi đường VLC 800ms. Xem tài liệu docs/V093_DIRECT_H264_PACING.md. Chưa xác minh độ mượt trên thiết bị thực.


## V0.9.4 – Duy trì phiên A500S và khôi phục Connection reset

Từ ảnh dùng V0.9.3: sau khi phát mượt, Direct RTSP báo Connection reset trong khi RTP gap=0, queue 0/48. Bản này bổ sung RTSP GET_PARAMETER lặp lại theo timeout từ Session SETUP (mặc định 60 giây, gửi tối đa mỗi 15 giây), theo dõi phản hồi và thử SET_PARAMETER nếu GET_PARAMETER bị từ chối. Nếu TCP đóng/RTP im lặng 12 giây, đóng MediaCodec cũ và mở phiên mới bằng DESCRIBE/SETUP/PLAY với URL gốc; giới hạn 6 lần thử với backoff tăng dần. Log gồm keepalive gửi/OK/bị từ chối, số lần reconnect. LibVLC 800ms vẫn giữ nguyên. Chưa xác nhận firmware A500S chấp nhận keepalive cho đến lần thử thực tế. Xem [chẩn đoán V0.9.4](docs/V094_A500S_KEEPALIVE_RECOVERY.md).


## V0.9.5 – Ưu tiên video mượt khi xe chạy

Video thử nghiệm A500S 48,07 giây khi di chuyển cho thấy Direct FPS giao Surface có lúc giảm còn ~15 dù RTP gap 0 và RTSP keepalive OK. Mã V0.9.4 giữ decoded output buffers tối đa ~550ms, có thể làm nghẽn MediaCodec. V0.9.5 **trả output buffer ngay lập tức** và đưa nhịp PTS sang thread nạp các Access Units, tách riêng khỏi luồng RTP/codec callback. `RtpInputPacer` dùng đệm khởi đầu 350ms và không rebuffer khi khung tới muộn, cho phép nhanh chóng bắt kịp các đợt RTP dồn. Đếm `frame nạp muộn` thay `frame trễ bỏ`; frames nén không bị loại tùy tiện. Chế độ RTSP keepalive và VLC 800ms không thay đổi. Kết quả còn cần kiểm tra trên điện thoại A500S thực tế.


## V0.9.6 – Khắc phục hồi quy Direct MediaCodec V0.9.5

Bộ phát Direct không còn chờ PTS trên thread nạp H264. Thay đổi này nhằm loại bỏ ràng buộc đồng hồ V0.9.5 khiến tốc độ nạp và tốc độ giải mã bị giảm; giữ nguyên thứ tự và PTS gốc, output buffers được release ngay như V0.9.5. Bổ sung bốn chỉ số riêng: Access Units H264 nhận từ camera/s, tốc độ nạp decoder/s, FPS decoded và FPS đưa tới Surface, để phân biệt lỗi firmware/RTP với codec. Không thay đổi RTSP GET_PARAMETER 100% thành công trên các ảnh người dùng, cũng không thay đổi VLC 800 ms. Xem [chẩn đoán V0.9.6](docs/V096_UNPACED_RTSP_ROLLBACK.md). Độ mượt chưa xác minh trên điện thoại.
