# Đặc tả giao tiếp giữa các mô-đun và các điểm cần thống nhất

Theo `docs/Thiết kễ hệ thống thu thập dựa trên kiến trúc dịch vụ nhỏ.pptx` (thư mục `docs/` ở gốc repo). Tất cả giao tiếp đều là HTTP + JSON, mã hoá UTF-8.

## Sơ đồ và cổng

```
                 ┌──────────────── POST /add {"urls":[...]} (URL mới) ────────────────┐
                 ▼                                                                    │
         Điều khiển (BAT) :5200 ──POST /load {url}──▶ Tải về (N5) :8800 [nhiều bản]   │
              │  GET /ping ◀───────────────────────────┘     │                        │
              │                                              │ POST /parse            │
              │                                              ▼                        │
              │                                  Đọc nội dung (N1) :6600              │
              │                                              │ POST /add              │
              │                                              ▼                        │
              │                                  Tổng hợp dữ liệu (N8) :3300 ─────────┘
              │                                              │ POST /add
              │                                              ▼
              │                                  Lưu trữ (N10) :2200 ──▶ MongoDB (Ctrl+F) :27017
```

| Mô-đun | Nhóm | Cổng | Lệnh nhận | Gửi tới |
|---|---|---|---|---|
| Điều khiển | BAT | 5200 | `POST /add {"urls":[...]}` | Tải về `/ping`, `/load` |
| **Tải về** | **N5 (nhóm mình)** | **8800** | **`POST /load {"url"}`, `GET /ping`** | **Đọc nội dung `/parse`** |
| Đọc nội dung | N1 | 6600 | `POST /parse` | Tổng hợp `/add` |
| Tổng hợp dữ liệu | N8 | 3300 | `POST /add` | Lưu trữ `/add`, Điều khiển `/add` |
| Lưu trữ | N10 | 2200 | `POST /add` | MongoDB |
| Mongo | Ctrl+F | 27017 | — | — |

## Các gói JSON (như cài đặt trong project này)

**Điều khiển → Tải về** `POST /load`: `{"url": "https://hust.edu.vn/tin-tuc"}`.
Trả `202 {"status":"đã nhận"}` hoặc `503 {"status":"đang bận"}`.

**Điều khiển → Tải về** `GET /ping`: `{"status": "đang chờ" | "đang bận", ...}`.

**Tải về → Đọc nội dung** `POST /parse`:
```json
{"url": "...", "format": "html|pdf|docx|...", "content_type": "text/html; charset=utf-8",
 "encoding": "text|base64", "content": "...",
 "final_url": "...", "http_status": 200, "fetched_at": "...", "render": "chrome|http", "downloader": "N5-1"}
```

**Đọc nội dung → Tổng hợp** và **Tổng hợp → Lưu trữ** `POST /add`:
```json
{"url": "https://hust.edu.vn/tin-tuc1", "format": "html", "title": "Tin tức 1",
 "content": "Nội dung bóc tách được", "intro": "mô tả trang nếu có",
 "out-links": [{"url": "https://hust.edu.vn/a", "text": "Câu chứa liên kết."}]}
```

**Tổng hợp → Điều khiển** `POST /add`: `{"urls": ["...", "..."]}`.

**Lưu trữ → MongoDB**: collection `html {url, format, title, content, intro}`, `other {url, format, content}`,
`graph {src, dest, intro}`. Upsert theo `url`, riêng `graph` theo cặp `(src, dest)`.

## Các điểm trong slide chưa rõ, cần chốt với thầy và các nhóm

| # | Điểm | Trong slide | Đề xuất của nhóm N5 (đã cài như vậy) |
|---|---|---|---|
| 1 | Gửi tệp nhị phân (PDF, DOCX) sang Đọc nội dung | Chỉ có ví dụ `"encoding": "text"` | `"encoding": "base64"` và `content` là base64. Gửi nhị phân dạng text sẽ hỏng dữ liệu |
| 2 | Cấu trúc `out-links` | `["url đi ra", "văn bản liên kết"]`, không rõ là cặp hay danh sách | Mảng đối tượng `[{"url": ..., "text": ...}]`. Bản Tổng hợp tham chiếu nhận cả dạng chuỗi URL |
| 3 | Cổng Tải về | `http://ip:/load`, không ghi cổng; sơ đồ ghi 8800 | 8800. Chạy nhiều bản trên một máy thì 8800, 8801… |
| 4 | Ý nghĩa `/ping` | Chỉ có ví dụ `{"status":"đang chờ"}` | `"đang chờ"` = nhận việc được ngay, `"đang bận"` = hết chỗ |
| 5 | `/load` trả gì, đồng bộ hay không | Không nói | Bất đồng bộ: trả 202 ngay; hàng đợi đầy thì 503 |
| 6 | Trang lỗi 404/500, trang không tải được | Không nói | Tải về không gửi đi, chỉ ghi vào `/stats`. Có thể bật `FORWARD_ERRORS=1` |
| 7 | Đích không phản hồi | Không nói | Thử lại 3 lần với độ trễ tăng dần (0,5 s, 1 s, 2 s) |
| 8 | Trường bổ sung | Không nói | Bên nhận **bỏ qua trường lạ**. N5 gửi thêm `final_url` (cần để giải liên kết tương đối sau chuyển hướng), `http_status`, `fetched_at`, `render`, `downloader` |
| 9 | Ví dụ JSON | Thiếu dấu phẩy; mục `other` trong Mongo lặp khóa `url`, có `"content": "html"` | Coi là lỗi gõ; dùng cấu trúc ở mục trên |
| 10 | Giãn cách truy cập hust.edu.vn | Điều khiển "thiết lập thời gian chờ" | Điều khiển giãn cách theo host (bản tham chiếu: 700 ms). N5 chỉ giới hạn số việc song song |
