# V0.9.4 – A500S Connection reset và RTSP session liveness

## Bằng chứng thực tế
Ảnh người dùng 02/11/2026: Direct đã phát ổn định hơn V0.9.2 nhưng sau đó dừng hình và hiện "Direct RTSP: Connection reset". Wi-Fi 192.168.0.20 / gateway 192.168.0.1. DESCRIBE 200; SETUP 200; SDP H264/90000; dynamic Content-Base /00000039/. Tại thời điểm ngừng: decoded/delivered FPS=0; RTP gap=0; queue=0/48; late-display=3; clock rebases=2. RTP gap=0 chỉ thể hiện không có nhảy thứ tự gói được quan sát; không chứng minh mạng Wi-Fi hoàn hảo.

## Giả thuyết và sửa lỗi
- RTP/RTSP server có thể reset socket do session timeout/heartbeat thiếu, firmware hay mạng Wi-Fi. Không có packet capture, do đó nguyên nhân chính xác chưa kết luận.
- RTSP/1.0 RFC 2326 cho phép Session: <id>;timeout=<seconds>, mặc định 60s. GET_PARAMETER thường dùng duy trì phiên RTSP, còn Live555 mặc định xử lý GET_PARAMETER như keepalive.
- V0.9.3 không có RTSP keepalive khi stream đang chạy. V0.9.4 gửi GET_PARAMETER định kỳ trên chính socket sau PLAY; Session ID lấy từ SETUP. Khoảng gửi là min(timeout/3, 15 giây), giới hạn 5–15 giây. Nếu GET_PARAMETER trả 405/501 hoặc mã lỗi thì thử SET_PARAMETER. Không tự cắt video khi keepalive chưa trả lời.
- Socket có một thread đọc và một thread ghi cùng luồng run() để tránh rối phản hồi RTP. RtspWireReader vẫn phân biệt RTP dạng '$' và RTSP reply.
- Nếu socket mất/EOF/MediaCodec lỗi, đóng decoder, giải phóng network và thực hiện clean reconnect với DESCRIBE/SETUP/PLAY, nhận session và dynamic Content-Base mới. 6 lần tối đa, các khoảng 1,2–12 giây; thành công liên tục trên 45 giây mới đặt lại số lần thử.
- Khi có video nhưng RTP không cập nhật trên 12 giây, coi là stream stall và reconnect.
- Không thay đổi buffer H264/PTS đã cải thiện độ mượt. Không kích hoạt AI khi đang thử RTSP.
- Không ghi lại video, không gửi dữ liệu ra máy chủ bên ngoài. Mọi kết nối RTSP được giới hạn vào Wi-Fi camera ghép nối.

## Kiểm thử
1. Đỗ xe, kết nối Wi-Fi A500S.
2. Chọn Direct MediaCodec với AI tắt. Quan sát tới sau mốc 15 giây và xem keepalive gửi/OK.
3. Nếu camera không hỗ trợ GET_PARAMETER, log sẽ thấy mã từ chối và thử SET_PARAMETER.
4. Nếu Connection reset tiếp tục xảy ra, xem số lần reconnect và thời gian mất hình.
5. Nếu kết nối lại được nhưng giật sau một lúc, ghi video màn hình có thống kê FPS, RTP gap, queue và thông báo session.
6. Đối chứng 70mai/VLC 800ms vẫn là chế độ đã được xác nhận hoạt động mượt khi AI tắt.

## Giới hạn
Các tính năng hỗ trợ lái/đo khoảng cách chưa đủ hiệu chuẩn; không dựa vào kết quả ADAS thử nghiệm để điều khiển xe. Keepalive và reconnect cần xác minh trên firmware thực tế, build xanh không đủ chứng minh ổn định.

References: https://www.rfc-editor.org/rfc/rfc2326.html; https://github.com/che0/live555/blob/master/liveMedia/RTSPServer.cpp
