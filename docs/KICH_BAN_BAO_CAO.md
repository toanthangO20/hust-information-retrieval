# Kịch bản báo cáo với thầy — HUST Information Retrieval

Cấu trúc mỗi phần: **Đã làm → Show code (file:dòng) → Chạy thử → Kết quả**.
Tổng thời lượng dự kiến ≈ 25–30 phút + hỏi đáp. Mọi lệnh chạy trong terminal PowerShell của VS Code, thư mục gốc dự án.

---

## 0. Chuẩn bị trước buổi báo cáo (làm 5 phút trước)

```powershell
cd "E:\Projects\Information Retrieval\hust-information-retrieval"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot"
# MongoDB phải đang chạy (mở một terminal khác nếu chưa chạy):
#   .\scripts\mongo.bat
Test-NetConnection 127.0.0.1 -Port 27017 | Select-Object TcpTestSucceeded
# Chạy thử 1 lần để chắc chắn không lỗi:
.\scripts\search.bat query ký túc xá bách khoa
```

Mở sẵn các tab trong VS Code (Ctrl+P → gõ tên tệp):
`Frontier.java`, `UrlNormalizer.java`, `SimHash.java`, `Crawler.java`, `BlockFinder.java`, `GraphBuilder.java`,
`MongoLoader.java`, `Bm25Retriever.java`, `HybridRetriever.java`, `Metrics.java`, `Significance.java`.

Mở sẵn 2 trang trực quan trong trình duyệt:

```powershell
Start-Process docs\KET_QUA_TRUC_QUAN.html     # biểu đồ kết quả (tất cả các bước)
Start-Process docs\SCHEMA_TOMTAT.html         # sơ đồ lược đồ MongoDB
```

> Lưu ý: dấu `Ctrl+G` trong VS Code để nhảy tới dòng. Tất cả số dòng dưới đây tính theo bản hiện tại.

---

## 1. Mở đầu (2 phút) — Bài toán và sơ đồ tổng thể

**Nói:**
- Mục tiêu: xây hệ thống thu thập và truy vấn thông tin về ĐH Bách khoa Hà Nội (`hust.edu.vn` + subdomain).
- 4 giai đoạn: **(1) Crawl** → **(2) Dữ liệu + chỉ mục + giao diện** → **(3) Bóc tách nội dung, đồ thị liên kết, MongoDB** → **(4) Đánh giá các mô hình truy hồi**.
- Công nghệ: Java 11, Tika (trích văn bản), Lucene (chỉ mục), jsoup (DOM), MongoDB, ONNX Runtime.

**Chỉ trang trực quan**: phần "0. Quy mô dữ liệu" của `KET_QUA_TRUC_QUAN.html`.
Số liệu: 22.178 URL hợp lệ / 47 host / 16,7 triệu từ; 80.060 nút đồ thị; 1,6 triệu cạnh.

---

## 2. Crawler (5 phút) — slide Ch10 (crawler), Ch09 (trùng lặp)

### 2.1 Đã làm
Frontier kiểu Mercator, robots.txt + giãn cách theo host, chuẩn hoá/lọc URL, đọc sitemap, lọc "có nội dung" (soft-404),
loại trùng (SHA-1 chính xác + SimHash gần đúng), điều chỉnh tốc độ khi bị 429, cập nhật tăng dần.

### 2.2 Show code
| Ý | Tệp:dòng | Nói gì |
|---|---|---|
| Hàng đợi hai tầng | `src/main/java/vn/hust/ir/crawler/Frontier.java:34` | 3 mức ưu tiên (phía trước) |
| Hàng đợi theo host + heap thời điểm | `Frontier.java:70-104` (`take`) | host chưa tới `nextAt` thì chờ → lịch sự (politeness) |
| Giãn cách sau mỗi request | `Frontier.java:106-116` (`release`, dòng 110) | `nextAt = now + delay` |
| Chuẩn hoá URL | `UrlNormalizer.java:27-61`, tham số rác dòng 17 và 70 | bỏ `utm_*`, `fbclid`, sessionid; chống bẫy |
| Soft-404 và ngưỡng "có nội dung" | `Crawler.java:27`, `Crawler.java:388-394` | < 200 ký tự / < 30 từ hoặc mẫu "không tìm thấy" |
| Trùng chính xác (SHA-1) | `Crawler.java:407`, `Crawler.java:456` | băm văn bản đã chuẩn hoá |
| Trùng gần đúng (SimHash) | `SimHash.java:18-29` (tính), `SimHash.java:31` (khoảng cách Hamming), `Crawler.java:419` | shingle 3 từ, 64 bit, ngưỡng Hamming ≤ 3 |
| Giảm tốc khi 429 | `Crawler.java:327-340` | AIMD theo host: nhân đôi khi lỗi, giảm nửa khi thành công |

