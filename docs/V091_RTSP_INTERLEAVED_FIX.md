# TTX Camera AI V0.9.1 – Fix RTSP Invalid reply on 70mai A500S

## Bằng chứng từ ảnh thực tế
Điện thoại IP 192.168.0.20, gateway 192.168.0.1.
DESCRIBE cho rtsp://192.168.0.1:554/livestream/12 trả RTSP/1.0 200 OK,
Content-Base: rtsp://192.168.0.1/0000000A/ (giá trị phiên động, không hardcode);
SDP chứa a=control:*,track1, m=video 0 RTP/AVP 96, a=rtpmap:96 H264/90000.
DirectRtspPlayer V0.9.0 báo "Invalid reply" kèm byte không in được, nhìn thấy ký tự "$" đầu.
Kết luận có cơ sở: parser dòng văn bản V0.9.0 không xử lý nhị phân RTP interleaved.
Chưa kết luận chắc rằng dữ liệu bắt đầu bằng "$" là một gói RTP hợp lệ; cần kiểm tra channel/length/RTSP stage.

## Sửa mã
- RtspWireReader: trình phân loại sự kiện từ cùng TCP socket, ranh giới RTSP reply và $/channel/2-byte length/payload (RFC 2326 10.12).
- Không dùng BufferedReader hoặc readLine để đọc payload nhị phân.
- CSeq xác thực để phát hiện response sai thứ tự.
- Chờ phản hồi OPTIONS/DESCRIBE/SETUP/PLAY có thể bỏ qua tối đa 128 gói RTP/512kB trước response; tránh treo vô hạn.
- Khi PLAY trả về chậm hơn một số gói RTP, giữ tối đa 24 gói / 96 KiB cho decoder.
- Sau PLAY đọc chung các gói RTP và phản hồi RTSP, không tự ăn nhầm header.
- Nhận diện channel RTP từ Transport header của SETUP, không mặc định chỉ channel 0.
- Mỗi phương thức RTSP được log rõ CSeq và mã kết quả; không đổ một chuỗi nhị phân rác lên giao diện.
- Chế độ Direct không tự chuyển liên tục /livestream/12 sang /livestream/0 sau 12s chưa có hình; giữ chẩn đoán gốc và dừng thử nghiệm.
- VLC 800 ms vẫn là bộ phát ổn định, không thay đổi.

## Giới hạn và bước thử
1. Cài V0.9.1, ngắt một phiên camera cũ nếu cần, xe dừng.
2. Chọn 70MAI / DIRECT MEDIACODEC, không bật AI ở lần đầu.
3. Nếu chưa có hình, sao chép hoặc chụp log mới (OPTIONS, DESCRIBE, SETUP, PLAY).
4. Nếu có hình, xem FPS Direct H264, sau đó mới thử nhánh AI; YUV có thể không được hỗ trợ từ codec Surface.
5. Trường hợp Direct vẫn lỗi cần dữ liệu Wi-Fi/RTP thực tế; không có mẫu lưu lượng TCP của chính A500S trong môi trường này.

RFC: https://www.rfc-editor.org/rfc/rfc2326.html

## V0.9.2: Ưu tiên DESCRIBE làm lệnh đầu tiên

Ảnh A500S cho thấy probe `DESCRIBE` đơn lẻ trả 200 OK. Trong khi đó bộ phát Direct V0.9.0 gửi `OPTIONS` trước, chưa có bằng chứng A500S cần lệnh này. Theo RTSP/1.0, OPTIONS không bắt buộc trước DESCRIBE.
Bởi vậy bộ phát Direct dùng trình tự `DESCRIBE (CSeq 1) → SETUP (2) → PLAY (3)`, dựa trên thao tác đã được người dùng kiểm chứng ở bước probe. Nếu model firmware khác cần OPTIONS, log mới sẽ cho thấy DESCRIBE không thành công.
Các sửa đổi về demultiplexer của V0.9.1 vẫn được giữ nguyên.
