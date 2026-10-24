# Hướng dẫn tái lập: kiểm tra trực quan mô-đun Tải về (N5) qua LAN

Mục đích: chứng minh cổng 8800 của N5 hoạt động **qua mạng**, đúng đặc tả. Cụ thể là nhận `POST /load`, trả `GET /ping`
và gửi gói đúng 5 trường sang `POST /parse`. Không cần các nhóm khác, không sửa mã N5.

Công cụ dùng: `scripts/demo-n5.js` (lệnh `npm run demo:n5`), gồm hai phần:

| Cổng | Vai trò |
|---|---|
| **7000** | Trang web kiểm tra. Máy chủ của trang gọi hộ N5 vì N5 không bật CORS, nên trình duyệt không gọi thẳng được. |
| **6600** | Đọc nội dung giả. Nhận `POST /parse` từ N5, kiểm tra 5 trường đặc tả và hiện lên trang. |

```
Trình duyệt ──▶ demo :7000 ──GET /ping, POST /load, GET /stats──▶ N5 :8800 ──POST /parse──▶ demo :6600
```

Đã kiểm trên Windows 11, Node.js 24.16.0 ngày 24/10/2026. Kết quả xem mục 6.

## 1. Chuẩn bị (một lần)

Cần **Node.js ≥ 18.17**. Kiểm tra bằng `node --version`.

```bat
cd crawler-microservices
npm install
cd services\downloader
npm install
```

`npm install` lần đầu sẽ tự tải Chrome cho Puppeteer (~170 MB).

Lấy IP LAN của máy bằng `ipconfig`, xem dòng `IPv4 Address` của card **Wi-Fi** hoặc **Ethernet**. Đừng lấy IP của
vEthernet (WSL), Radmin VPN hay Local Area Connection* (điểm phát). Trong hướng dẫn này IP mẫu là `192.168.72.4`. Trang
kiểm tra cũng liệt kê sẵn các IP của máy kèm tên card để bấm chọn nhanh.

Kiểm tra nhanh trước khi demo (ở thư mục `crawler-microservices`):

```bat
npm test            :: phải ra 26/26 đạt
```

## 2. Cách A: demo trên một máy

Mở **hai cửa sổ terminal**.

**Terminal 1: chạy N5**

```bat
cd crawler-microservices\services\downloader
npm start
```

Mặc định N5 nghe tại `0.0.0.0:8800` và gửi gói sang `http://127.0.0.1:6600/parse`. Đúng chỗ trang kiểm tra đang nghe,
nên không cần đặt biến môi trường. Thấy dòng `Tải về (N5) nghe tại http://0.0.0.0:8800 …` là được.

**Terminal 2: chạy trang kiểm tra**

```bat
cd crawler-microservices
npm run demo:n5 -- --n5 http://192.168.72.4:8800
```

Mở trình duyệt vào `http://localhost:7000`.

> Dùng IP LAN trong `--n5`, đừng dùng `localhost`, để yêu cầu đi qua card mạng thật.

## 3. Các bước bấm trên trang

1. **Ô 1 (Địa chỉ N5):** thấy chấm xanh **"đang chờ"** kèm vài ms, cùng `id`, `sức chứa`, `render=auto`. Trang tự gọi
   `GET http://192.168.72.4:8800/ping` mỗi giây.
2. **Ô 2 (Gửi lệnh /load):** bấm nút mẫu rồi bấm **"Gửi /load?wait=1"**. Lần lượt thử:

   | Nút mẫu | Kết quả mong đợi |
   |---|---|
   | ctt.hust.edu.vn | HTTP 200, `format=html`, `render=chrome`, `result="đã gửi sang đọc nội dung"` |
   | PDF | HTTP 200, `format=pdf`, `encoding=base64`, `render=http` |
   | Trang 404 | `http_status=404`, `result="bỏ qua (HTTP 404)"`, **không** có gói mới ở ô 3 |
   | URL sai (ftp) | HTTP **400**, `thiếu hoặc sai "url" (chỉ nhận http/https)` |

