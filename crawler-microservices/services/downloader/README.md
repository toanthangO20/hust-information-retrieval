# Mô-đun Tải về (N5)

Mô-đun **Tải về** trong hệ thống thu thập theo kiến trúc dịch vụ nhỏ (`docs/Thiết kễ hệ thống thu thập dựa trên kiến trúc dịch vụ nhỏ.pptx`).
Nhận URL từ **Điều khiển**, tải nội dung (trang web bằng **Puppeteer + Chrome headless**, tài liệu bằng tải trực tiếp) và
đẩy kết quả sang **Đọc nội dung** qua HTTP + JSON.

```
Điều khiển ──POST /load {url}──▶ [ Tải về N5 :8800 ] ──POST /parse {url, format, content_type, encoding, content}──▶ Đọc nội dung :6600
           ◀──GET /ping──────────
```

Thư mục này **tự chứa**: không phụ thuộc mã nào khác trong repo, sao chép ra máy khác là chạy được.

## 1. Cài đặt và chạy

Cần Node.js ≥ 18.17 (khuyên dùng 20 hoặc 22).

```bat
cd services\downloader
npm install                 :: tải luôn Chrome for Testing cho Puppeteer (~170 MB, chỉ lần đầu)
npm test                    :: 23 ca kiểm thử (xem docs/KIEM_THU.md)
npm start                   :: nghe tại 0.0.0.0:8800
```

Chạy trên mạng LAN, chỉ đến máy của nhóm Đọc nội dung (N1):

```bat
:: Windows (cmd)
set PARSER_URL=http://192.168.1.21:6600/parse
set PORT=8800
npm start
```

```powershell
# Windows (PowerShell)
$env:PARSER_URL="http://192.168.1.21:6600/parse"; npm start
```

Mở cổng trên tường lửa Windows (chạy cmd bằng quyền Administrator) để máy Điều khiển gọi được vào:

```bat
netsh advfirewall firewall add rule name="N5 Tai ve 8800" dir=in action=allow protocol=TCP localport=8800
```

Nếu đã có sẵn Chrome hoặc Edge và không muốn Puppeteer tải Chrome riêng: `set PUPPETEER_SKIP_DOWNLOAD=1` trước khi
`npm install`, rồi `set CHROME_PATH=C:\Program Files\Google\Chrome\Application\chrome.exe`.

## 2. Giao diện dịch vụ (API)

### `POST /load`: lệnh tải URL (theo đặc tả)

```json
{"url": "https://hust.edu.vn/tin-tuc"}
```

| Trả về | Khi nào |
|---|---|
| `202 {"status":"đã nhận","url":...,"queued":n,"active":n}` | Đã xếp vào hàng đợi, sẽ tải và gửi sang Đọc nội dung |
| `202 {"status":"đã nhận","note":"đã có trong hàng đợi"}` | URL đang chờ trong hàng đợi, không tải lần hai |
| `503 {"status":"đang bận","error":"hàng đợi đầy, thử lại sau"}` | Đang chạy `CONCURRENCY` việc và hàng đợi đã đủ `QUEUE_MAX` |
| `400 {"status":"lỗi","error":...}` | Thiếu `url`, JSON hỏng, hoặc không phải http/https |

Thêm `?wait=1` (`POST /load?wait=1`) thì dịch vụ chờ tải và gửi xong mới trả lời, kèm kết quả. Dùng để demo hoặc gỡ lỗi:

```json
{"status":"xong","url":"https://hust.edu.vn/","http_status":200,"format":"html","content_type":"text/html; charset=utf-8",
 "encoding":"text","render":"chrome","final_url":"https://hust.edu.vn/","chars":181234,
 "forward":{"ok":true,"status":200,"attempts":1},"result":"đã gửi sang đọc nội dung","ms":2140}
```

### `GET /ping`: kiểm tra trạng thái (theo đặc tả)

```json
{"status": "đang chờ", "id": "N5-1", "active": 0, "queued": 0, "capacity": 2, "render": "chrome"}
```

