# Hệ thống thu thập theo kiến trúc dịch vụ nhỏ (HUST IR)

Cài đặt đủ 6 mô-đun theo thiết kế của thầy (`../docs/Thiết kễ hệ thống thu thập dựa trên kiến trúc dịch vụ nhỏ.pptx`).
Các mô-đun gọi nhau bằng **HTTP + JSON** qua cổng mạng LAN. Nhóm mình phụ trách **Tải về (N5)**. Năm mô-đun còn lại là
**bản tham chiếu** để chạy thử cả chuỗi và kiểm tra N5 khớp với các nhóm khác.

```
Điều khiển :5200 ─/load─▶ Tải về N5 :8800 (+ :8801) ─/parse─▶ Đọc nội dung :6600 ─/add─▶ Tổng hợp :3300 ─/add─▶ Lưu trữ :2200 ─▶ Mongo
      ▲                                                                                     │
      └────────────────────────────── /add {"urls": [...]} (URL mới) ──────────────────────┘
```

| Thư mục | Mô-đun | Ghi chú |
|---|---|---|
| **`services/downloader/`** | **Tải về (N5)**: Puppeteer + Chrome headless, tài liệu tải trực tiếp | **Sản phẩm của nhóm**, tự chứa, tài liệu riêng: [`services/downloader/README.md`](services/downloader/README.md) |
| `services/controller/` | Điều khiển (BAT): xoay vòng giữa các bản Tải về, chỉ gửi khi `/ping` = "đang chờ", giãn cách theo host | tham chiếu |
| `services/parser/` | Đọc nội dung (N1): tiêu đề, văn bản, liên kết kèm câu chứa thẻ `<a>` (cheerio) | tham chiếu |
| `services/aggregator/` | Tổng hợp (N8): nạp URL mầm, lọc miền hust, bỏ URL đã có | tham chiếu |
| `services/storage/` | Lưu trữ (N10): collection `html / other / graph`; MongoDB nếu có `MONGO_URI`, không thì ghi tệp `data/storage/` | tham chiếu |
| `services/common/` | Tiện ích HTTP dùng chung cho các bản tham chiếu (N5 không dùng) | |
| `test/e2e/` | Kiểm thử đầu-cuối 6 mô-đun | |
| `scripts/start-all.js` | Chạy cả 6 mô-đun trên một máy | |
| `scripts/smoke-hust.js` | Thử N5 trên trang thật hust.edu.vn | |
| `docs/KIEM_THU.md` | 26 ca kiểm thử và kết quả | |
| `docs/DAC_TA_GIAO_TIEP.md` | Gói JSON giữa các mô-đun và **10 điểm cần chốt với các nhóm** | |

## Bắt đầu nhanh

Cần **Node.js ≥ 18.17** (khuyên dùng 20 hoặc 22, tải tại nodejs.org).

```bat
cd crawler-microservices
npm install                 :: cài cheerio, mongodb, puppeteer (Puppeteer tự tải Chrome lần đầu)
npm test                    :: 26 ca kiểm thử: 23 của N5 + 3 đầu-cuối
npm run start:all           :: chạy 6 mô-đun, thu thập từ https://hust.edu.vn/ (tối đa 50 URL)
```

Tuỳ chọn cho `start:all`: `npm run start:all -- --seeds https://hust.edu.vn/ --max-urls 200 --render auto --mongo mongodb://localhost:27017`.
Xem tiến độ: `curl http://127.0.0.1:2200/stats`, `curl http://127.0.0.1:8800/stats`, `curl http://127.0.0.1:5200/stats`.
Dữ liệu: `data/storage/{html,other,graph}.json`, hoặc MongoDB `hust_crawl` nếu có `--mongo`.

## Chạy trên mạng LAN (mỗi nhóm một máy)

Mỗi mô-đun đọc địa chỉ các mô-đun khác từ biến môi trường và nghe trên `0.0.0.0`. Ví dụ máy nhóm N5:

```bat
cd crawler-microservices\services\downloader
npm install
set PARSER_URL=http://<IP máy N1>:6600/parse
npm start
```

Các nhóm khác cần biết: **Điều khiển** gọi `http://<IP máy N5>:8800/load` và `/ping`. Nhớ mở cổng 8800 trên tường lửa
(`services/downloader/README.md`, mục 1).

Biến môi trường của các bản tham chiếu, khi cần giả lập nhóm khác:

| Mô-đun | Biến |
|---|---|
| Điều khiển | `PORT=5200`, `DOWNLOADERS=http://ip1:8800,http://ip2:8800`, `HOST_DELAY_MS=700`, `TICK_MS=200` |
| Đọc nội dung | `PORT=6600`, `AGGREGATOR_URL=http://ip:3300/add` |
| Tổng hợp | `PORT=3300`, `SEEDS=https://hust.edu.vn/`, `ALLOWED_DOMAINS=hust.edu.vn`, `MAX_URLS=200`, `CONTROLLER_URL=http://ip:5200/add`, `STORAGE_URL=http://ip:2200/add` |
| Lưu trữ | `PORT=2200`, `MONGO_URI=mongodb://ip:27017`, `MONGO_DB=hust_crawl`, `DATA_DIR=...` |

## Diễn tập trước khi ghép với các nhóm

1. `npm test`: 26/26 đạt.
2. `npm run smoke:hust`: N5 tải được trang thật của trường (cần Internet).
3. Chạy N5 trên máy mình, các mô-đun tham chiếu trên máy khác (hoặc cùng máy), đổi `PARSER_URL` và `DOWNLOADERS` theo IP.
4. Khi ghép thật: thay từng mô-đun tham chiếu bằng mô-đun của nhóm tương ứng. Theo dõi `GET /stats` của N5 để xem việc đến và đi.

## Quan hệ với project chính

Project Java ở thư mục gốc repo (crawler Mercator, bóc tách, chỉ mục, đánh giá) **giữ nguyên**. Project này đứng riêng, viết bằng
Node.js vì thiết kế yêu cầu **Puppeteer** cho mô-đun Tải về.