3. **Ô 3 (Gói Đọc nội dung giả nhận được):** mỗi gói có 5 nhãn xanh `✓ url ✓ format ✓ content_type ✓ encoding ✓ content`.
   - Gói HTML: tiêu đề tiếng Việt, ví dụ "CTT ĐHBKHN", kèm trích đoạn nội dung.
   - Gói PDF: dung lượng, 8 byte đầu `%PDF-1.7` và SHA-256. Có thể đối chiếu với tệp tải thẳng (Git Bash):

     ```bash
     curl -s "https://library.hust.edu.vn/sites/default/files/DM%20397%20Ebook%20Sciendirect%20.pdf" | sha256sum
     ```

     Hai mã SHA-256 phải trùng khớp tuyệt đối.
4. **Ô 4 (/stats):** các bộ đếm tăng dần, có lịch sử từng việc (giờ, URL, kết quả, HTTP, format, render, ms).
5. **Thử máy chết:** dừng N5 ở Terminal 1 (Ctrl+C). Trong 1 giây, ô 1 chuyển đỏ **"không liên lạc được · ECONNREFUSED"**.
   Chạy lại `npm start` thì ô 1 về xanh.
6. **Thử "đang bận"** (tuỳ chọn): bấm **"Gửi /load (không chờ)"** liên tục vài lần với các URL khác nhau. Ô 1 thoáng chuyển
   cam "đang bận". Nếu hàng đợi đầy, N5 trả **503**.

## 4. Cách B: chứng minh qua LAN với máy thứ hai (thuyết phục nhất)

Cách A vẫn chạy trên một máy, nên gói tin không đi qua tường lửa. Để chứng minh N5 nhận lệnh từ **máy khác** và gửi gói sang
**máy khác**:

- **Máy N5 (máy mình):** IP ví dụ `192.168.72.4`.
- **Máy kiểm tra (máy bạn cùng nhóm):** IP ví dụ `192.168.72.10`. Máy này cần clone repo và chạy `npm install` trong
  `crawler-microservices`.

**Bước 1: mở cổng 8800 trên máy N5.** Mở cmd bằng **Run as administrator** rồi chạy:

```bat
netsh advfirewall firewall add rule name="N5 Tai ve 8800" dir=in action=allow protocol=TCP localport=8800 remoteip=localsubnet profile=any
```

Máy kiểm tra cũng cần mở cổng **6600** để N5 gửi gói sang được:

```bat
netsh advfirewall firewall add rule name="Demo N5 doc noi dung 6600" dir=in action=allow protocol=TCP localport=6600 remoteip=localsubnet profile=any
```

Xoá luật sau buổi demo: `netsh advfirewall firewall delete rule name="N5 Tai ve 8800"` (tương tự cho luật 6600).

**Bước 2: máy kiểm tra chạy trang**

```bat
cd crawler-microservices
npm run demo:n5 -- --n5 http://192.168.72.4:8800
```

**Bước 3: máy N5 chạy N5, trỏ `PARSER_URL` sang máy kiểm tra**

```bat
cd crawler-microservices\services\downloader
set PARSER_URL=http://192.168.72.10:6600/parse
npm start
```

PowerShell: `$env:PARSER_URL="http://192.168.72.10:6600/parse"; npm start`

**Bước 4:** trên máy kiểm tra, mở `http://localhost:7000` và làm như mục 3. Bằng chứng qua LAN:

- Ô 1 xanh "đang chờ" với địa chỉ `http://192.168.72.4:8800/ping`, tức là chiều **máy khác → N5** chạy được.
- Ô 3 có gói ghi **"từ 192.168.72.4"**, tức là chiều **N5 → máy khác** chạy được.
- Ô 4 có dòng `PARSER_URL của N5 = http://192.168.72.10:6600/parse`.

**Cách nhanh nhất, không cần cài gì:** dùng điện thoại hoặc laptop cùng Wi-Fi, mở trình duyệt vào
`http://192.168.72.4:8800/ping`. Thấy `{"status":"đang chờ",…}` là cổng 8800 đã mở ra mạng. Mở `/stats` để xem lịch sử.

## 5. Lỗi thường gặp

| Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `Không mở được cổng 6600 … (EADDRINUSE)` | Đã có tiến trình khác giữ cổng 6600: một trang kiểm tra khác đang chạy, `start:all` đang chạy, hoặc mô-đun Đọc nội dung thật. | Tìm tiến trình đang giữ cổng: `Get-NetTCPConnection -LocalPort 6600 -State Listen \| Select-Object OwningProcess`, rồi tắt đi. Hoặc dùng cổng khác: `npm run demo:n5 -- --n5 … --parser-port 6601 --port 7001`, khi đó N5 phải đặt `PARSER_URL=http://…:6601/parse`. |
| `EADDRINUSE` ở cổng 8800 khi `npm start` | Đã có một N5 khác đang chạy (cửa sổ khác, `start:all`). | Tắt bản kia, hoặc `set PORT=8802` rồi `--n5 http://IP:8802`. |
| Ô 1 đỏ, `ECONNREFUSED` | N5 chưa chạy, hoặc sai cổng. | Chạy `npm start` ở `services\downloader`, kiểm tra cổng trong `--n5`. |
| Ô 1 đỏ, `UND_ERR_CONNECT_TIMEOUT` hoặc `TimeoutError` (Cách B) | Tường lửa máy N5 chặn 8800, sai IP, hoặc Wi-Fi bật "client isolation" (Wi-Fi công cộng, Wi-Fi trường). | Mở cổng (mục 4 bước 1). Kiểm tra IP bằng `ipconfig`. Thử điểm phát từ điện thoại hoặc router riêng. |
| Ô 2 báo `lỗi gửi sang đọc nội dung`, ô 3 không có gói | `PARSER_URL` của N5 không trỏ về máy chạy trang, hoặc cổng 6600 của máy đó bị tường lửa chặn. | Xem dòng "PARSER_URL của N5" ở ô 4, sửa rồi chạy lại N5. Mở cổng 6600 trên máy kiểm tra. |
| `lỗi tải … net::ERR_CONNECTION_TIMED_OUT` | Trang đích không vào được từ máy này. Ngày 24/10/2026 `hust.edu.vn` từng timeout, kể cả khi gọi bằng `curl`, trong khi ctt và library vẫn vào được. | Không phải lỗi N5. N5 ghi lỗi đúng (`kind=network`). Dùng nút mẫu ctt.hust.edu.vn hoặc PDF của library. |
| Gửi `/load?wait=1` lâu khoảng 20 s mới trả về | Trang đích chậm hoặc treo. N5 chờ tối đa `NAV_TIMEOUT_MS` (30 s). | Chờ, hoặc đặt `NAV_TIMEOUT_MS` nhỏ hơn khi chạy N5. |

## 6. Kết quả đã chạy (Windows 11, 24/10/2026)

Cách A, N5 gọi qua `192.168.72.4:8800`:

| Thao tác | Kết quả |
|---|---|
| `/ping` | "đang chờ", 1–2 ms |
| `/load?wait=1` ctt.hust.edu.vn | HTTP 200, html, chrome, 31.328 ký tự, 1.460 ms. Gói có đủ 5 trường, tiêu đề "CTT ĐHBKHN". |
| `/load?wait=1` PDF library | 200, pdf, base64, 713.570 byte. 8 byte đầu `%PDF-1.7`, SHA-256 `2ced9837…78374` trùng tệp tải thẳng bằng `curl`. |
| `/load?wait=1` ctt…/khong-ton-tai-404 | 404, "bỏ qua (HTTP 404)", không gửi gói |
| `/load?wait=1` hust.edu.vn | lỗi tải `ERR_CONNECTION_TIMED_OUT` sau 21,8 s, do trang đích không vào được (`curl` cũng timeout) |
| Dừng N5 | Ô 1 đỏ "ECONNREFUSED", bật lại thì xanh |
| Giao diện khổ điện thoại 375 px | Không tràn ngang |

Ngoài ra, máy ảo WSL (mạng Hyper-V, đi qua tường lửa Windows) gọi `http://192.168.72.4:8800/ping` cũng được.
**Chưa thử** Cách B với một máy vật lý khác.

## 7. Dừng

Bấm Ctrl+C ở từng terminal. N5 tự đóng Chrome. Đã kiểm trên Windows: dừng xong không còn tiến trình node hay Chrome của
Puppeteer nào sót lại.
