# V0.9.5 – Vấn đề giật trong lúc di chuyển: giải phóng MediaCodec output

## Dữ liệu kiểm chứng (02/11/2026)
Video Android screen-recording 48,0667 giây (512x1136, bản ghi 30fps; KHÔNG phải fps camera). Xe di chuyển, overlay GPS hiển thị khoảng 28–29 km/h ở những thời điểm kiểm tra. Trên UI Direct V0.9.4, khoảng giây 23 ghi decode=29.1fps/rendered=24.7fps, frame trễ bỏ=262, RTP gap=0, keepalive=3/3; giây 42 ghi decode=14.8fps/rendered=15.3fps, frame trễ bỏ=296, RTP gap=0, keepalive=4/4.
Tính toán thay đổi vùng hình từ video có khoảng tĩnh kéo dài hơn 1s khi xe di chuyển; không coi đây là đo chuẩn FPS decoder.

## Nguyên nhân kỹ thuật và độ tin cậy
Mã V0.9.4 sử dụng MediaCodec.Callback.onOutputBufferAvailable -> Handler.postDelayed(releaseOutputBuffer) để giữ mỗi khung chờ PTS lên tới 550ms. Android MediaCodec docs cảnh báo việc giữ output buffers có thể khiến decoder không sinh được output tiếp theo (đặc thù thiết bị). Do RTP gap=0, keepalive tốt, giật có liên hệ nhiều hơn với scheduling/decoder và Surface; vẫn cần đo sâu hơn để chứng minh nguyên nhân duy nhất.

## Thay đổi
1. Khi codec có output: releaseOutputBuffer(index,true) ngay tại callback, luôn trả lại slot decoder. Không lập lịch chờ 550ms trong codec callback.
2. Thread cấp nén H264 Access Unit áp dụng RtpInputPacer (350ms input cushion) và PTS ở 90kHz chuyển us. Output callback và reader không chờ.
3. Nếu feed bị trễ thì submit ngay và đếm frame nạp muộn, **không** rebuffer một lần nữa chỉ vì đang trễ. RtpInputPacer chỉ rebase khi timestamp nhảy thực.
4. Giữ queue compressed 48, timeout 900ms khi đầy, không bỏ compressed P-frames bừa bãi.
5. Keepalive GET_PARAMETER, recovery 6 lần, phát dự phòng VLC 800ms: không đổi.

## Giới hạn, tiêu chí chấp nhận
Chưa có đo nhiệt/GPU/real displayed VSYNC; decoded/rendered counters là callback/released-to-Surface, không đồng nghĩa khung người dùng đã nhìn.
Kiểm thử xe DỪNG trước để bảo đảm app hoạt động; chỉ đánh giá khi xe di chuyển với màn hình được người khác thao tác/ghi lại, người lái không dùng điện thoại.
Các chỉ số mong đợi (không hứa trước): RTP gap=0, queue ổn định, FPS decode/render gần FPS stream, "frame nạp muộn" không tăng liên tục.
Nếu queue tăng dù output callback đã giải phóng ngay, cần kiểm tra MediaCodec vendor, RTP timestamp drift, độ dài access units và loại khung H264. Tiếp tục điều tra đối chứng cùng VLC.
Nguồn Android: https://developer.android.com/reference/android/media/MediaCodec.
