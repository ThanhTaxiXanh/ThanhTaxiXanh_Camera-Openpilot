# TTX Camera AI V0.9.6 — Rollback bộ giữ nhịp V0.9.5

## Đầu vào thực tế 03/11/2026
Người dùng gửi 3 video khi xe dừng và di chuyển: lần lượt khoảng 45.0, 17.17, 62.6 giây. Đây là video quay màn hình 512x1136 khoảng 30FPS, không phải FPS RTSP.
- Tại một thời điểm V0.9.5: Direct decoded/rendered chỉ 16.9FPS, RTP gap=0, queue=0/48, frame nạp muộn=3864 (tích lũy), keepalive 73/73 OK, không reconnect.
- Video khác: ~23.3FPS, queue=6/48, late=0, keepalive 25/25.
- Trạng thái thực: V0.9.5 giật cả khi xe dừng và di chuyển; V0.9.4 trước đó mượt hơn nhưng bị tăng queue/late drops về sau.

## Vì sao làm lại
V0.9.4 giữ decoded output buffers 550ms -> có thể gây nghẽn hardware codec.
V0.9.5 chuyển PTS clock sang feeder, trì hoãn cấp input, lại neo đồng hồ xuyên suốt phiên. Một pipeline nhạy trễ có thể giảm nhịp khi PTS RTP và System.nanoTime không trùng tốc độ.
Chưa có tcpdump hay camera-native frame timestamps để kết luận nguyên nhân duy nhất.

## V0.9.6
- Hoàn tác PTS pacing của feeder, không sleep theo PTS.
- Khi có input buffer MediaCodec, submit complete Access Unit H264 trực tiếp và giữ PTS gốc.
- Khi decode output, release ngay ra Surface; KHÔNG giữ output buffer dài 550ms.
- Giữ hàng đợi bounded 48AU, không hủy khung tham chiếu P tùy tiện.
- Giữ keepalive GET_PARAMETER, session lifecycle/reconnect và LibVLC 800ms.
- Telemetry: nhận AU/s, nạp decoder/s, giải mã/s, giao Surface/s; queue depth, RTP gap, lượt chờ input decoder tích lũy, MB nén nhận.
- Không dùng PixelCopy trong chế độ Direct AI tắt.

## Diễn giải số liệu
- Nhận AU/s ~30 nhưng nạp <30: feeder hoặc input decoder nghẽn.
- Nạp ~30 nhưng decoded <30: codec hoặc Surface nghẽn.
- Nhận chỉ ~15-20 AU/s, RTP gap=0: có thể nguồn camera thực gửi thưa/chậm, hoặc RTP/H264 assembler gom sai; cần đo raw RTP timestamps và decode NAL.
- Các bộ đếm so theo cùng khoảng 2s; FPS nạp/giao Surface KHÔNG phải VSYNC FPS thực sự.

Không khẳng định V0.9.6 đã khắc phục hoàn toàn cho tới khi được kiểm tra trên thiết bị người dùng. Xe phải dừng hoặc có hành khách ghi màn hình; không thao tác khi đang lái.
