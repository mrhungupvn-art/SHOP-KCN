# COM11H Shop (App Tiệm/Đối tác) — Android

Ứng dụng Android cho **tiệm/đối tác** xử lý đơn hàng theo thời gian thực.
Đây là project **MỚI**, tách biệt hoàn toàn với app Shipper — dùng chung
backend (`com11h.com/api/index.php`) nhưng token, package name và luồng
nghiệp vụ đều riêng.

## Vì sao có app này
Trước đây tiệm chỉ có **web portal** (`admin/login.php` → `admin/foods.php`)
để đăng/sửa món ăn. Nhưng khi có đơn hàng mới, tiệm KHÔNG có cách nào biết
và xác nhận kịp thời — dẫn tới shipper phải tự liên hệ tiệm hoặc admin đứng
ra làm trung gian. App này lấp đúng khoảng trống đó: tiệm mở app, thấy ngay
đơn nào cần chuẩn bị, bấm nhận/từ chối/báo xong — không cần biết gì về code.

**App KHÔNG thay thế web portal** — đăng món, sửa mô tả, bật/tắt còn hàng vẫn
làm trên web như cũ. App chỉ lo phần "có đơn thì xử lý".

## Luồng nghiệp vụ
1. Khách đặt hàng (web/app khách) → nếu **tất cả** món trong đơn đã được gán
   cho 1 tiệm cụ thể (`foods.store_id`), hệ thống tự tạo 1 "pickup" cho tiệm
   đó ngay khi đặt đơn (còn ở trạng thái `pending`, tiệm CHƯA thấy trên app).
2. Đơn được xác nhận thanh toán (QR tự động hoặc admin xác nhận COD) → pickup
   xuất hiện trên app tiệm, mục **"Đơn cần xử lý"**.
3. Tiệm bấm **"Nhận đơn"** (pending → confirmed) hoặc **"Từ chối"** kèm lý do
   (hết món...). Từ chối sẽ báo ngay cho Admin qua Telegram để xử lý hoàn
   tiền/đổi món — app KHÔNG tự quyết định việc này.
4. Tiệm chuẩn bị xong, bấm **"Món đã xong, sẵn sàng"** (confirmed → ready).
5. Chỉ khi **mọi tiệm liên quan** trong đơn đều đã "ready", đơn mới xuất hiện
   trong danh sách "đơn sẵn sàng" của app Shipper — tránh shipper tới nơi mà
   món chưa xong.

**Đơn kiểu cũ** (món chưa gán tiệm nào, bếp trung tâm nấu trực tiếp) sẽ KHÔNG
có pickup nào cả — hành vi giữ nguyên như trước, tiệm không cần làm gì, app
Shipper xử lý thẳng như cũ.

## Điều kiện đăng nhập
- Cần tài khoản `partner_accounts` (tạo ở `admin/partners.php`).
- **Bắt buộc đã ký hợp đồng điện tử** (`partner_contracts.status = 'signed'`)
  — nếu chưa ký, app báo lỗi và yêu cầu đăng nhập web trước để ký.

## API contract đang dùng
Cùng endpoint `https://com11h.com/api/index.php`, xem chú thích đầy đủ trong
`api/index.php` (khối "APP TIỆM / ĐỐI TÁC") và các hàm trong `core.php`
(`partner_list_pending_pickups`, `partner_confirm_pickup`, `partner_ready_pickup`,
`partner_reject_pickup`...).

- `partner_login`            `{"username","password","device"}`
- `partner_pickups`          (GET)
- `partner_confirm_pickup`   `{"pickup_id"}`
- `partner_ready_pickup`     `{"pickup_id"}`
- `partner_reject_pickup`    `{"pickup_id","reason"}`
- `partner_logout`

Headers bắt buộc: `X-KCN-ID`, `Accept: application/json`,
`Authorization: Bearer <token>` (trừ `partner_login`).

**KHÔNG** hiển thị địa chỉ/SĐT khách hàng trong app này — đó là thông tin
riêng của shipper để giao hàng, tiệm chỉ cần biết món + số lượng để nấu.

## GitHub Secrets (giống app Shipper)
`API_BASE_URL`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Nếu chưa có keystore: chạy workflow **"Create COM11H Keystore"** 1 lần để tự
sinh, tải file `.keystore.base64` trong Artifacts, dán nội dung vào secret
`KEYSTORE_BASE64`. Chưa có đủ secret thì workflow vẫn build ra APK release
KHÔNG ký (đủ để cài thử, chưa nộp được Play Store).

## Local build
```bash
gradle :app:assembleDebug
```
