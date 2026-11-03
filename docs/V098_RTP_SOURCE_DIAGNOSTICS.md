# V0.9.8 – Đo RTP gốc để xác định nguyên nhân giật A500S

Ngày 03/11/2026. Nguồn: XAPK xADAS 0.2.7 do người dùng cung cấp + video TTX V0.9.7 mới.

## Đã quan sát trên video mới
- Android screen recording 31.633 s, 512x1136, 30fps của màn hình, không phải camera.
- Direct RTP/UDP khi xe đang di chuyển: 0s ~21.9 AU/s; 6s 8.9; 9s 8.4; 12s 20.2; 18s 18.8; 27s 14.8.
- RTSP keepalive 200/OK và RTP gap 0 tại các mốc; không chứng minh source media đều. Video chứa các khoảng nhìn gần như không đổi kéo dài khoảng một giây.
- Người dùng cũng nói VLC với AI tắt giật khi xe di chuyển; nguyên nhân có thể bao gồm camera encode FPS biến động, độ phức tạp cảnh tăng bitrate, Wi-Fi, camera producer, hoặc cách render video. Không được suy luận chắc chỉ do TTX decoder.

## Chứng cứ bổ sung từ xADAS classes.dex
- La5/l3.H() sử dụng RTSP OPTIONS/DESCRIBE/SETUP/PLAY, UDP và TCP, có chuỗi "rtsp: RTCP receiver reports every 5 s on channel ".
- La5/k0.run() có chuỗi "client.cgi", "register[", "getcapability.cgi", "handshake: heartbeat uses".
- La5/q0.run() có "heartbeat: same reply".
- La5/l3.H() có "rtsp: no video without camera commands, sending them again".
Các chuỗi xác nhận có tính năng kiểm soát/đăng ký camera chuyên biệt, nhưng chưa cho phép kết luận CGI nào phải gửi cho A500S này. Không tự ý thêm lệnh CGI chưa được kiểm chứng để tránh làm gián đoạn camera.

## TTX V0.9.8
1. Quan sát trực tiếp RTP packet rates, marker bit và RTP timestamp changes, số byte video, SSRC, packet sequence gaps, jitter estimate.
2. So sánh với số complete H264 Annex-B AU/s đã đưa vào MediaCodec, số AU bị loại vì thiếu packet/unsupported NAL.
3. Báo cáo RTCP Receiver Report và SDES CNAME không chứa dữ liệu cá nhân, trên UDP/RTCP port negotiated từ RTSP Transport hoặc TCP interleaved RTCP channel, 5 giây/lần, chỉ khi đã thấy RTP.
4. Unit test synthetic RTP timestamps, wrap/gap, compound RTCP packet format.
5. Giữ RTP/TCP, RTP/UDP, VLC và RTSP keepalive đang hoạt động. Không tự upload video hay thay đổi firmware.

## Cách diễn giải
- Marker/s và timestamp changes/s đều thấp ~8–12: camera gửi ít khung/giây hoặc có bất thường ở packet receiver; đối chiếu 70mai app/xADAS và kiểm tra gói mạng.
- Marker/s gần 25–30, nhưng AU/s chỉ 8–12: lỗi nằm ở RTP->H264 assembler hoặc các packet bị loại (xem AU hỏng, RTP gap).
- AU/s gần 30, feed hoặc decoded/Surface chỉ ~10: nghẽn decoder/Surface hoặc vendor codec.
- Bitrate Mbps tăng mạnh đúng lúc video giật: nghiên cứu giới hạn Wi-Fi/kích thước frame và biến động mã hóa, không hạ chất lượng tùy tiện.
- RTP gap 0 là không phát hiện sai lệch thứ tự, không đảm bảo mọi frame đã được phát đúng.

## Lộ trình tiếp
Nghiên cứu thêm CGI 70mai handshake và các lựa chọn xem trước, nhưng chỉ triển khai sau xác nhận protocol phù hợp chính xác với A500S. Thử nghiệm an toàn khi xe dừng, hoặc có hành khách quan sát màn hình. Không dùng dữ liệu AI để quyết định điều khiển xe.

Nguồn tiêu chuẩn RTCP: https://www.rfc-editor.org/rfc/rfc3550