### 2.3 Chạy thử (≈ 2 giây)
```powershell
.\scripts\crawl.bat stats | Select-Object -First 15      # thống kê theo host: pending / valid / invalid
Get-Content output\urls_valid.txt -TotalCount 5; (Get-Content output\urls_valid.txt).Count
Get-Content output\update_log.tsv                         # nhật ký 7 lượt chạy
```
(Nếu muốn minh hoạ lọc: `.\scripts\crawl.bat inspect <url>` — cần mạng.)

### 2.4 Kết quả (nói + chỉ `output/`)
- 22.178 URL hợp lệ, 12.589 bị loại (lý do: `no-content`, `duplicate`, `http-404`, `soft-404`, `robots-disallow`…),
  11.159 URL chưa tải (độ phủ chưa đầy đủ vì `hust.edu.vn` giới hạn tốc độ).
- Cơ chế cập nhật: `urls_added_*.txt`, `urls_removed_*.txt`, `update_log.tsv`.
- 7 lượt chạy thật, mỗi lượt sửa lỗi thực tế (ASP.NET `<form>`, server treo, 429, URL nhân đôi tham số).

---

## 3. Bộ dữ liệu ver1, chỉ mục và giao diện (4 phút) — slide Ch04, Ch07

### 3.1 Đã làm
`dataset/ver1` (22.178 tài liệu), làm sạch (sửa chữ vỡ UTF-8, loại 43 tài liệu font TCVN3/VNI), chỉ mục Lucene BM25
bỏ dấu tiếng Việt + trường giữ dấu, giao diện web.

