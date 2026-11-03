# Đối chiếu 70mai 4.5.0 (A500S/DR2500) – xADAS 0.2.7 – TTX Camera AI
Ngày: 03/11/2026.

## Nguồn và giới hạn
Đối chiếu báo cáo Markdown do người dùng gửi: 70mai 4.5.0 và A500S: Cơ chế kết nối, phát video trực tiếp và chuyển camera trước/sau; cùng mã TTX hiện có và ghi nhận video thực tế. Kết quả từ báo cáo 70mai là phân tích tĩnh, không phải packet capture hoặc xác nhận mọi nhánh runtime trên firmware A500S.

## Những phát hiện có cơ sở
- A500S quốc tế thuộc nhóm DR2500 / dr2500_intl. Endpoint RTSP có căn cứ mạnh nhất là rtsp://192.168.0.1:554/livestream/12, nhưng nên lấy IP theo gateway của mạng Wi-Fi camera.
- Theo báo cáo XAPK 70mai 4.5.0, ứng dụng có libijkplayer.so, libijkffmpeg.so, IjkVideoView và các option rtsp_transport, mediacodec, framedrop, packet-buffering, min-frames, max_cached_duration, analyzeduration, probesize, reconnect và flush_packets.
- Chưa đủ bằng chứng 70mai A500S thực chạy với tất cả option trên; cần xác minh giá trị tùy nhánh ứng dụng và firmware.
- xADAS (từ phân tích XAPK trước): RTSP client riêng + MediaCodec + RTP TCP/UDP + RTCP và cơ chế đăng ký/heartbeat camera; không đồng nhất với ứng dụng 70mai/IJK.
- TTX hiện có DirectRtspPlayer tự ghép RTP/H264 -> MediaCodec -> TextureView, hoặc LibVLC -> VLCVideoLayout; không phải pipeline FFmpeg/IJK như ứng dụng 70mai.
- API setwifistream.cgi với sensor 0/1 có trong code dùng chung 70mai, nhưng chưa chứng minh A500S hỗ trợ live camera sau. Không tự coi /livestream/13 là rear: báo cáo gắn đường dẫn đó với họ plugin HiSilicon khác.

## Kết luận từ hai video TTX và xADAS
- Ở một số mốc trong video TTX V0.9.8: RTP 0 packets/s, AU 0/s, MediaCodec 0 FPS; RTSP SETUP/PLAY 200 không đồng nghĩa đã có luồng video.
- Một số lần xem VLC hay Direct khi xe chạy cũng giật: không thể quy nguyên nhân chỉ cho RTP/TCP hoặc RTP/UDP.
- Video xADAS hiển thị grayscale; đây có thể là hậu xử lý, không chứng minh camera phát profile nhẹ hơn.
- FPS 30 của video quay màn hình không phải FPS camera A500S.

## Quyết định kỹ thuật đề xuất
1. Giữ RTP/RTSP media-liveness detection; nếu PLAY thành công nhưng không có frame, ghi rõ trạng thái và tái thiết lập phiên có giới hạn.
2. Đưa IJKPlayer/FFmpeg vào ma trận A/B như backend thứ tư chỉ sau khi xác minh bộ thư viện open-source còn truy xuất hợp lệ, native ABI, GPL/LGPL và build Android; tuyệt đối không lấy thư viện sở hữu riêng từ XAPK 70mai.
3. Đo trên cùng A500S và điện thoại: RTP gói/s, marker/s, timestamp changes/s, AU/s, decoder input/output/s, thời gian ảnh đứng và bitrate; so sánh chính xác cùng bối cảnh.
4. Xác minh quy trình ứng dụng 70mai/xADAS sử dụng để đăng ký phiên preview trước khi suy đoán hoặc phát CGI chưa rõ chữ ký/xác thực.
5. Chỉ sau khi media mượt ổn định mới tích hợp AI từ decoder/YUV; không dùng PixelCopy từ SurfaceView như bản TTX trước.
6. Chỉ mở live rear camera nếu camera/firmware thực tế cho phép và đã thử sensor/SDP, không gán /13 theo suy đoán.

## Tiêu chí nghiệm thu
- Không còn RTP=0 trong khi UI vẫn báo đang phát.
- Tối thiểu 5 phút liên tục khi đỗ và kiểm tra bởi hành khách/ghi màn hình tự động trong lúc xe chạy.
- Hàng đợi không tăng mãi, không liên tục thiếu AU hoặc codec output.
- Build thành công không được coi là xác nhận độ mượt.

## Tham chiếu
- Báo cáo người dùng gửi ngày 03/11/2026 (70mai 4.5.0/APKPure XAPK, phân tích tĩnh).
- Bằng chứng static xADAS trước đây trong dự án.
- RFC RTSP: https://www.rfc-editor.org/rfc/rfc2326.html
- RFC RTP/RTCP: https://www.rfc-editor.org/rfc/rfc3550
- IJKPlayer: https://github.com/bilibili/ijkplayer