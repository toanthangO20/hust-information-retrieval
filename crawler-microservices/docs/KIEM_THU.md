# Kiểm thử mô-đun Tải về (N5) và toàn hệ thống

**Kết quả: 26/26 ca đạt, 0 lỗi, 0 bỏ qua** (6 đơn vị + 17 dịch vụ + 3 đầu-cuối), trên cả hai môi trường:

| Môi trường | Node.js | Chrome | Thời gian `npm test` |
|---|---|---|---|
| Linux (cloud) | 22.22 | Chromium 1243 (Puppeteer 25) | ~24 s |
| Windows 11 Home 10.0.26200 (máy local) | 24.16.0, npm 11.13.0 | Chrome for Testing 156.0.8078.47 (Puppeteer 25.16.0 tự tải) | 26–29 s, chạy 3 lần đều 26/26 |

Trên Windows còn kiểm thêm trang thật hust.edu.vn, MongoDB thật và gọi N5 qua IP LAN (xem [Kết quả trên Windows](#kết-quả-trên-windows-máy-local-24102026)).

## Cách chạy lại

```bat
cd crawler-microservices
npm install
npm test                  :: cả 26 ca
npm run test:downloader   :: 23 ca của N5
npm run test:e2e          :: 3 ca đầu-cuối
```

Dùng Chrome có sẵn thay cho Chrome của Puppeteer: `set CHROME_PATH=...` trước khi chạy. Nếu máy không chạy được Chrome,
các ca cần Chrome (S02, S03, S05) hiện `skipped`, không bị tính là đạt.

## Cách kiểm thử

Mọi ca dịch vụ và đầu-cuối đều chạy **qua HTTP thật trên cổng mạng**, không gọi hàm nội bộ:

- **Website giả lập** (`services/downloader/test/fixtures/site.js`) có: trang tiếng Việt, trang mà nội dung **chỉ xuất hiện sau
  khi JavaScript chạy**, PDF, PDF không có đuôi (`Content-Disposition: attachment`), tệp txt, chuyển hướng 302, trang 404,
  trang chậm, máy chủ treo, tệp 50 MB. Đếm số lần mỗi đường dẫn bị tải (`hits`).
- **Đọc nội dung giả** ghi lại mọi gói N5 gửi sang, có thể giả lập lỗi 500.
- Ca đầu-cuối khởi động **đủ 6 mô-đun** (Điều khiển, 2 bản Tải về N5, Đọc nội dung, Tổng hợp, Lưu trữ), mỗi mô-đun trên cổng riêng.

## Danh sách ca kiểm thử

### Kiểm thử đơn vị (`services/downloader/test/unit.test.js`)

| Mã | Nội dung | Kết quả mong đợi | KQ |
|---|---|---|---|
| U01 | Phân loại URL | `.pdf/.DOCX/.xlsx?v=2` là tài liệu; `/`, `.html`, `/tin-tuc` là trang web | Đạt |
| U02 | Nhận dạng định dạng | Theo Content-Type trước (PDF không đuôi → `pdf`, MIME docx → `docx`), sau đó theo đuôi URL, cuối cùng `other` | Đạt |
| U03 | Giải mã ký tự | UTF-8 tiếng Việt giữ nguyên; charset lấy từ header hoặc `<meta charset>` | Đạt |
| U04 | Gói `/parse` cho HTML | Đủ 5 trường đặc tả `url, format, content_type, encoding, content`; `format=html`, `encoding=text` | Đạt |
| U05 | Gói `/parse` cho tệp nhị phân | `encoding=base64`, giải mã lại đúng từng byte | Đạt |
| U06 | Tệp văn bản | `text/plain` → `format=txt`, `encoding=text` | Đạt |

### Kiểm thử dịch vụ N5 qua HTTP (`services/downloader/test/service.test.js`)

| Mã | Nội dung | Kết quả mong đợi | KQ |
|---|---|---|---|
| S01 | `GET /ping` khi rảnh | `{"status":"đang chờ"}`, `active=0`, `queued=0` | Đạt |
| S02 | `POST /load` trang HTML | Đọc nội dung nhận đúng 1 gói `POST /parse`, `Content-Type: application/json`, đủ trường đặc tả, tiếng Việt nguyên vẹn, `render=chrome` | Đạt |
| S03 | **Trang sinh nội dung bằng JavaScript** | Chrome: HTML gửi đi chứa nội dung và liên kết do JS tạo. HTTP thuần: chỉ có "Đang tải…". **Chứng minh Puppeteer hoạt động đúng thiết kế** | Đạt |
| S04 | Tài liệu PDF | Tải trực tiếp (không qua Chrome), `format=pdf`, `encoding=base64`, **SHA-256 trùng tệp gốc** | Đạt |
| S05 | PDF không có đuôi (`/download?id=1`, tải dạng tệp) | Chrome nhận ra không phải HTML, chuyển sang tải trực tiếp: `format=pdf`, SHA-256 trùng | Đạt |
| S06 | Chuyển hướng 302 | `url` = địa chỉ yêu cầu, `final_url` = đích, nội dung là trang đích | Đạt |
| S07 | Trang 404 | Không gửi sang Đọc nội dung; `/stats` có `skipped_http_error=1` và lịch sử | Đạt |
| S08 | Yêu cầu sai | Thiếu `url` → 400; JSON hỏng → 400 "JSON không hợp lệ"; `ftp://` → 400; đường dẫn lạ → 404; không gửi gì đi | Đạt |
| S09 | Bận / hàng đợi đầy | `CONCURRENCY=1, QUEUE_MAX=1`: việc 1 → 202, `/ping` = "đang bận"; việc 2 → 202 (vào hàng đợi); việc 3 → **503 "đang bận"**; xong thì `/ping` về "đang chờ", gửi đủ 2 gói | Đạt |
| S10 | Xử lý song song | `CONCURRENCY=3`, 3 trang mỗi trang chậm 600 ms → xong trong < 1,5 s (thực tế ~620 ms) | Đạt |
| S11 | Đọc nội dung không phản hồi | Thử 1 + 2 lần lại = 3 lần, ghi `failed_forward=1`, dịch vụ vẫn "đang chờ" | Đạt |
| S12 | Đọc nội dung lỗi 500 tạm thời | Thử lại và gửi thành công (≥ 2 lần) | Đạt |
| S13 | Máy chủ treo | Dừng sau `NAV_TIMEOUT_MS=700`, `kind=timeout`, không gửi | Đạt |
| S14 | Tệp quá lớn (50 MB > `MAX_BYTES=1 MB`) | Từ chối theo `Content-Length`, `kind=too-large`, không gửi | Đạt |
| S15 | Host không tồn tại | `kind=network` | Đạt |
| S16 | Chrome hỏng, chế độ `auto` | Tự lùi về HTTP, vẫn gửi được; `/stats` báo `render=http` và `chrome_error` | Đạt |
| S17 | URL trùng trong hàng đợi | Chỉ tải và gửi 1 lần, lần 2 trả "đã có trong hàng đợi" | Đạt |

### Kiểm thử đầu-cuối 6 mô-đun (`test/e2e/pipeline.test.js`)

| Mã | Nội dung | Kết quả mong đợi | KQ |
|---|---|---|---|
| E01 | Chạy toàn hệ thống từ 1 URL mầm | Lưu trữ có **3 html** (`/`, `/a`, `/b`), **2 other** (`doc.pdf`, `notes.txt`), **8 cạnh** graph; tiêu đề, intro, nội dung tiếng Việt đúng; liên kết ngoài miền ghi vào graph nhưng **không bị tải**; **mỗi trang chỉ tải 1 lần**; Điều khiển xoay vòng nên **cả 2 bản N5 đều nhận việc** | Đạt |
| E02 | Một bản Tải về chết (cổng 9) | Điều khiển đánh dấu `up=false`, vẫn thu thập đủ 3 html + 2 other bằng các bản còn lại | Đạt |
| E03 | Văn bản liên kết | Là câu hoàn chỉnh chứa thẻ `<a>` ("Xem thông báo tuyển sinh mới nhất."), hoặc văn bản thẻ nếu khối không có câu | Đạt |

## Kết quả chạy (Linux, cloud)

```
✔ S01 GET /ping khi rảnh trả {"status":"đang chờ"} (355ms)
✔ S02 POST /load trang HTML -> gửi sang /parse đúng đặc tả (1472ms)
✔ S03 trang sinh nội dung bằng JavaScript: Chrome lấy được, HTTP thuần thì không (1468ms)
✔ S04 tài liệu PDF: tải trực tiếp, base64, toàn vẹn từng byte (SHA-256) (10ms)
✔ S05 tài liệu không có đuôi trong URL (Content-Disposition: attachment) vẫn nhận ra là PDF (411ms)
✔ S06 chuyển hướng 302: url giữ nguyên địa chỉ yêu cầu, final_url là đích (1441ms)
✔ S07 trang 404: không gửi sang Đọc nội dung, ghi vào /stats (1436ms)
✔ S08 yêu cầu sai: thiếu url, JSON hỏng, giao thức không hỗ trợ, đường dẫn lạ (7ms)
✔ S09 bận: /ping "đang bận" khi hết chỗ, hàng đợi đầy thì /load trả 503, xong thì về "đang chờ" (1621ms)
✔ S10 xử lý song song: CONCURRENCY=3 tải 3 trang chậm cùng lúc (618ms)
✔ S11 Đọc nội dung không phản hồi: thử lại rồi ghi lỗi, dịch vụ vẫn chạy (71ms)
✔ S12 Đọc nội dung lỗi 500 lần đầu: thử lại và gửi được (108ms)
✔ S13 máy chủ treo: dừng sau NAV_TIMEOUT_MS, báo lỗi timeout (706ms)
✔ S14 tệp vượt MAX_BYTES bị từ chối, không gửi đi (8ms)
✔ S15 lỗi mạng (host không tồn tại) báo lỗi network (4ms)
✔ S16 chế độ auto: Chrome không khởi động được thì tự lùi về HTTP (24ms)
✔ S17 cùng URL gửi hai lần khi còn trong hàng đợi chỉ tải một lần (459ms)
✔ U01 … U06 (6 ca đơn vị, mỗi ca < 2ms)
✔ E01 toàn hệ thống: từ URL mầm thu thập đủ trang trong miền, lưu đúng cấu trúc html/other/graph (4480ms)
✔ E02 một bản Tải về chết: Điều khiển bỏ qua, hệ thống vẫn thu thập đủ (4273ms)
✔ E03 văn bản liên kết là câu hoàn chỉnh chứa thẻ <a> (4254ms)
ℹ tests 26   pass 26   fail 0   skipped 0   duration_ms 23761
```

Ngoài ra đã kiểm tra thêm:
- **Tách mô-đun:** sao chép riêng thư mục `services/downloader` ra chỗ khác, `npm install` rồi `npm test` → 23/23 đạt. Mô-đun không phụ thuộc mã nào khác trong repo.
- **Chạy nhiều tiến trình:** `node scripts/start-all.js` (6 tiến trình, đúng cổng 2200/6600/8800/8801/5200/3300) trên website giả lập
  → Lưu trữ `{"html":3,"other":2,"graph":8}`, Điều khiển chia việc cho 2 bản N5 (2 và 3 việc).

## Kết quả trên Windows (máy local, 24/10/2026)

Không phải sửa mã hay test nào. `npm install` tự tải Chrome cho Puppeteer (không cần `PUPPETEER_SKIP_DOWNLOAD`/`CHROME_PATH`).

**`npm test`**: 26/26 đạt cả 3 lần chạy. Các ca có thể khác trên Windows:

| Ca | Lo ngại | Thực tế trên Windows |
|---|---|---|
| S11, S15, E02 (cổng 9 giả lập máy chết) | Kết nối có thể bị timeout thay vì bị từ chối ngay | Trên `127.0.0.1` bị từ chối ngay: S11 ~90 ms, S15 ~5 ms, E02 ~4,5 s (như Linux) |
| S10 (song song, ngưỡng < 1500 ms) | Đo thời gian | 615–636 ms |
| S13 (timeout 700 ms, ngưỡng < 3000 ms) | Đo thời gian | 712–724 ms |

**`npm run smoke:hust`** (trang thật, ~9 s):

| URL | HTTP | format | render | ký tự | ms | Kết quả |
|---|---|---|---|---|---|---|
| https://hust.edu.vn/ | 200 | html | chrome | 211.682 | 3441 | đã gửi sang đọc nội dung |
| https://hust.edu.vn/vi/news/tin-tuc-su-kien/ | 200 | html | chrome | 96.574 | 1588 | đã gửi sang đọc nội dung |
| https://ctt.hust.edu.vn/ | 200 | html | chrome | 31.328 | 1448 | đã gửi sang đọc nội dung |
| https://hust.edu.vn/khong-ton-tai-404 | 404 | html | chrome | 3.096 | 1215 | bỏ qua (HTTP 404) |

`data/smoke-hust.json` có 3 gói, mỗi gói đủ `url, format, content_type, encoding, content` (cùng `final_url, http_status, fetched_at,
render, downloader`). `content_type` là `text/html; charset=utf-8`, `encoding=text`, tiêu đề tiếng Việt đúng ("Đại học Bách khoa Hà Nội",
"Tin tức - sự kiện", "CTT ĐHBKHN"), không có ký tự lỗi font. Thử thêm tài liệu thật:
`nq.24.2025.pdf` (5.742.217 byte) → `format=pdf`, `encoding=base64`, 7.656.292 ký tự (đúng bằng độ dài base64 của tệp), `render=http`;
một tệp `.xlsx` của library.hust.edu.vn → `format=xlsx`, base64.

**`npm run start:all -- --max-urls 30`** (6 tiến trình, trang thật): xong 30/30 URL sau ~25 s. Điều khiển chia 13/17 việc cho
N5-1/N5-2, không lỗi tải, không lỗi gửi. Lưu trữ (tệp): `html` 30, `other` 0 (30 URL đầu không có tài liệu nào), `graph` 3.693 cạnh,
3.531 cạnh có văn bản liên kết ("Trang nhất", "Tuyển dụng"…). Tiêu đề và nội dung tiếng Việt đúng. 1 bản ghi `content` rỗng là
`mail.hust.edu.vn` (trang đăng nhập Outlook), do bộ lọc miền của Tổng hợp chứ không phải lỗi N5.
Dừng bằng Ctrl+C thật gửi vào console: cả 6 tiến trình con dừng, không còn tiến trình node hay Chrome của Puppeteer nào sót lại.

**MongoDB** (`scripts\mongo.bat`, MongoDB 7.0.14, driver 7.7.0): `start:all -- --max-urls 30 --mongo mongodb://localhost:27017`
→ database `hust_crawl`: `html` 30, `other` 0, `graph` 3.693 (3.531 có `intro`), index duy nhất `{url}` và `{src, dest}` đúng thiết kế,
tiếng Việt đúng.

**N5 chạy độc lập** (`services/downloader`: `npm install`, `npm test` 23/23 trong ~12 s, `npm start` với `HOST=0.0.0.0`):
gọi bằng IP Wi-Fi `192.168.72.4:8800`: `/ping` → `{"status":"đang chờ",…}`; `/load?wait=1` trang chủ → 200, html, chrome, đã gửi
sang Đọc nội dung giả; `/load` PDF → 202 rồi gửi gói `pdf`/base64. Gọi từ máy ảo WSL (mạng Hyper-V, đi qua tường lửa Windows)
cũng nhận được `/ping`. Ctrl+C dừng sạch, đóng Chrome.

## Chưa kiểm thử được

- **Gọi từ một máy vật lý khác trong LAN:** mới gọi bằng IP LAN từ chính máy và từ WSL. Khi lên LAN chỉ thay địa chỉ trong biến môi
  trường (`PARSER_URL`, `DOWNLOADERS`…), mã không đổi. Máy khác gọi vào cần mở cổng 8800 trên tường lửa (xem `services/downloader/README.md`).
- **Máy chết thật trên LAN:** cổng 9 trên `127.0.0.1` bị từ chối ngay. Máy tắt hẳn trên LAN thường làm kết nối chờ đến hết
  `FORWARD_TIMEOUT_MS` (15 s × 4 lần thử), nên một chỗ tải bị giữ khoảng 1 phút trước khi ghi lỗi.
- **Ghép với mô-đun thật của nhóm khác:** Điều khiển, Đọc nội dung, Tổng hợp, Lưu trữ ở đây là bản tham chiếu.
