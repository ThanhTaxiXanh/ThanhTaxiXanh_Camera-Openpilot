# V0.6.0: 70mai A500S frame pair exporter

Mục đích: chuẩn bị dữ liệu để kiểm tra intrinsics/warp/temporal, không phải thu thập video liên tục.

1. Ghép nối 70mai A500S, mở TTX, chọn “70mai / VLC”.
2. Khi video đã phát, **dừng xe**, chọn “XUẤT 2 KHUNG HÌNH A500S (ZIP)”.
3. Ứng dụng dùng PixelCopy để lấy 2 khung cách nhau tối thiểu 120 ms, với mục tiêu 350 ms.
4. Dùng trình lưu tệp Android để chủ động lưu ZIP chứa frame_01.jpg, frame_02.jpg và frame_metadata.json.
5. Mẫu này là ảnh hiển thị từ SurfaceView (mức 640x360), không phải luồng YUV gốc; dùng để phân tích hình học sơ bộ, không thể kết luận warp giống xADAS.
6. **Quyền riêng tư:** ảnh có thể chứa biển số, khuôn mặt, đường đang đi. Không có upload tự động; chỉ chia sẻ với người mà chủ thiết bị tin tưởng.

Parser của OpenpilotVisionDecoder đã có test mean/std từ tensor tổng hợp. Chưa có mô hình được phân phối kèm TTX hoặc kết quả làn đường neural trực tiếp. Những số mét raw không được dùng để kích hoạt cảnh báo.
