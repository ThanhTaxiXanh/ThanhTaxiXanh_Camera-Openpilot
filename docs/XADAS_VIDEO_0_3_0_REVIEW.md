# Phân tích video xADAS 0.3.0 – 02/11/2026

Đầu vào: video người dùng gửi, khoảng **152,67 giây**, dọc 512×1136, ghi hình màn hình Android. Phiên bản hiển thị ở phần About là **xADAS 0.3.0**; phân biệt với XAPK 0.2.7 nghiên cứu trước.

## Những gì video thực sự chứng minh
- 0–16 s: phát hình nguồn camera hành trình qua Wi-Fi, sau đó lớp phủ làn đường xanh đa giác và nét biên hiện lên. Có nhãn AI và nút PRO.
- ~40–48 s: có khung hình với mũi tên tam giác vàng trước xe; bảng khoảng cách có thời điểm hiện **6 m ±2**, sau đó **6 m ±6** và “Dữ liệu cũ 2,8 s”. Sai số được hiển thị thay đổi, nên không suy diễn khoảng cách thật 6 m.
- ~52 s: hội thoại ghi dữ liệu chẩn đoán, hiển thị thông tin quyền riêng tư của phiên chẩn đoán.
- 64–116 s: đi qua các trang Cài đặt; được ghi lại bên dưới.
- ~120 s trở đi: quay lại màn hình camera; hình vẫn tiếp tục xử lý.
- Không có cảnh so sánh đo khoảng cách với thước, radar hay dữ liệu ground truth. Không chứng minh độ chính xác hoặc tính năng phanh tự động.

## Cấu hình đã thấy (không phải khẳng định hành vi nội bộ)
1. **Nguồn camera:** Cam hành trình – Đã kết nối.
2. **Tốc độ bắt đầu đo:** 10 km/h; thanh trượt 0–150 km/h. Mô tả khi dưới ngưỡng liên tục 30 giây sẽ giảm tải để tránh nóng.
3. **Âm thanh khi khoảng cách đỏ:** tùy chọn âm thanh nếu khoảng cách đỏ hiện liên tục 5 giây, còn đỏ lại trong 3 giây; không xác minh logic bằng log.
4. **Mốc cảnh báo:** Việt Nam / Theo giây. Theo giây 1–3 giây, mặc định 2,0 giây. XADAS ghi một số mốc theo quy tắc Việt Nam; phải kiểm chứng văn bản luật độc lập trước khi sử dụng.
5. **Hiển thị khi lái:** nền tối sau 1–30 phút (mặc định 1); hiển thị khoảng cách theo quãng 5 m; mũi tên chỉ xe phía trước; logo; ẩn thanh trạng thái; tự cắt giảm hiển thị khi xe dừng lâu.
6. **Lắp đặt trên xe:** tóm tắt tối đa 1,5 m; bù GPS +2 km/h; chưa mở chi tiết trang để xác minh các điều khiển khác.
7. **Chạy nền và chia sẻ:** Tắt / Bong bóng PiP / Cửa sổ nổi; kích thước nhỏ/vừa/lớn; quyền hiển thị trên ứng dụng khác; chọn chia sẻ dữ liệu khoảng cách cho ứng dụng khác, âm thanh khi chạy nền.
8. **Chung:** ngôn ngữ theo hệ thống; đơn vị km/h · m hoặc mph · ft; chẩn đoán; các phiên chẩn đoán; phiên bản 0.3.0.
9. Có trang thông tin bản quyền/pro, không sao chép entitlement/cơ chế kích hoạt.

## Diễn giải hình học
Vùng xanh là overlay đường đi ước lượng; có ít nhất một đường biên rõ và một số hình bị che bởi nhiễu/đường thực tế, nhưng không thể xác định sơ đồ tensor neural từ video. Kết hợp kết quả phân tích XAPK cũ cho thấy xADAS đóng gói ONNX/NCNN, nhưng không chứng minh model 0.3.0 giống chính xác model 0.2.7.

## TTX Camera AI
- Màn hình cài đặt nghiên cứu độc lập; tùy chọn HUD hoạt động ngay; ngưỡng khoảng cách, thời gian, GPS chỉ lưu chờ module đã kiểm định.
- Lớp phủ xanh ở TTX chỉ hiển thị khi thuật toán thử nghiệm tìm được hai biên hình học hợp lý.
- Chưa dùng trọng số xADAS và không phân phối thành phần xADAS chưa có quyền sử dụng.
- Ứng dụng hỗ trợ quan sát, không thay cho việc lái xe chủ động.
