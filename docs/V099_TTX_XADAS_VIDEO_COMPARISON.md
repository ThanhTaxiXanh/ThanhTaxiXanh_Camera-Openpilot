# Đối chiếu TTX Camera AI V0.9.8 và xADAS 0.2.7: video thực tế (03/11/2026)

## Nguồn mẫu do người dùng gửi
- Video 1 – TTX Camera AI: 5b187e0f5d414fcf85366657603a4001.mp4, 50.4333 giây; 1513 frames quay màn hình; H264 512×1136 / 30fps.
- Video 2 – xADAS: cbb2c28afa19d8d19f1f8a9bde530883.mp4, 87.5333 giây; 2626 frames quay màn hình; H264 512×1136 / 30fps.

30fps là FPS màn hình, không phải FPS từ camera. Hai video khác cảnh: TTX gần như đỗ xe, xADAS đang chạy; không thể so sánh tốc độ camera chỉ từ chuyển động pixel.

## Chứng cứ trong video TTX
- Khoảng 5 và 22 giây: Direct RTP/UDP: 0 packets/s, H264 AU 0/s, MediaCodec 0 FPS.
- RTSP DESCRIBE 200, SETUP 200, PLAY 200: đã kết nối điều khiển, không chứng minh luồng media có dữ liệu.
- Khoảng 22 giây báo camera từ chối cả hai keepalive và reconnect 1/6.
- Từ khoảng 15–25 giây, timestamp OSD trong khung hình 70mai giữ nguyên 11:11:55. Điều này phù hợp với việc giữ frame cũ lâu, không chỉ rớt một vài frame.
- RTCP 2 gửi không chứng minh RTP media đã tới.

## Chứng cứ trong video xADAS
- Hình con đường đang chuyển động, lớp phủ AI làn đường và khoảng cách xe phía trước liên tục cập nhật.
- Video thang xám chỉ chứng minh hậu xử lý/hiển thị; không chứng minh camera phát bitrate thấp hơn hay FPS cao hơn.
- Quay màn hình 30fps có các frame lặp như bình thường; không thể khẳng định camera gốc 30fps.
- Không phát hiện quãng đứng hình rõ tương đương trên cảnh chuyển động, nhưng không phải đo packet-level.

## Dấu vết XAPK
Các strings trong classes.dex xADAS 0.2.7 có:
- rtsp: no video without camera commands, sending them again
- rtsp: cam answered TCP to a UDP SETUP, reading interleaved
- rtsp: RTCP receiver reports every 5 s on channel
- /cgi-bin/client.cgi?&-operation=, &-operation=register&-ip=, handshake: heartbeat uses
- MediaCodec.Callback, getOutputImage, releaseOutputBuffer (trong bytecode).
Đây là các tính năng hiện diện trong ứng dụng, chưa chứng minh nhánh nào xADAS sử dụng trong clip cụ thể. CGI 70mai chưa được TTX tái tạo đầy đủ; không nên gửi lệnh chưa kiểm chứng.

## V0.9.9
1. RTSP PLAY=200 nhưng không nhận được RTP sau 5 giây: báo lỗi và thoát phiên cũ.
2. Đã nhận RTP nhưng không ghép được H264 AU sau 8.5 giây: báo lỗi ghép khung.
3. Nếu server đã đàm phán UDP và xuất hiện một trong hai lỗi trên, tự chuyển sang TCP ở phiên RTSP mới; không quay lại UDP vô hạn.
4. Không chuyển transport nếu chỉ keepalive trả 405/501. Fallback GET_PARAMETER sang OPTIONS (read-only), không dùng SET_PARAMETER.
5. Log đầy đủ RTSP SETUP Transport và một lần nếu UDP packets đến từ IP không phải camera.
6. Tests kiểm tra lựa chọn failover, OPTIONS, các điều kiện không switch.

## Giới hạn
TCP vẫn có thể giật khi xe chạy; người dùng đã kiểm chứng. Đây là sửa lỗi mất luồng, không phải giải pháp tổng thể tương đương xADAS. Chưa có RTP trace cùng thời điểm trên cả hai ứng dụng; chưa biết xADAS đang dùng UDP/TCP cho clip này.
Tiếp theo: xác minh trình tự CGI handshake/preview với A500S, các hình ảnh nguồn, bitrate, frame timestamps rồi mới nối YUV AI trực tiếp. Không dựa vào ADAS thử nghiệm để quyết định lái/phanh.

Nguồn Android: https://developer.android.com/reference/android/media/MediaCodec