`status` là `"đang chờ"` khi còn chỗ nhận việc ngay (`active + queued < CONCURRENCY`), là `"đang bận"` khi hết chỗ.
Điều khiển chỉ cần đọc `status`. Các trường còn lại để theo dõi.

### `GET /stats`: số liệu và 100 việc gần nhất

`stats.received / downloaded / forwarded / failed_download / failed_forward / skipped_http_error / rejected_busy / bytes`,
`history[]` (URL, mã HTTP, định dạng, cách tải, thời gian, kết quả, lỗi), `chrome_error` (nếu Chrome không chạy được).

## 3. Gói gửi sang Đọc nội dung: `POST {PARSER_URL}`

```json
{
  "url": "https://hust.edu.vn/vi/news/tin-tuc-su-kien/abc-654601.html",
  "format": "html",
  "content_type": "text/html; charset=utf-8",
  "encoding": "text",
  "content": "<!DOCTYPE html>…",

  "final_url": "https://hust.edu.vn/vi/news/tin-tuc-su-kien/abc-654601.html",
  "http_status": 200,
  "fetched_at": "2026-10-24T04:09:41.998Z",
  "render": "chrome",
  "downloader": "N5-1"
}
```

| Trường | Ý nghĩa |
|---|---|
| `url` | URL được yêu cầu, giữ nguyên như Điều khiển gửi |
| `format` | `html` cho trang web; với tài liệu là đuôi tệp: `pdf`, `docx`, `xlsx`, `pptx`, `txt`… Lấy theo Content-Type, không có thì theo đuôi URL, cuối cùng là `other` |
| `content_type` | Header `Content-Type` máy chủ trả về |
| `encoding` | `text`: `content` là chuỗi (HTML, txt, csv). `base64`: `content` là tệp nhị phân mã hoá base64 (PDF, Office…) |
| `content` | HTML **sau khi JavaScript chạy** (DOM do Chrome dựng) hoặc nội dung tệp |
| `final_url`… `downloader` | **Bổ sung**, ngoài đặc tả. Bên nhận có thể bỏ qua. `final_url` là địa chỉ sau chuyển hướng, cần để giải liên kết tương đối cho đúng |

5 trường đầu khớp đúng ví dụ của lệnh `/parse` trong slide 13. Giá trị `encoding: "base64"` cho tài liệu nhị phân là đề xuất
của nhóm, vì đặc tả chưa nói cách gửi tệp nhị phân (xem `docs/DAC_TA_GIAO_TIEP.md`).

## 4. Cách xử lý

1. **Phân loại.** URL có đuôi tài liệu (`pdf, doc(x), xls(x), ppt(x), odt, rtf, txt, csv, zip…`) được tải trực tiếp. Các URL còn lại
   mở bằng Chrome headless (`page.goto`, chờ `networkidle2`, chặn ảnh/phông/video để nhanh hơn) và lấy `page.content()`.
2. Nếu Chrome nhận về tài nguyên **không phải HTML** (ví dụ PDF không có đuôi, `Content-Disposition: attachment`), tải lại
   trực tiếp và gửi như tài liệu.
3. Trang trả **4xx/5xx thì không gửi** sang Đọc nội dung (đặt `FORWARD_ERRORS=1` nếu muốn gửi), chỉ ghi vào `/stats`.
4. **Lỗi mạng, quá thời gian** (`NAV_TIMEOUT_MS`) hoặc **quá dung lượng** (`MAX_BYTES`) thì ghi lỗi (`kind`: `network | timeout | too-large`), không gửi.
5. **Gửi sang Đọc nội dung thất bại** (lỗi mạng hoặc 5xx) thì thử lại `FORWARD_RETRIES` lần, độ trễ tăng dần (500 ms, 1 s, 2 s…).
6. **Chrome không khởi động được.** Ở chế độ `RENDER=auto`, dịch vụ tự lùi về tải HTTP thuần và báo trong `/ping` (`"render":"http"`) và `/stats` (`chrome_error`).
7. Một tiến trình Chrome được dùng chung, mỗi việc mở một tab rồi đóng lại; Chrome bị ngắt thì khởi động lại.

