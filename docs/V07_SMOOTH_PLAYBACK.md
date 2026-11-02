# TTX Camera AI V0.7.0 – Chẩn đoán và cải thiện độ mượt
Kiểm tra từ video người dùng dài 52.03 giây, video quay màn hình 512x1136 ở 30 FPS (đây là **FPS của quay màn hình, không phải RTSP**) và tệp ZIP chứa hai ảnh JPEG 640x360 lấy cách nhau 359 ms.

## Bằng chứng
- Hai JPEG trong ZIP trùng SHA-256 hoàn toàn, nhưng vì ảnh được chụp khi xe có thể dừng, **không đủ kết luận camera đã đóng băng**.
- Mã V0.6 watchdog gọi PixelCopy 640x360 mỗi 500 ms với callback Handler main/UI, sau đó OpenCV/TFLite được chạy trên worker. Việc lấy mẫu từ surface và UI callback có thể góp phần gây giật giao diện; chưa có phép đo profiler trên máy người dùng để chứng minh nguyên nhân duy nhất.
- Số đếm "AI: N khung" chỉ là lượng frame đã phân tích, không đo FPS H264.
- V0.6 dùng thành công LibVLC RTP-over-RTSP/TCP với 70mai A500S; không quay lại Media3 PLAY 404.

## Các thay đổi
1. **Chế độ VIDEO trước:** mặc định AI Canny/Hough tắt. Không dùng PixelCopy trong chế độ này và giữ VLC hiển thị độc lập.
2. Nút **AI THỬ NGHIỆM**: khi bật, PixelCopy tối đa 1 lần/giây và giảm ảnh từ 640x360 còn 320x180; phần hình học không đại diện cho Openpilot.
3. PixelCopy callback dùng **HandlerThread** riêng, không dùng main/UI Handler. PixelCopy là bất đồng bộ, lấy buffer SurfaceView gần nhất; không thể bảo đảm hai bản copy cách nhau 359 ms là hai frame video khác nhau.
4. Watchdog **không tự động reconnect** khi VLC đã báo có hình chỉ vì PixelCopy/AI không cập nhật. Lỗi pipeline xử lý không được phá luồng RTSP đã phát thành công.
5. Đệm VLC/TCP mặc định 800 ms; có nút chuyển 300 ms để so sánh độ trễ. Cache cao có thể giảm giật do jitter mạng nhưng tăng độ trễ; cần đánh giá trên thiết bị.
6. Thử bật các tùy chọn thả khung hình trễ của LibVLC, ưu tiên phát video trước AI.
7. ZIP xuất khung hình bổ sung cờ "identical_encoded_frames" giúp diễn giải dữ liệu tĩnh đúng.

## Phương pháp so sánh trên điện thoại
- Khi xe dừng và camera đã kết nối: bật "VIDEO AI TẮT", thử RTSP 800 ms 30-60 giây.
- Giữ cùng camera, cùng đường/phong cảnh, thử RTSP 300 ms để đối chiếu độ trễ/giật.
- Sau đó bật AI thử nghiệm: xem video có giật tăng không. Kết quả không dùng để lái/phanh.
- Nếu tắt AI vẫn giật, ưu tiên thu thập codec/decoder stats (decoded/dropped video frames), nhiệt CPU và RTT/Wi-Fi; không suy luận do OpenCV.
- Chế độ đệm cao có thể có độ trễ đáng kể. Chưa có kiểm thử latency/fps trực tiếp trên máy Android của người dùng.
