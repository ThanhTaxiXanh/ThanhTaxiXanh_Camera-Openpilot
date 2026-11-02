# Hiệu năng V0.8.0 – Giữ video A500S mượt khi bật AI

## Kết quả thực nghiệm từ người dùng (02/11/2026)
Video màn hình 102,43 giây, 512x1136 và 30fps quay màn hình, không phải FPS RTSP.
Ba trường hợp:
- VLC buffer 800 ms, tắt AI: phát mượt.
- VLC buffer 300 ms, tắt AI: giật.
- VLC buffer 800 ms, bật AI: giật.
Phép so sánh biến thiên ảnh trên ROI video lấy mẫu 5Hz: 0–60 giây có 0% đoạn 200ms gần như đứng yên (<0,5 mức xám trung bình), trong khi 60–80 giây khoảng 33%, 80–102 giây khoảng 26%. Các con số chỉ là dấu hiệu khựng trên bản ghi màn hình, chưa đo số khung bị mất bởi decoder.

## Phân tích
Đường LibVLC RTP/TCP → hardware decoder → SurfaceView hiển thị đang hoạt động khi AI tắt.
Ở bản V0.7, AI sử dụng PixelCopy từ SurfaceView vào Bitmap 320x180 rồi OpenCV trên background worker. Dù callback đã rời main, lệnh GPU readback có thể đồng bộ với video compositor hoặc tăng tải CPU/GPU, gây khựng. Không có profiler chứng minh chỉ PixelCopy gây lỗi; cần native frame stats.
Đệm 300ms yếu hơn 800ms trên kết nối Wi-Fi A500S thực, vì vậy mặc định giữ 800ms.

## Bản V0.8
- Đọc LibVLC Media.Stats.decodedVideo, displayedPictures, lostPictures định kỳ 2 giây. Không đồng nhất những giá trị này với FPS quay màn hình hay FPS AI.
- PixelCopy.request được khởi tạo lẫn callback ở HandlerThread riêng thấp ưu tiên; lấy mẫu tối đa 1 mỗi 2 giây.
- Bảo vệ video: nếu số hình mất >=2 trong chu kỳ khi AI bật, pause readback 6,5 giây; nếu một lần PixelCopy mất trên 48ms, pause 7s; OpenCV trên 100ms, pause 6s. Khoảng nghỉ và số lần giảm tải hiển thị rõ.
- Không tự restart stream VLC đang có hình khi AI không cập nhật.
- Đây là giải pháp hạn chế tải, chưa đảm bảo mọi thiết bị hết giật.

## Lộ trình kỹ thuật
Để AI thực 5–20fps và video không giật, cần một đường nhận YUV từ decoder/VLC native (JNI, MediaCodec hoặc custom RTSP) cùng bộ đệm bounded/latest-only và đồng bộ timestamp, thay vì readback SurfaceView. Chỉ triển khai sau thử nghiệm kỹ thuật kiểm soát buffer/decoder.