Giãn cách giữa các lần tải tới cùng một host thuộc trách nhiệm của Điều khiển (theo thiết kế). N5 chỉ giới hạn số việc chạy song song.

## 5. Cấu hình (biến môi trường)

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PORT` | `8800` | Cổng dịch vụ |
| `HOST` | `0.0.0.0` | Địa chỉ nghe (`0.0.0.0` để máy khác trong LAN gọi được) |
| `PARSER_URL` | `http://127.0.0.1:6600/parse` | Địa chỉ lệnh `/parse` của Đọc nội dung |
| `DOWNLOADER_ID` | `N5-<tên máy>` | Tên bản Tải về (khi chạy nhiều bản) |
| `RENDER` | `auto` | `auto` / `chrome` (bắt buộc Chrome) / `http` (không dùng Chrome) |
| `CHROME_PATH` | (Chrome của Puppeteer) | Đường dẫn Chrome/Edge có sẵn |
| `WAIT_UNTIL` | `networkidle2` | Điều kiện coi trang đã tải xong (`load`, `domcontentloaded`, `networkidle0/2`) |
| `BLOCK_RESOURCES` | `1` | Chặn ảnh/phông/video khi render |
| `CONCURRENCY` | `2` | Số việc tải song song |
| `QUEUE_MAX` | `20` | Số việc tối đa được xếp chờ |
| `NAV_TIMEOUT_MS` | `30000` | Thời gian tối đa cho một lần tải |
| `MAX_BYTES` | `26214400` | Dung lượng tối đa một tệp (25 MB) |
| `USER_AGENT` | `HUST-IR-N5-Downloader/1.0 …` | User-Agent gửi đi |
| `FORWARD_RETRIES` | `3` | Số lần thử lại khi gửi sang Đọc nội dung |
| `FORWARD_TIMEOUT_MS` | `15000` | Thời gian chờ mỗi lần gửi |
| `FORWARD_ERRORS` | `0` | `1` = gửi cả trang 4xx/5xx |

## 6. Thử nhanh bằng tay

```bat
curl http://localhost:8800/ping
curl -X POST http://localhost:8800/load -H "Content-Type: application/json" -d "{\"url\":\"https://hust.edu.vn/\"}"
curl -X POST "http://localhost:8800/load?wait=1" -H "Content-Type: application/json" -d "{\"url\":\"https://hust.edu.vn/\"}"
curl http://localhost:8800/stats
```

PowerShell:

```powershell
Invoke-RestMethod http://localhost:8800/ping
Invoke-RestMethod -Method Post "http://localhost:8800/load?wait=1" -ContentType "application/json" -Body '{"url":"https://hust.edu.vn/"}'
```

Kiểm tra trên trang thật của trường mà không cần nhóm khác (ở thư mục gốc `crawler-microservices`): `npm run smoke:hust`.

## 7. Mã nguồn

| Tệp | Vai trò |
|---|---|
| `src/server.js` | Dịch vụ HTTP: `/load`, `/ping`, `/stats`, hàng đợi, xử lý song song, gửi sang Đọc nội dung có thử lại |
| `src/fetcher.js` | Phân loại URL, Puppeteer + Chrome, tải trực tiếp, nhận dạng định dạng/charset, dựng gói `/parse` |
| `src/config.js` | Đọc cấu hình từ biến môi trường |
| `src/index.js` | Điểm chạy |
| `test/unit.test.js` | 6 ca kiểm thử đơn vị |
| `test/service.test.js` | 17 ca kiểm thử dịch vụ qua HTTP thật (website giả lập + Đọc nội dung giả) |
| `test/fixtures/site.js` | Website giả lập và Đọc nội dung giả |
