# Giai đoạn V0.6 – Parser thị giác Openpilot

## Kết quả xác minh
Repo commaai/openpilot: selfdrive/modeld/parse_model_outputs.py (MIT) sử dụng parse_mdn cho lane_lines, road_edges, lead; sigmoid cho lane_lines_prob và lead_prob; mean/std được chia đôi theo từng head. ModelConstants: IDX_N=33, NUM_LANE_LINES=4, NUM_ROAD_EDGES=2, LEAD_TRAJ_LEN=6, LEAD_WIDTH=4, LEAD_MHP_SELECTION=3, X_IDXS=192*(i/32)^2.

Mô hình xADAS 0.2.7 có chính xác các head:
- lane_lines [117..644] = 528 = 2*4*33*2 (mean & log std)
- lane_lines_prob [645..652] = 8 = 4*2 (legacy logit + primary logit)
- road_edges [653..916] = 264 = 2*2*33*2 (mean & log std)
- lead [917..1060] = 144 = 2*3*6*4 (mean & log std)
- lead_prob [1061..1063] = 3 (sigmoid).

Bổ sung OpenpilotVisionDecoder.kt chứa parser độc lập, kiểm tra NaN/Inf, sigmoid ổn định và exp capped, cùng JUnit test với tensor tổng hợp.

## Quan trọng về 70mai A500S
Các số mean/std đã được parse chỉ có nghĩa hình học vật lý khi ảnh vào model được chuẩn hóa và warp đúng hệ tọa độ camera. A500S đã phát RTSP qua VLC; không được lấy kết quả số và chiếu thẳng lên hình, hoặc phát tiếng cảnh báo, khi chưa khớp intrinsics, crop, pitch/yaw và model-image correspondence.

## Kế hoạch bước kế tiếp
1. Bổ sung chụp 2 khung hình thô A500S, mốc thời gian chính xác và export file cho phân tích distortion/warp.
2. Lập ma trận hiệu chỉnh theo FOV ngang và kích thước thực của RTSP, kiểm định với ảnh chuẩn (chưa mặc định dùng 140° marketing làm HFOV).
3. Nạp ONNX có giấy phép phù hợp, chạy shadow inference trên 2 frame đã hiệu chỉnh; so golden outputs và xác nhận dấu tọa độ, phân phối xác suất.
4. Đối chiếu model head với ground truth và kích hoạt hiển thị thử nghiệm riêng; không dùng cho quyết định phanh/lái.
5. Kiểm tra FPS, thời gian chờ, nhiệt độ, lỗi Wi-Fi, dữ liệu cũ.
Nguồn: https://github.com/commaai/openpilot/blob/master/openpilot/selfdrive/modeld/parse_model_outputs.py
Nguồn: https://github.com/commaai/openpilot/blob/master/openpilot/selfdrive/modeld/constants.py
