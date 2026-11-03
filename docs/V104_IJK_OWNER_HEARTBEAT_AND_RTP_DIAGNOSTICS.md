# V1.0.4 – Hai video A500S V1.0.3, CGI registration và RTP jitter diagnostics

## Hai video do chủ camera cung cấp
- Video 1 IJK/FFmpeg: 39.700 giây, 1191 frames 30fps (quay màn hình, không phải FPS camera). Khoảng giây 31: `RTSP kết thúc (OnCompletion)`, bộ đếm phục hồi=1/5 và tuổi phiên phát về 1 giây. Hình tiếp tục sau khi mở lại IJK. IJK native output FPS ~10–18 tại các mốc quan sát; chủ camera báo hình mượt tương đối.
- Video 2 Direct MediaCodec: 29.900 giây, 897 frames 30fps. Bộ đếm RTSP heartbeat 1/1 tới 3/3 OK, reconnect=0. Tại khoảng giây 25, RTP nhận ~64.5 packets/s, decoder và Surface ~2.0fps; khoảng giây 28 lại RTP ~300 packets/s, output ~27.8fps.
- Bộ đếm `UDP đến lệch=163`, `khôi phục=156`, `thiếu=28` giữ nguyên tại nhiều mốc từ đầu đến cuối. Vì vậy không có bằng chứng lỗi 1 giây là do mỗi giây có một đợt out-of-order mới.

## Phương án chủ động kiểm chứng nguyên nhân IJK
- Bằng chứng bên thứ ba về đúng A500S/DR2500: ứng dụng đăng ký `client.cgi` mỗi vài giây sau khi người dùng chấp nhận ghép đôi trên chính camera. Giao thức dùng `BindByBanya.cgi`, `UserconfirmByBanya.cgi`, token và signkey.
- Thêm nút `GHÉP ĐÔI & DUY TRÌ CAMERA A500S` chỉ chạy khi người dùng chạm. Camera phải yêu cầu và được xác nhận vật lý. Không giả token hoặc gửi yêu cầu CGI khi chưa có token được ghép đôi.
- Sau khi xác nhận, lưu token trong `SharedPreferences` private (Android allowBackup=false), khởi chạy đăng ký CGI đã ký cứ 5 giây trong thời gian IJK đang mở. HTTP được gắn vào Android Network Wi-Fi của A500S, timeout 2.8 giây, không theo redirect, chỉ gọi CGI được nêu trong báo cáo A500S.
- Nếu firmware trả ResultCode !=0, hiển thị trên HUD. Không khẳng định đây là phương án chính thức hoặc bảo đảm hết ngắt sau 30 giây. Đối chứng IJK không ghép đôi vẫn hoạt động như cũ.

## Direct RTP
- Không thay đổi buffer/hardware decoder vì chưa đủ bằng chứng. Thêm RTP marker/s, timestamp changes/s và jitter đo 90kHz. Khi thông số source cùng giảm với FPS thì cần xem camera/receiving thread; khi source đều nhưng AU hoặc codec giảm thì điều tra H264 assembler hoặc MediaCodec.
- Dữ liệu hiện có chưa phải packet capture: không biết toàn bộ gói camera đã phát, chỉ biết những gói ứng dụng nhận/đếm.

## Kết quả nghiệm thu cần theo dõi
1. Xe dừng, kết nối Wi-Fi A500S; nhấn nút ghép đôi trong TTX, xác nhận vật lý trên camera, không chạy đồng thời app chính thức.
2. IJK phát liên tục >120 giây, heartbeat registered n/n OK và xem còn xảy ra OnCompletion tại mốc 30 giây hay không.
3. Direct chạy >60 giây, theo dõi RTP packet/s, marker/s, timestamp/s và FPS khi đứng hình; đếm missing packet không chứng minh camera gửi đúng số khung.
4. Nếu CGI báo lỗi, không sửa firmware và không tắt/bỏ qua xác thực; dùng IJK tự reconnect làm phương án dự phòng. Thực nghiệm giao thông cần người đi cùng thao tác.

Sources:
- https://alu.dog/posts/reverse-engineering-the-70mai-android-app/
- https://www.rfc-editor.org/rfc/rfc2326
- https://www.rfc-editor.org/rfc/rfc3550