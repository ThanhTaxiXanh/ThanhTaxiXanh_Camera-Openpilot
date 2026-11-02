# V0.9.3 – Đồng bộ Direct RTSP/H264 với thời gian hình ảnh

## Bằng chứng từ video
Video quay màn hình người dùng dài 13,633 giây tại 512×1136, 30fps là FPS quay màn hình, không phải camera. Dòng Direct trên màn hình ghi khoảng 24–30 FPS giải mã và khung đưa tới Surface. Cảnh đường phố ban đêm ít chuyển động, chưa thể kết luận chính xác tỷ lệ mất khung chỉ từ khác biệt pixel.

## Phát hiện trong mã V0.9.2
- MediaCodec.releaseOutputBuffer(index,true) được gọi ngay khi decode xong thay vì lập lịch theo presentation timestamp.
- ArrayBlockingQueue(3) và frames.poll() khi đầy có thể bỏ compressed H264 P-frame, gây thiếu reference hình tiếp theo.
- feedCodec chờ sample trước rồi chờ input index; khi decoder đang chậm queue nhỏ dễ tràn.
- Codec low-latency được bật, dù người dùng ưu tiên mượt, không ưu tiên độ trễ thấp.

## Điều chỉnh V0.9.3
- Tạo H264PresentationClock: giữ anchor PTS, đặt mục tiêu 550ms cushion, khớp khoảng cách timestamp giữa các khung, không chạy theo nhịp callback decode.
- Handler của MediaCodec thực hiện releaseOutputBuffer(index,true) đúng thời điểm được tính; không phụ thuộc TextureView có tôn trọng renderTimestampNs hay không.
- Frame muộn trên 150ms bỏ ở OUTPUT (đã decode) để không làm hỏng chain reference; nếu lag vượt 850ms hoặc timestamp nhảy thì đặt lại mốc clock.
- Bounded queue 48 Access Units, chờ tối đa 900ms khi đầy; nếu vẫn đầy, báo rõ tình trạng quá tải và dừng stream thay vì bỏ ngẫu nhiên frame P.
- Tắt hint low-latency, ưu tiên phát ổn định. Thống kê decoded, delivered-to-Surface (không phải đo VSYNC), RTP gaps, late display drops, queue depth/peak, overloads và clock rebases.
- JUnit kiểm tra pacing khi decoder xuất khung theo burst, timestamp gián đoạn, frame đến trễ.

## Giới hạn
Cấu trúc này đã build chỉ chứng minh tương thích mã, không chứng minh phát mượt trên A500S. Đo actual displayed VSYNC cần thêm per-frame instrumentation. Nếu RTP có gap, quá tải decoder hoặc camera không cung cấp đều 25–30fps thì không thể khẳng định pacing tự giải quyết.

## Cách thử khi xe dừng
Kết nối A500S, chọn Direct MediaCodec và giữ AI tắt. Quan sát số RTP gap, frame trễ bỏ, hàng đợi và FPS giao Surface. Nếu giật vẫn xảy ra, gửi video mới kèm số liệu, sau đó thử VLC 800ms để so sánh.