### 3.2 Show code
| Ý | Tệp:dòng | Nói gì |
|---|---|---|
| Bộ phân tích tiếng Việt | `src/main/java/vn/hust/ir/search/ViAnalyzer.java:10, 24` | tách âm tiết (UAX#29) + bỏ dấu (ASCIIFolding) → "tuyen sinh" tìm được "tuyển sinh" |
| Truy vấn AND, nới sang OR | `search/SearchEngine.java:113-116` | không có kết quả đủ từ thì nới `relaxed = true` |
| Thưởng cụm từ / đúng dấu | `SearchEngine.java:167-181` | phrase trên tiêu đề ×4, nội dung ×1,5; trường giữ dấu |
| Trường và trọng số | `SearchEngine.java:77` | title ×3, content ×1, url ×0,6 |

### 3.3 Chạy thử (≈ 1 giây)
```powershell
.\scripts\search.bat query ký túc xá bách khoa
.\scripts\search.bat query học bổng trao đổi sinh viên
```
Hoặc mở giao diện web: `.\scripts\search.bat serve` → http://localhost:8080 (tô sáng, lọc theo trang con/loại).

### 3.4 Kết quả
- 1.073 kết quả, 279 ms (lần đầu); ≈ 19 ms/truy vấn trung bình.
- Giao diện: HTML/CSS/JS thuần, chạy được trên điện thoại.

---

## 4. Bóc tách nội dung: tìm khối nội dung chính (7 phút) — slide Ch10 (BTE)

### 4.1 Đã làm
Phân trang thành token (thẻ/từ) và khối; cài thuật toán **BTE** (Body Text Extraction, O(n)); các baseline (quy tắc
đặc trưng nông, CSS theo host); **bộ phân loại học có giám sát** (hồi quy logistic, 37 đặc trưng); lọc khuôn mẫu theo host;
tập vàng 99 trang chia dev/test.

### 4.2 Show code
| Ý | Tệp:dòng | Nói gì |
|---|---|---|
| BTE: chọn đoạn cực đại hoá hàm mục tiêu | `src/main/java/vn/hust/ir/extract/BlockFinder.java:27-78` | chọn [i, j] cực đại: số thẻ trước i + số từ trong [i,j] + số thẻ sau j |
| Vòng lặp O(n) duy nhất | `BlockFinder.java:51-67` (hàm mục tiêu ở dòng 63) | duy trì max `g(i)` chạy dần, không cần O(n²) |
| Phân loại học | `BlockFinder.java:219-245` (dòng 242 = `prob ≥ threshold`) | mỗi khối → xác suất là nội dung |
| Hồi quy logistic tự cài | `extract/LogReg.java:19-22` (dự đoán), `LogReg.java:26` (huấn luyện) | chuẩn hoá z-score, L2, trọng số mẫu |
| Lọc khuôn mẫu theo host | `BlockFinder.java:247`, `Extractor.java:181` | khối xuất hiện ở ≥ 20% trang cùng host bị loại |

### 4.3 Chạy thử (≈ 2 giây)
```powershell
$u = "https://bktextile.hust.edu.vn/tin-tuc1/-/asset_publisher/L3swsazlHgWT/content/youngone-nam-inh-tuyen-dung"
.\scripts\extract.bat show --url $u | Where-Object { $_ -match '^\s*\d+ #|^(title|publishedAt|author|sourceRef) =' }
```
Dấu `#` = khối được chọn là nội dung chính; `.` = khối bị bỏ (menu, chân trang).

### 4.4 Kết quả (tập test 49 trang, F1 mức từ macro) — biểu đồ phần 2 của `KET_QUA_TRUC_QUAN.html`
```powershell
Get-Content eval\extract\report_blocks_test.md | Select-Object -First 22
```
| Cấu hình | F1 macro | F1 micro |
|---|---:|---:|
| Baseline jsoup (mật độ liên kết) | 0,813 | 0,930 |
| BTE thuần (Ch10) | 0,662 | 0,895 |
| Phân loại học | 0,845 | 0,972 |
| **Học + lọc khuôn mẫu** | **0,870** | **0,971** |

**Điểm cần nói thẳng:** BTE thuần *kém* baseline jsoup; thầy dạy BTE nhưng trên trang thật của HUST (menu nhiều từ,
nội dung ngắn như thông báo tuyển dụng) giả định của BTE bị vỡ. Vì vậy phải kết hợp đặc trưng + học máy.

### 4.5 Metadata
```powershell
Get-Content eval\extract\report_meta_test.md | Select-Object -First 12
```
Tiêu đề 0,75 · ngày đăng 0,96 · tác giả 0,96 (độ chính xác); phủ toàn bộ: tiêu đề 94%, ngày 54%, tác giả 14%.
Mỗi trường lưu `source` + `conf` (JSON-LD → meta → DOM → văn bản).

---

## 5. Đồ thị liên kết + MongoDB (5 phút) — phân tích liên kết (Ch10 phần liên kết)

### 5.1 Đã làm
Đồ thị nguồn → đích : văn bản mô tả (anchor), cờ `inContent`, ảnh và tài liệu là nút; quy đích về URL chuẩn;
PageRank hai biến thể; lược đồ MongoDB có `$jsonSchema` + chỉ mục.

### 5.2 Show code
| Ý | Tệp:dòng | Nói gì |
|---|---|---|
| PageRank | `extract/GraphBuilder.java:290-306` (d = 0,85 ở dòng 296; 40 vòng dòng 297; cộng dồn dòng 301) | xử lý nút treo (dangling) bằng phân phối đều |
| Tránh neo vô nghĩa | `GraphBuilder.java:34`, `GraphBuilder.java:348` | bỏ "xem thêm", "chi tiết" |
| Phân loại nút (ảnh/tài liệu/ngoài) | `GraphBuilder.java:32-33`, `GraphBuilder.java:325-335` | theo đuôi tệp và loại cạnh |
| Lược đồ kiểm tra | `extract/MongoLoader.java:32` (pages), `MongoLoader.java:45` (links), `MongoLoader.java:59-62` | `$jsonSchema`, `validationLevel: moderate` |
| Chỉ mục | `MongoLoader.java:69-76` | `url` duy nhất, `(host,kind)`, `pagerankContent`↓, text `title.value` |

Mở thêm `docs/SCHEMA.md` (mô tả từng trường) và `docs/SCHEMA_TOMTAT.html` (sơ đồ).

### 5.3 Chạy thử (≈ 7 giây; cần MongoDB đang chạy)
```powershell
.\scripts\extract.bat mongo-demo
```
In ra: số trang/cạnh theo loại, 5 trang PageRank cao nhất, và một ảnh kèm các trang giới thiệu nó (nguồn + văn bản liên kết).

### 5.4 Kết quả
| Loại nút | Số lượng |
|---|---:|
| html | 34.876 |
| ảnh | 23.284 |
| ngoài miền | 16.855 |
| tài liệu | 5.045 |
| **Tổng** | **80.060** (22.172 thuộc bộ dữ liệu) |

Cạnh: 1.601.816 (69.989 nằm trong nội dung chính).
Nhận xét: PageRank cao nhất là trang chủ và danh mục của `clc.hust.edu.vn` — do site có hàng nghìn bài trỏ về menu;
đây là lý do tách `pagerankContent` (chỉ tính liên kết trong khối nội dung).

---

## 6. Đánh giá truy hồi (7 phút) — slide Ch04, Ch05, Ch06, Ch07

### 6.1 Đã làm
Bộ kiểm thử kiểu TREC: 60 truy vấn (20 dev / 40 test), pool, qrels 3 mức (0/1/2), 4 mô hình theo slide + 2 mô hình mở rộng:

| Mã | Mô hình | Slide |
|---|---|---|
| M1 | VSM tf-idf `lnc.ltc` | Ch04 |
| M2 | BM25 đa trường (title×3, content×1, url×0,6) | Ch07 |
| M3 | BM25 + phản hồi giả định (PRF/RM3) | Ch06 |
| M4 | Hybrid: BM25 + dense (multilingual-e5-small), hợp nhất RRF | ngoài slide |
| M5 | Rocchio trong không gian vec-tơ dày (thử nghiệm) | Ch06 (mở rộng) |
| M6 | Cross-encoder reranker bge-reranker-v2-m3 (thử nghiệm) | ngoài slide |

### 6.2 Show code
| Ý | Tệp:dòng | Nói gì |
|---|---|---|
| VSM `lnc.ltc` | `eval/VsmRetriever.java:17-18` (mô tả), `VsmRetriever.java:94-110` | log tf, chuẩn hoá cosine; truy vấn có idf |
| BM25 từng trường | `eval/Bm25Retriever.java:34` (`BM25Similarity(k1,b)`), `Bm25Retriever.java:38-48` | tổng BM25 các trường có trọng số |
| Hợp nhất RRF | `eval/HybridRetriever.java:39-40` | `w / (rrfK + hạng)` — không cần chuẩn hoá điểm |
| nDCG theo slide | `eval/Metrics.java:16-31` (dòng 19 = `r1 + Σ ri/log2 i`) | IDCG lấy từ toàn bộ qrels |
| Kiểm định ghép cặp | `eval/Significance.java:17-27` (dòng 27 = lật dấu ngẫu nhiên) | hoán vị 20.000 vòng, Holm ở `Significance.java:51` |
| Cross-encoder | `eval/CrossEncoderRetriever.java:66-77` (ghép cặp), `:79-` (chấm), `:106-` (xếp lại) | mô hình đọc đồng thời truy vấn và tài liệu |

### 6.3 Chạy thử (≈ 1 giây, chấm điểm lại từ các tệp run đã lưu)
```powershell
.\scripts\eval.bat eval --runs "eval/runs/test/bm25.run;eval/runs/test/hybrid_w-1.run;eval/runs/test/vsm.run;eval/runs/test/dense.run;eval/runs/test/current.run" --qrels eval/qrels/test.tsv --split test --baseline bm25 --out eval/work/demo_report.md
Get-Content eval\work\demo_report.md -Encoding UTF8 | Select-Object -Skip 6 -First 22
```
(Tuỳ chọn, ≈ 15–20 giây vì phải nạp mô hình 571 MB; dùng cache nên không chấm lại từng cặp:)
```powershell
.\scripts\eval.bat run --systems "ce:depth=30,len=320" --split test --out eval/work/ce_demo
```

### 6.4 Kết quả (tập test, 40 truy vấn) — biểu đồ phần 3, 4, 5 của `KET_QUA_TRUC_QUAN.html`
| Hệ thống | nDCG@10 | MAP | MRR@10 | R@100 |
|---|---:|---:|---:|---:|
| current (công cụ ứng dụng) | 0,573 | 0,346 | 0,787 | 0,448 |
| M1 VSM | 0,492 * | 0,361 | 0,783 | 0,647 |
| M2 BM25 | 0,675 | 0,488 | 0,883 | 0,688 |
| M3 PRF | 0,674 | 0,489 | 0,894 | 0,668 |
| dense | 0,631 | 0,448 | 0,864 | 0,685 |
| **M4 hybrid (w=1)** | **0,701** | 0,585 | 0,953 | 0,876 |
| M6 cross-encoder | 0,702 | 0,600 | 0,921 | 0,876 |

`*` = khác biệt có ý nghĩa so với BM25 (Holm p = 0,006).

**Các kết luận phải nói (đúng thực tế, không thổi phồng):**
1. Chỉ VSM kém hơn BM25 có ý nghĩa thống kê; BM25 ≈ PRF (PRF không giúp).
2. Hybrid cho xu hướng tốt hơn (R@100 cao hơn rõ: 0,69 → 0,88), nhưng nDCG@10 chưa khác biệt có ý nghĩa với 40 truy vấn.
3. Công cụ hiện tại sụp đổ trên truy vấn dạng câu hỏi (nDCG ≈ 0,04) do ngữ nghĩa AND + cụm từ → đã có kết quả này để cải tiến.
4. Văn bản liên kết (anchor) và PageRank **không** cải thiện (phần 5 của biểu đồ) — ghi nhận kết quả âm.
5. Cross-encoder: dev tăng +0,147 (p=0,02) nhưng test ngang hybrid (+0,0006) và chậm ≈ 12 giây/truy vấn → **không kết luận là cải thiện**.

---

## 7. Tổng kết và hạn chế (2 phút)

**Đã đạt:** crawler + cập nhật tăng dần; bộ dữ liệu; chỉ mục + giao diện; bóc tách khối nội dung/metadata; đồ thị 1,6 triệu cạnh;
MongoDB có lược đồ; khung đánh giá có kiểm định thống kê; 36 test (`@Test`) ở 6 lớp.

**Hạn chế phải nói trước khi bị hỏi:**
- Độ phủ: 11.159 URL chưa tải; trang dựng bằng JavaScript bị loại `no-content`.
- **Nhãn đánh giá truy hồi và truy vấn do mô hình ngôn ngữ (Claude) soạn/gán, không có người duyệt** — kappa chỉ đo nhất quán nội bộ.
  Nhãn vàng cho tìm khối nội dung do một người gán. Cần nói rõ, không để thầy phát hiện sau.
- Quy tắc metadata được chỉnh sau khi xem lỗi cả dev và test → số liệu test lạc quan hơn thực tế.
- Chỉ 40 truy vấn test: khoảng tin cậy rộng; pool chưa chứa cross-encoder nên Judged@10 chỉ 0,84.
- Chưa tách từ ghép tiếng Việt (hiện dùng âm tiết) — hướng phát triển ưu tiên.

**Hướng tiếp:** tách từ ghép; mở rộng bộ truy vấn và gán nhãn người thật; gán nhãn bổ sung cho cross-encoder;
chạy `update` để dựng `ver2` với phần 11.159 URL còn lại.

---

## 8. Câu hỏi thầy có thể hỏi — và gợi ý trả lời (bám nội dung môn học)

### A. Crawler (Ch10)
**1. Vì sao frontier hai tầng (ưu tiên phía trước, hàng đợi theo host phía sau)?**
Phía trước chọn URL nào quan trọng trước (ở đây: trang hub, sitemap trước); phía sau đảm bảo lịch sự — mỗi host một hàng đợi
và thời điểm được phép truy cập tiếp theo (`nextAt`); heap chọn host sớm nhất. Một luồng chỉ lấy host đang không bận.

**2. Làm sao biết có được phép tải? robots.txt xử lý thế nào?**
`RobotsCache` tải và lưu `robots.txt` theo host, kiểm tra `Disallow/Allow` cho agent `hustirbot`, tôn trọng `Crawl-delay`
và lấy `Sitemap`. URL bị chặn ghi lý do `robots-disallow`.

**3. Phát hiện trùng lặp thế nào? Vì sao hai mức?**
SHA-1 của văn bản chuẩn hoá bắt trùng *chính xác* (rẻ, tức thì). SimHash 64 bit trên shingle 3 từ, Hamming ≤ 3, bắt trùng
*gần đúng* (ví dụ cùng bài ở hai đường dẫn khác nhau) — đúng như shingling/fingerprint của Ch09. SimHash chỉ gắn cờ,
không tự loại; chỉ loại khi bật `--drop-near-dup` (`Crawler.java:424`).
*Hạn chế có thể bị hỏi:* so sánh SimHash hiện là quét tuyến tính toàn bộ URL hợp lệ (`Crawler.java:418-423`, O(N) mỗi trang) —
với quy mô lớn phải dùng bảng hoán vị bit (Manku et al.) để tra cứu Hamming ≤ 3 nhanh.

**4. Chuẩn hoá URL để làm gì? Có những bẫy nào?**
Đưa các dạng khác nhau của cùng một trang về một dạng (hạ chữ thường host, bỏ fragment, bỏ cổng mặc định, sắp tham số,
bỏ `utm_*`/`sessionid`, mã hoá `%xx` thống nhất). Bẫy: lịch vô hạn, tham số phân trang vô hạn, URL nhân đôi tham số khi chuyển hướng.

**5. Cập nhật tăng dần hoạt động ra sao? Khi nào tải lại?**
Kho URL (Lucene) lưu trạng thái. `update` tải URL mới + URL quá hạn (`--revisit-days`, mặc định 7) + trang hub (24 giờ);
xuất danh sách thêm/bớt. Quyết định tải lại dựa trên *tuổi*, chưa dựa trên tốc độ thay đổi thực (hạn chế).

**6. Vì sao bị 429 và xử lý thế nào?**
Server giới hạn tải tổng. Ngoài giãn cách theo host còn có giãn cách toàn cục; khi gặp 429/503 thì AIMD theo host
(nhân đôi chờ, tối đa 10 giây, giảm nửa sau mỗi lần thành công; tôn trọng `Retry-After`).

### B. Bóc tách nội dung
**7. BTE chạy thế nào, độ phức tạp?**
Gán mỗi token 1 (thẻ) hoặc 0 (từ), tìm đoạn [i, j] cực đại hoá `Σthẻ trước i + Σtừ trong [i,j] + Σthẻ sau j`. Duy trì
giá trị `g(i)` tốt nhất khi quét j nên O(n) thay vì O(n²); đã kiểm chứng bằng vét cạn trong test.

**8. Vì sao BTE thuần lại kém baseline?** *(câu rất có thể bị hỏi)*
BTE giả định nội dung là một đoạn liền mạch có mật độ từ cao. Trang HUST có menu dài nhiều chữ, nội dung ngắn (thông báo
tuyển dụng, danh sách), nhiều khối nội dung rời nhau → chọn nhầm. Vì vậy dùng đặc trưng vị trí/mật độ liên kết/độ dài
và để mô hình học trọng số; thêm lọc khuôn mẫu theo host (khối lặp ở ≥ 20% trang).

**9. Đánh giá khối nội dung bằng gì? Tập vàng lấy đâu?**
P/R/F1 mức *từ*, macro (trung bình theo trang) và micro (gộp). 99 trang gán nhãn tay, chia dev (huấn luyện/chọn tham số)
và test (chỉ báo cáo cuối) theo host để tránh rò rỉ.

**10. Metadata ngày đăng lấy từ đâu? Vì sao không dùng `Last-Modified`?**
Ưu tiên JSON-LD `datePublished` → meta → thẻ `<time>`/class ngày → văn bản đầu bài → Tika. `Last-Modified` của CMS phản ánh
thời điểm sửa/dựng lại, không phải ngày đăng.

### C. Đồ thị liên kết / PageRank
**11. Công thức PageRank, xử lý nút treo?**
`PR(v) = (1−d)/N + d·Σ_{u→v} PR(u)/L(u)`, d = 0,85, 40 vòng lặp luỹ thừa; nút không có liên kết ra (dangling)
chia đều khối lượng của chúng cho mọi nút.

**12. Vì sao có hai PageRank?**
`pagerank` tính trên mọi liên kết, bị menu/chân trang chi phối (trang chủ nhận hàng nghìn liên kết); `pagerankContent`
chỉ tính liên kết nằm trong khối nội dung chính — phản ánh sự giới thiệu có chủ đích hơn.

**13. Văn bản liên kết (anchor) có ích không?**
Về lý thuyết là mô tả của người khác về trang đích (phân tích liên kết). Trên bộ truy vấn của em *không giúp* (nDCG 0,675 → 0,63–0,65),
vì phần lớn liên kết nội bộ là menu/"xem thêm". Đã lọc neo vô nghĩa nhưng chưa đủ.

### D. Mô hình truy hồi (Ch04, Ch06, Ch07)
**14. `lnc.ltc` nghĩa là gì?**
Tài liệu: `l` log tf, `n` không dùng idf, `c` chuẩn hoá cosine. Truy vấn: `l` log tf, `t` idf, `c` cosine. Idf chỉ ở một
phía để không bị tính hai lần.

**15. BM25: ý nghĩa `k1`, `b`; vì sao hơn VSM?**
`k1` điều khiển bão hoà tf (tăng tần suất có lợi giảm dần); `b` điều khiển chuẩn hoá độ dài. VSM-cosine phạt tài liệu dài
quá mạnh và tf không bão hoà. Em đã chỉnh `k1 ∈ {0,9; 1,6}`, `b ∈ {0,5; 0,9}` trên dev; mặc định (1,2; 0,75) đủ tốt.

**16. BM25F khác gì "tổng BM25 từng trường" của em?**
BM25F đúng nghĩa gộp tf có trọng số *trước* khi áp dụng bão hoà (`tf' = Σ w_f·tf_f/(chuẩn hoá độ dài trường)`).
Em dùng dạng đơn giản hơn: cộng điểm BM25 từng trường có trọng số — nêu rõ đây là xấp xỉ.

**17. Phản hồi giả định (Rocchio/RM3) vì sao không cải thiện?**
Cần top-k ban đầu có độ chính xác cao; trên truy vấn từ khoá ngắn đã khá chính xác, mở rộng thêm làm *trôi chủ đề*
(query drift). Khoảng trôi thể hiện ở MRR giảm khi dùng PRF cho nhánh từ khoá trong thử nghiệm M5.

**18. RRF là gì, vì sao không cộng điểm trực tiếp?**
`score = Σ_i w_i/(k + rank_i)`. BM25 và cosine có thang điểm khác nhau, chuẩn hoá điểm dễ lệch; RRF chỉ dùng thứ hạng.
Tham số `k = 60` theo bài báo gốc.

**19. Dense retrieval không có trong slide — vì sao thêm?**
Để xử lý truy vấn *diễn đạt lại* (paraphrase) mà từ khoá không khớp. Quan sát: dense một mình (0,631) kém BM25 (0,675) nhưng
hybrid tăng R@100 từ 0,69 lên 0,88 — hai tín hiệu bổ sung cho nhau.

### E. Đánh giá (Ch05)
**20. Vì sao dùng nDCG@10 làm chỉ số chính? Công thức?**
Nhãn có 3 mức (0/1/2) nên cần độ đo có độ lợi phân bậc. `DCG = r1 + Σ_{i≥2} ri/log2(i)` (đúng dạng trong slide),
IDCG tính từ *toàn bộ* qrels của truy vấn chứ không từ danh sách trả về, vì chuẩn hoá theo danh sách trả về gây sai lệch
khi so sánh các mô hình.

**21. Pooling là gì, nhược điểm?**
Chỉ gán nhãn hợp top-15 của các hệ thống tham gia; tài liệu ngoài pool coi là không phù hợp. Hệ thống mới (cross-encoder,
M5) bị thiệt — thể hiện ở `Judged@10 < 1`. Em đã kiểm tra độ ổn định bằng cách bỏ lần lượt từng hệ thống khỏi pool (tau Kendall).

**22. Kiểm định thống kê nào? Vì sao không dùng t-test?**
Kiểm định hoán vị ghép cặp theo truy vấn (20.000 vòng) + hiệu chỉnh Holm cho nhiều so sánh + bootstrap CI 95%.
Phân phối nDCG theo truy vấn không chuẩn và mẫu nhỏ, hoán vị không giả định phân phối.

**23. Vì sao chênh lệch dev và test của cross-encoder lớn?**
Dev chỉ có 20 truy vấn, 4 truy vấn paraphrase (cross-encoder rất mạnh ở đó); test có 27 truy vấn từ khoá nơi hybrid đã mạnh.
Điều này chính là lý do cần tập test độc lập và không tuyên bố cải thiện chỉ dựa trên dev.

**24. Độ tin cậy của nhãn?** *(trả lời thật)*
Nhãn và truy vấn do mô hình ngôn ngữ soạn, không có người duyệt. Đã chấm lại mù 100 cặp: đồng thuận 77%, kappa 0,65
(nhị phân 0,68, có trọng số 0,75). Đây là *nhất quán nội bộ* chứ không phải đồng thuận giữa người với người.

### F. MongoDB / thiết kế
**25. Vì sao dùng MongoDB, `$jsonSchema` để làm gì?**
Tài liệu có cấu trúc lồng nhau (`title{value,source,conf}`, `anchors[]`) và không đồng nhất giữa trang/ảnh/tài liệu —
hợp mô hình tài liệu. `$jsonSchema` đảm bảo trường bắt buộc/kiểu dữ liệu (đầu vào sạch), `validationLevel: moderate`.

**26. Vì sao tách `pages` và `links`?**
Số cạnh (1,6 triệu) lớn và mỗi trang có nhiều cạnh; nhúng vào `pages` làm tài liệu phình quá giới hạn 16 MB và khó truy vấn
ngược (ai trỏ tới ảnh này?). `links` có chỉ mục `(dst, inContent)` để tra nguồn giới thiệu. Trường `anchors`/`inlinks` được
phi chuẩn hoá (denormalize) vào `pages` để đọc nhanh.

**27. `_id` là gì?**
SHA-1 của URL chuẩn → ổn định, độc lập thứ tự nạp, nạp lại không tạo bản sao.

### G. Câu hỏi "vượt chương trình"
**28. Nếu có thêm thời gian, làm gì để cải thiện nhiều nhất?**
(1) Tách từ ghép tiếng Việt (nay chỉ dùng âm tiết — ảnh hưởng lớn nhất ở truy vấn paraphrase);
(2) mở rộng bộ truy vấn và nhãn do người gán; (3) hoàn thiện độ phủ (11.159 URL); (4) học xếp hạng (LTR) trên đặc trưng
BM25 từng trường + dense + PageRank khi có đủ nhãn.

**29. Làm sao phục vụ cross-encoder trong thực tế (12 giây/truy vấn)?**
GPU hoặc mô hình nhỏ hơn; chỉ xếp lại top 10–20; lưu đệm cho truy vấn phổ biến; chạy ngoại tuyến cho các trang quan trọng.

---

## 9. Danh sách lệnh chạy nhanh (dán nhanh khi cần)

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot"
.\scripts\crawl.bat stats | Select-Object -First 15
.\scripts\search.bat query ký túc xá bách khoa
$u = "https://bktextile.hust.edu.vn/tin-tuc1/-/asset_publisher/L3swsazlHgWT/content/youngone-nam-inh-tuyen-dung"
.\scripts\extract.bat show --url $u | Where-Object { $_ -match '^\s*\d+ #|^(title|publishedAt|author|sourceRef) =' }
.\scripts\extract.bat mongo-demo
.\scripts\eval.bat eval --runs "eval/runs/test/bm25.run;eval/runs/test/hybrid_w-1.run;eval/runs/test/vsm.run;eval/runs/test/dense.run;eval/runs/test/current.run" --qrels eval/qrels/test.tsv --split test --baseline bm25 --out eval/work/demo_report.md
Start-Process docs\KET_QUA_TRUC_QUAN.html
```
