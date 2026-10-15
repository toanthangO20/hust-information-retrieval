# Kịch bản báo cáo với thầy — HUST Information Retrieval

Cấu trúc mỗi phần: **Slide → Đã làm → Show code → Chạy thử → Kết quả**.
Tổng thời lượng dự kiến ≈ 25–30 phút + hỏi đáp. Mọi lệnh chạy trong terminal PowerShell của VS Code, thư mục gốc dự án.

- **Slide**: `docs/BAO_CAO_TRUC_QUAN.html` (22 slide). Phím ←/→ chuyển slide, `N` ghi chú người nói, `M` mục lục, `D` nền tối,
  `Ctrl+P` xuất PDF. Mở thẳng một slide: `BAO_CAO_TRUC_QUAN.html#14`. Mọi số liệu trên slide sinh từ `docs/data/results.js`.
- **Show code**: mỗi vị trí cần chỉ có nhãn `[DEMO:tên]` ngay trong mã. Trong VS Code: `Ctrl+Shift+F` → gõ `DEMO:tên`
  (không phụ thuộc số dòng, sửa mã không làm lệch kịch bản). Liệt kê tất cả: `Select-String -Path src\main\java -Pattern '\[DEMO:' -Recurse`
  hoặc `git grep -n "\[DEMO:"`.

---

## 0. Chuẩn bị trước buổi báo cáo (làm 5 phút trước)

```powershell
cd "E:\Projects\Information Retrieval\hust-information-retrieval"
# Terminal 2: MongoDB (chỉ cần cho phần 5)       .\scripts\mongo.bat
# Terminal 3: web + trang so sánh mô hình         .\scripts\search.bat serve
.\scripts\demo-check.bat        # Java, jar, chỉ mục, mô hình, số liệu slide, MongoDB, web — in [OK] / [!!] / [XX]
.\scripts\report.bat            # (nếu demo-check báo số liệu slide cũ) sinh lại docs\data\results.js rồi mở slide
```

Mở sẵn trong trình duyệt:

```powershell
Start-Process docs\BAO_CAO_TRUC_QUAN.html                    # bộ slide (toàn màn hình: F11)
Start-Process "http://localhost:8080/compare.html?qid=q28"   # so sánh trực tiếp các mô hình
Start-Process docs\SCHEMA_TOMTAT.html                        # sơ đồ lược đồ MongoDB
```

Nếu demo trực tiếp lỗi: các slide đã có đủ biểu đồ và số liệu; trang so sánh có thể thay bằng slide 20 (bảng điểm từng mô hình).

---

## 1. Mở đầu (2 phút) — slide 1, 2

**Nói:**
- Mục tiêu: xây hệ thống thu thập và truy vấn thông tin về ĐH Bách khoa Hà Nội (`hust.edu.vn` + subdomain).
- 5 giai đoạn (slide 2): **Crawl** → **Dữ liệu + chỉ mục + giao diện** → **Bóc tách khối nội dung** → **Đồ thị liên kết + MongoDB** → **Đánh giá các mô hình truy hồi**.
- Công nghệ: Java 11, Tika (trích văn bản), Lucene (chỉ mục), jsoup (DOM), MongoDB, ONNX Runtime.
- Mọi con số trên slide sinh tự động từ đầu ra của hệ thống (`scripts\report.bat`), không chép tay.

---

## 2. Crawler (5 phút) — slide 3, 4 · Ch10 (crawler), Ch09 (trùng lặp)

### 2.1 Đã làm
Frontier kiểu Mercator, robots.txt + giãn cách theo host, chuẩn hoá/lọc URL, đọc sitemap, lọc "có nội dung" (soft-404),
loại trùng (SHA-1 chính xác + SimHash gần đúng), điều chỉnh tốc độ khi bị 429, cập nhật tăng dần.

### 2.2 Show code
| Ý | Tệp · nhãn | Nói gì |
|---|---|---|
| Hàng đợi hai tầng | `crawler/Frontier.java` · `DEMO:frontier-priority` | 3 mức ưu tiên (phía trước) |
| Hàng đợi theo host + heap thời điểm | `Frontier.java` · `DEMO:frontier-take` | host chưa tới `nextAt` thì chờ → lịch sự (politeness) |
| Giãn cách sau mỗi request | `Frontier.java` · `DEMO:frontier-politeness` | `nextAt = now + delay` |
| Chuẩn hoá URL | `UrlNormalizer.java` · `DEMO:url-normalize`, `DEMO:url-junk-params` | bỏ `utm_*`, `fbclid`, sessionid; chống bẫy |
| Soft-404 và ngưỡng "có nội dung" | `Crawler.java` · `DEMO:soft-404`, `DEMO:min-content` | < 200 ký tự / < 30 từ hoặc mẫu "không tìm thấy" |
| Trùng chính xác (SHA-1) | `Crawler.java` · `DEMO:dup-sha1` | băm văn bản đã chuẩn hoá |
| Trùng gần đúng (SimHash) | `SimHash.java` · `DEMO:simhash`, `DEMO:hamming`; `Crawler.java` · `DEMO:dup-simhash` | shingle 3 từ, 64 bit, ngưỡng Hamming ≤ 3 |
| Giảm tốc khi 429 | `Crawler.java` · `DEMO:backoff-429` | AIMD theo host: nhân đôi khi lỗi, giảm nửa khi thành công |

### 2.3 Chạy thử (≈ 2 giây)
```powershell
.\scripts\crawl.bat stats | Select-Object -First 15      # thống kê theo host: pending / valid / invalid
Get-Content output\update_log.tsv                         # nhật ký các lượt chạy (slide 3 vẽ từ tệp này)
```
(Nếu muốn minh hoạ lọc: `.\scripts\crawl.bat inspect <url>` — cần mạng.)

### 2.4 Kết quả — chỉ slide 3 rồi slide 4
- Slide 3: URL hợp lệ tăng qua 6 lượt `update` (7.196 → 22.178) → cơ chế cập nhật tăng dần hoạt động; lượt sau chỉ thêm phần mới.
- Slide 4: 12.589 URL bị loại, mỗi URL có lý do; nhiều nhất là "không có nội dung" (36,8%) và HTTP 404.
  11.159 URL đã phát hiện nhưng chưa tải (`hust.edu.vn` giới hạn tốc độ).
- 7 lượt chạy thật, mỗi lượt sửa lỗi thực tế (ASP.NET `<form>`, server treo, 429, URL nhân đôi tham số).

---

## 3. Bộ dữ liệu ver1, chỉ mục và giao diện (3 phút) — slide 5 · Ch04, Ch07

### 3.1 Đã làm
`dataset/ver1` (22.178 tài liệu), làm sạch (sửa chữ vỡ UTF-8, loại 43 tài liệu font TCVN3/VNI), chỉ mục Lucene BM25
bỏ dấu tiếng Việt + trường giữ dấu, giao diện web.

### 3.2 Show code
| Ý | Tệp · nhãn | Nói gì |
|---|---|---|
| Bộ phân tích tiếng Việt | `search/ViAnalyzer.java` · `DEMO:vi-analyzer` | tách âm tiết (UAX#29) + bỏ dấu (ASCIIFolding) → "tuyen sinh" tìm được "tuyển sinh" |
| Truy vấn AND, nới sang OR | `search/SearchEngine.java` · `DEMO:and-to-or` | không có kết quả đủ từ thì nới `relaxed = true` |
| Thưởng cụm từ / đúng dấu | `SearchEngine.java` · `DEMO:phrase-bonus` | phrase trên tiêu đề ×4, nội dung ×1,5; trường giữ dấu |
| Trường và trọng số | `SearchEngine.java` · `DEMO:field-boosts` | title ×3, content ×1, url ×0,6 |

### 3.3 Chạy thử
Mở http://localhost:8080 (tô sáng, lọc theo trang con/loại, xuất kết quả). Hoặc dòng lệnh: `.\scripts\search.bat query ký túc xá bách khoa`.

### 3.4 Kết quả — slide 5
- Độ trễ đo bằng `eval.bat bench` (1 lượt khởi động + trung vị 5 lượt): BM25 ≈ 0,9 ms, hybrid ≈ 8 ms/truy vấn.
  Công cụ web ≈ 115 ms vì còn tạo đoạn trích tô sáng và facet cho 100 kết quả.
- Giao diện: HTML/CSS/JS thuần, chạy được trên điện thoại.

---

## 4. Bóc tách nội dung: tìm khối nội dung chính (6 phút) — slide 6, 7, 8, 9 · Ch10 (BTE)

### 4.1 Đã làm
Phân trang thành token (thẻ/từ) và khối; cài thuật toán **BTE** (Body Text Extraction, O(n)); các baseline (quy tắc
đặc trưng nông, CSS theo host); **bộ phân loại học có giám sát** (hồi quy logistic, 37 đặc trưng); lọc khuôn mẫu theo host;
tập vàng 99 trang chia dev/test.

### 4.2 Show code
| Ý | Tệp · nhãn | Nói gì |
|---|---|---|
| BTE: chọn đoạn cực đại hoá hàm mục tiêu | `extract/BlockFinder.java` · `DEMO:bte` | chọn [i, j] cực đại: số thẻ trước i + số từ trong [i,j] + số thẻ sau j |
| Vòng lặp O(n) duy nhất | `BlockFinder.java` · `DEMO:bte-linear` | duy trì max `g(i)` chạy dần, không cần O(n²) |
| Phân loại học | `BlockFinder.java` · `DEMO:learned`, `DEMO:learned-threshold` | mỗi khối → xác suất là nội dung |
| Hồi quy logistic tự cài | `extract/LogReg.java` · `DEMO:logreg-predict`, `DEMO:logreg-train` | chuẩn hoá z-score, L2, trọng số mẫu |
| Lọc khuôn mẫu theo host | `BlockFinder.java` · `DEMO:template`; `Extractor.java` · `DEMO:template-df` | khối xuất hiện ở ≥ 20% trang cùng host bị loại |

### 4.3 Chạy thử (≈ 2 giây)
```powershell
$u = "https://bktextile.hust.edu.vn/tin-tuc1/-/asset_publisher/L3swsazlHgWT/content/youngone-nam-inh-tuyen-dung"
.\scripts\extract.bat show --url $u | Where-Object { $_ -match '^\s*\d+ #|^(title|publishedAt|author|sourceRef) =' }
```
Dấu `#` = khối được chọn là nội dung chính; `.` = khối bị bỏ (menu, chân trang).

### 4.4 Kết quả (tập test 49 trang, F1 mức từ macro)
- **Slide 6** (biểu đồ quả tạ): mỗi thuật toán gốc → thêm lọc khuôn mẫu. Baseline jsoup 0,813; BTE thuần 0,662;
  phân loại học 0,845; **học + lọc khuôn mẫu 0,870**. Nút "Tập dev" để cho thấy xu hướng giống nhau trên dev.
- **Slide 7**: từng trang — học tốt hơn baseline trên 36 trang, kém hơn trên 11 trang.
- **Slide 8** (trực quan nhất): dải khối của trang thật — BTE chọn nhầm menu dài (đỏ), bỏ sót bài viết (vàng); mô hình học chọn đúng (xanh).
  Trang cuối là **ví dụ thất bại** của mô hình học — nói rõ để không bị coi là chọn ví dụ đẹp.

**Điểm cần nói thẳng:** BTE thuần *kém* baseline jsoup; thầy dạy BTE nhưng trên trang thật của HUST (menu nhiều từ,
nội dung ngắn như thông báo tuyển dụng) giả định của BTE bị vỡ. Vì vậy phải kết hợp đặc trưng + học máy.

### 4.5 Metadata — slide 9
Tiêu đề 0,75 · ngày đăng 0,96 · tác giả 0,96 (độ chính xác trên tập vàng); phủ toàn bộ dữ liệu: tiêu đề 94%, ngày 54%, tác giả 14%.
Mỗi trường lưu `source` + `conf` (JSON-LD → meta → DOM → văn bản).

---

## 5. Đồ thị liên kết + MongoDB (4 phút) — slide 10, 11 · phân tích liên kết

### 5.1 Đã làm
Đồ thị nguồn → đích : văn bản mô tả (anchor), cờ `inContent`, ảnh và tài liệu là nút; quy đích về URL chuẩn;
PageRank hai biến thể; lược đồ MongoDB có `$jsonSchema` + chỉ mục.

### 5.2 Show code
| Ý | Tệp · nhãn | Nói gì |
|---|---|---|
| PageRank | `extract/GraphBuilder.java` · `DEMO:pagerank` | d = 0,85; 40 vòng; nút treo (dangling) chia đều |
| Tránh neo vô nghĩa | `GraphBuilder.java` · `DEMO:junk-anchor` | bỏ "xem thêm", "chi tiết" |
| Phân loại nút (ảnh/tài liệu/ngoài) | `GraphBuilder.java` · `DEMO:node-kind` | theo đuôi tệp và loại cạnh |
| Lược đồ kiểm tra | `extract/MongoLoader.java` · `DEMO:mongo-schema` | `$jsonSchema`, `validationLevel: moderate` |
| Chỉ mục | `MongoLoader.java` · `DEMO:mongo-indexes` | `url` duy nhất, `(host,kind)`, `pagerankContent`↓, text `title.value` |

Mở thêm `docs/SCHEMA.md` (mô tả từng trường) và `docs/SCHEMA_TOMTAT.html` (sơ đồ).

### 5.3 Chạy thử (≈ 7 giây; cần MongoDB đang chạy)
```powershell
.\scripts\extract.bat mongo-demo
```
In ra: số trang/cạnh theo loại, 5 trang PageRank cao nhất, và một ảnh kèm các trang giới thiệu nó (nguồn + văn bản liên kết).

### 5.4 Kết quả
- **Slide 10**: 80.060 nút (34.876 html, 23.284 ảnh, 16.855 ngoài miền, 5.045 tài liệu), 1.601.816 cạnh; đồ thị 30 trang con
  liên kết nhiều nhất (kéo thả được); chỉ ~3% liên kết `<a>` nằm trong nội dung chính.
- **Slide 11**: văn bản neo và PageRank **không** cải thiện xếp hạng; neo chỉ từ nội dung (w ≥ 0,5) kém hơn có ý nghĩa.
  PageRank cao nhất là trang được menu/chân trang trỏ tới → lý do tách `pagerankContent`.

---

## 6. Đánh giá truy hồi (8 phút) — slide 12 → 20 · Ch04, Ch05, Ch06, Ch07

### 6.1 Đã làm
Bộ kiểm thử kiểu TREC: 60 truy vấn (20 dev / 40 test), pool, qrels 3 mức (0/1/2), 4 mô hình theo slide + mô hình mở rộng:

| Mã | Mô hình | Cấu hình chốt trên dev | Slide môn học |
|---|---|---|---|
| W | Công cụ web hiện tại (tham chiếu) | — | Ch07 |
| M1 | VSM tf-idf `lnc.ltc` | — | Ch04 |
| M2 | BM25 đa trường (title×3, content×1, url×0,6) | k1 = 1,2, b = 0,75 (mặc định) | Ch07 |
| M3 | BM25 + phản hồi giả định (PRF/RM3) | 5 tài liệu, 20 từ, λ = 0,5 | Ch06 |
| D | Dense (multilingual-e5-small) | — | ngoài slide |
| M4 | Hybrid: BM25 + dense, hợp nhất RRF | **w = 2** (lưới {0,5; 1; 2}) | ngoài slide |
| M5 | Rocchio trong không gian vec-tơ dày (chỉ dev) | m = 5, α = 0,5 | Ch06 (mở rộng) |
| M6 | Cross-encoder bge-reranker-v2-m3 xếp lại top-30 | chỉ điểm CE | ngoài slide |

### 6.2 Show code
| Ý | Tệp · nhãn | Nói gì |
|---|---|---|
| VSM `lnc.ltc` | `eval/VsmRetriever.java` · `DEMO:vsm-lnc-ltc` | log tf, chuẩn hoá cosine; truy vấn có idf |
| BM25 từng trường | `eval/Bm25Retriever.java` · `DEMO:bm25` | tổng BM25 các trường có trọng số |
| Hợp nhất RRF | `eval/HybridRetriever.java` · `DEMO:rrf` | `w / (rrfK + hạng)` — không cần chuẩn hoá điểm |
| nDCG theo slide | `eval/Metrics.java` · `DEMO:ndcg` | `r1 + Σ ri/log2 i`, IDCG lấy từ toàn bộ qrels |
| Kiểm định ghép cặp | `eval/Significance.java` · `DEMO:permutation-test`, `DEMO:holm` | hoán vị 20.000 vòng, hiệu chỉnh Holm |
| Cross-encoder | `eval/CrossEncoderRetriever.java` · `DEMO:ce-pair`, `DEMO:ce-rerank` | mô hình đọc đồng thời truy vấn và tài liệu |
| Số liệu slide | `eval/Dashboard.java` · `DEMO:dashboard-stats` | tính lại độ đo + kiểm định từ các tệp run |
| So sánh trực tiếp | `eval/LiveCompare.java` · `DEMO:live-compare` | nhiều mô hình, cùng truy vấn, tô theo qrels |

### 6.3 Chạy thử
**Trang so sánh trực tiếp** (slide 20 có sẵn 3 liên kết, chọn tự động nơi các mô hình khác nhau nhiều nhất):
http://localhost:8080/compare.html?qid=q28 — chọn truy vấn có nhãn, mỗi cột một mô hình, viền trái = nhãn phù hợp (xanh 2, vàng 1, đỏ 0),
đầu cột là nDCG@10 của chính truy vấn đó; rê chuột lên một kết quả để thấy nó ở các cột khác.

Chấm lại từ các tệp run đã lưu (≈ 1 giây, ra đúng bảng ở slide 15):
```powershell
$T = "eval/runs/test"
.\scripts\eval.bat eval --runs "$T/bm25.run;$T/current.run;$T/vsm.run;$T/prf_docs-5_terms-20_lambda-0.5.run;$T/dense.run;$T/hybrid_w-2.run;$T/ce_depth-30_len-320.run" --qrels eval/qrels/test.tsv --split test --baseline bm25 --out eval/work/demo_report.md
Get-Content eval\work\demo_report.md -Encoding UTF8 | Select-Object -Skip 6 -First 24
```

### 6.4 Kết quả (tập test, 40 truy vấn; `eval/report/test_final.md`)
| Hệ thống | nDCG@10 | MAP | MRR@10 | R@100 |
|---|---:|---:|---:|---:|
| W công cụ web hiện tại | 0,573 | 0,346 | 0,787 | 0,448 |
| M1 VSM | 0,492 * | 0,361 | 0,783 | 0,647 |
| M2 BM25 | 0,675 | 0,488 | 0,883 | 0,688 |
| M3 PRF | 0,674 | 0,489 | 0,894 | 0,668 |
| D dense | 0,631 | 0,448 | 0,864 | 0,685 |
| **M4 hybrid (w=2, chốt trên dev)** | **0,685** | 0,536 | 0,960 | 0,809 |
| M6 cross-encoder | 0,702 | 0,600 | 0,921 | 0,876 |
| *phân tích sau: hybrid w=1 (tốt nhất trên test — không dùng để kết luận)* | *0,701* | *0,585* | *0,953* | *0,876* |

`*` = khác biệt có ý nghĩa so với BM25 (hoán vị ghép cặp, Holm p = 0,0045).

Đi qua các slide:
- **Slide 12–13**: dev chọn tham số, test chạy một lần; trong mỗi họ mô hình chênh lệch giữa các cấu hình nhỏ.
- **Slide 14** (forest plot): chỉ khoảng tin cậy của VSM nằm hẳn một phía 0. Hybrid +0,010 [−0,065; +0,086].
- **Slide 15**: hybrid kéo R@100 0,688 → 0,809; cross-encoder cao nhất nhưng 16% top-10 chưa có nhãn (Judged@10 0,84).
- **Slide 16**: từng truy vấn — M4 thắng 19, thua 18; đổi mô hình bằng ô chọn.
- **Slide 17**: theo loại truy vấn — công cụ web 0,04 ở truy vấn câu hỏi trong khi BM25 0,71 → lỗi ở ngữ nghĩa AND/cụm từ.
  Ma trận cặp: chỉ các ô của M1 có viền (có ý nghĩa).
- **Slide 18**: trọng số tốt nhất trên dev (w=4) kém nhất trên test; dense cao hơn mọi hybrid trên dev nhưng thấp hơn trên test → không tinh chỉnh quá mức.
- **Slide 19**: cross-encoder chậm ~1.400 lần hybrid để đổi lấy +0,016 nDCG@10 (không có ý nghĩa).

**Các kết luận phải nói (đúng thực tế, không thổi phồng) — slide 21:**
1. Chỉ VSM kém hơn BM25 có ý nghĩa thống kê; BM25 ≈ PRF (PRF không giúp).
2. Hybrid tăng độ phủ rõ (R@100 0,69 → 0,81), nhưng nDCG@10 chưa khác biệt có ý nghĩa với 40 truy vấn.
3. Công cụ hiện tại sụp đổ trên truy vấn dạng câu hỏi (nDCG ≈ 0,04) do ngữ nghĩa AND + cụm từ → hướng cải tiến rõ ràng.
4. Văn bản liên kết (anchor) và PageRank **không** cải thiện — ghi nhận kết quả âm.
5. Cross-encoder: dev tăng mạnh (+0,217 so với BM25, Holm p = 0,011) nhưng test chỉ +0,016 so với M4 và chậm ≈ 12 giây/truy vấn → **không kết luận là cải thiện**.

---

## 7. Tổng kết và hạn chế (2 phút) — slide 21, 22

**Đã đạt:** crawler + cập nhật tăng dần; bộ dữ liệu; chỉ mục + giao diện + trang so sánh mô hình; bóc tách khối nội dung/metadata;
đồ thị 1,6 triệu cạnh; MongoDB có lược đồ; khung đánh giá có kiểm định thống kê; báo cáo trực quan tự sinh từ dữ liệu (slide 22).

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
không tự loại; chỉ loại khi bật `--drop-near-dup` (`DEMO:drop-near-dup`).
*Hạn chế có thể bị hỏi:* so sánh SimHash hiện là quét tuyến tính toàn bộ URL hợp lệ (`DEMO:dup-simhash`, O(N) mỗi trang) —
với quy mô lớn phải dùng bảng hoán vị bit (Manku et al.) để tra cứu Hamming ≤ 3 nhanh.

**4. Chuẩn hoá URL để làm gì? Có những bẫy nào?**
Đưa các dạng khác nhau của cùng một trang về một dạng (hạ chữ thường host, bỏ fragment, bỏ cổng mặc định, sắp tham số,
bỏ `utm_*`/`sessionid`, mã hoá `%xx` thống nhất). Bẫy: lịch vô hạn, tham số phân trang vô hạn, URL nhân đôi tham số khi chuyển hướng.

**5. Cập nhật tăng dần hoạt động ra sao? Khi nào tải lại?**
Kho URL (Lucene) lưu trạng thái. `update` tải URL mới + URL quá hạn (`--revisit-days`, mặc định 7) + trang hub (24 giờ);
xuất danh sách thêm/bớt (slide 3). Quyết định tải lại dựa trên *tuổi*, chưa dựa trên tốc độ thay đổi thực (hạn chế).

**6. Vì sao bị 429 và xử lý thế nào?**
Server giới hạn tải tổng. Ngoài giãn cách theo host còn có giãn cách toàn cục; khi gặp 429/503 thì AIMD theo host
(nhân đôi chờ, tối đa 10 giây, giảm nửa sau mỗi lần thành công; tôn trọng `Retry-After`).

### B. Bóc tách nội dung
**7. BTE chạy thế nào, độ phức tạp?**
Gán mỗi token 1 (thẻ) hoặc 0 (từ), tìm đoạn [i, j] cực đại hoá `Σthẻ trước i + Σtừ trong [i,j] + Σthẻ sau j`. Duy trì
giá trị `g(i)` tốt nhất khi quét j nên O(n) thay vì O(n²); đã kiểm chứng bằng vét cạn trong test.

**8. Vì sao BTE thuần lại kém baseline?** *(câu rất có thể bị hỏi — chỉ slide 8)*
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
`pagerank` tính trên mọi liên kết, bị menu/chân trang chi phối (trang chủ nhận hàng nghìn liên kết — bảng ở slide 11); `pagerankContent`
chỉ tính liên kết nằm trong khối nội dung chính — phản ánh sự giới thiệu có chủ đích hơn.

**13. Văn bản liên kết (anchor) có ích không?**
Về lý thuyết là mô tả của người khác về trang đích (phân tích liên kết). Trên bộ truy vấn của em *không giúp* (nDCG 0,675 → 0,63–0,65; slide 11),
vì phần lớn liên kết nội bộ là menu/"xem thêm". Đã lọc neo vô nghĩa nhưng chưa đủ.

### D. Mô hình truy hồi (Ch04, Ch06, Ch07)
**14. `lnc.ltc` nghĩa là gì?**
Tài liệu: `l` log tf, `n` không dùng idf, `c` chuẩn hoá cosine. Truy vấn: `l` log tf, `t` idf, `c` cosine. Idf chỉ ở một
phía để không bị tính hai lần.

**15. BM25: ý nghĩa `k1`, `b`; vì sao hơn VSM?**
`k1` điều khiển bão hoà tf (tăng tần suất có lợi giảm dần); `b` điều khiển chuẩn hoá độ dài. VSM-cosine phạt tài liệu dài
quá mạnh và tf không bão hoà. Em đã chỉnh `k1 ∈ {0,9; 1,6}`, `b ∈ {0,5; 0,9}` trên dev (slide 13); mặc định (1,2; 0,75) đủ tốt.

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
Để xử lý truy vấn *diễn đạt lại* (paraphrase) mà từ khoá không khớp (ví dụ q31 trên trang so sánh: BM25 0, dense 0,60).
Dense một mình (0,631) kém BM25 (0,675) nhưng hybrid tăng R@100 từ 0,69 lên 0,81 — hai tín hiệu bổ sung cho nhau.

**20. Vì sao M4 dùng w = 2, trong khi w = 1 cho nDCG@10 cao hơn trên test (0,701)?** *(rất có thể bị hỏi — slide 18)*
Vì w được chọn trên dev trước khi chạy test (lưới {0,5; 1; 2} → w = 2 tốt nhất). Chọn lại theo test là dùng test để tinh chỉnh,
làm số liệu lạc quan giả tạo. w = 1 chỉ được báo cáo như *phân tích sau*. Slide 18 cho thấy thứ tự các w đảo ngược giữa dev và test
→ chênh lệch nằm trong nhiễu của 20–40 truy vấn; kết luận đúng là "hybrid ổn định trong khoảng 0,67–0,70", không phải "w = 1 tốt nhất".

### E. Đánh giá (Ch05)
**21. Vì sao dùng nDCG@10 làm chỉ số chính? Công thức?**
Nhãn có 3 mức (0/1/2) nên cần độ đo có độ lợi phân bậc. `DCG = r1 + Σ_{i≥2} ri/log2(i)` (đúng dạng trong slide),
IDCG tính từ *toàn bộ* qrels của truy vấn chứ không từ danh sách trả về, vì chuẩn hoá theo danh sách trả về gây sai lệch
khi so sánh các mô hình.

**22. Pooling là gì, nhược điểm?**
Chỉ gán nhãn hợp top-15 của các hệ thống tham gia; tài liệu ngoài pool coi là không phù hợp. Hệ thống mới (cross-encoder,
M5) bị thiệt — thể hiện ở `Judged@10 < 1` (ô vàng ở slide 15). Em đã kiểm tra độ ổn định bằng cách bỏ lần lượt từng hệ thống khỏi pool (tau Kendall).

**23. Kiểm định thống kê nào? Vì sao không dùng t-test?**
Kiểm định hoán vị ghép cặp theo truy vấn (20.000 vòng) + hiệu chỉnh Holm cho nhiều so sánh + bootstrap CI 95% (slide 14, 17).
Phân phối nDCG theo truy vấn không chuẩn và mẫu nhỏ, hoán vị không giả định phân phối.

**24. Vì sao chênh lệch dev và test của cross-encoder lớn?**
Dev chỉ có 20 truy vấn, 4 truy vấn paraphrase (cross-encoder rất mạnh ở đó); test có 27 truy vấn từ khoá nơi hybrid đã mạnh (slide 18 phải).
Điều này chính là lý do cần tập test độc lập và không tuyên bố cải thiện chỉ dựa trên dev.

**25. Độ tin cậy của nhãn?** *(trả lời thật)*
Nhãn và truy vấn do mô hình ngôn ngữ soạn, không có người duyệt. Đã chấm lại mù 100 cặp: đồng thuận 77%, kappa 0,65
(nhị phân 0,68, có trọng số 0,75). Đây là *nhất quán nội bộ* chứ không phải đồng thuận giữa người với người.

### F. MongoDB / thiết kế
**26. Vì sao dùng MongoDB, `$jsonSchema` để làm gì?**
Tài liệu có cấu trúc lồng nhau (`title{value,source,conf}`, `anchors[]`) và không đồng nhất giữa trang/ảnh/tài liệu —
hợp mô hình tài liệu. `$jsonSchema` đảm bảo trường bắt buộc/kiểu dữ liệu (đầu vào sạch), `validationLevel: moderate`.

**27. Vì sao tách `pages` và `links`?**
Số cạnh (1,6 triệu) lớn và mỗi trang có nhiều cạnh; nhúng vào `pages` làm tài liệu phình quá giới hạn 16 MB và khó truy vấn
ngược (ai trỏ tới ảnh này?). `links` có chỉ mục `(dst, inContent)` để tra nguồn giới thiệu. Trường `anchors`/`inlinks` được
phi chuẩn hoá (denormalize) vào `pages` để đọc nhanh.

**28. `_id` là gì?**
SHA-1 của URL chuẩn → ổn định, độc lập thứ tự nạp, nạp lại không tạo bản sao.

### G. Câu hỏi "vượt chương trình"
**29. Nếu có thêm thời gian, làm gì để cải thiện nhiều nhất?**
(1) Sửa ngữ nghĩa AND của công cụ web (lợi ngay cho truy vấn câu hỏi — slide 17); (2) tách từ ghép tiếng Việt;
(3) mở rộng bộ truy vấn và nhãn do người gán; (4) hoàn thiện độ phủ (11.159 URL); (5) học xếp hạng (LTR) trên đặc trưng
BM25 từng trường + dense + PageRank khi có đủ nhãn.

**30. Làm sao phục vụ cross-encoder trong thực tế (12 giây/truy vấn)?**
GPU hoặc mô hình nhỏ hơn; chỉ xếp lại top 10–20; lưu đệm cho truy vấn phổ biến; chạy ngoại tuyến cho các trang quan trọng.

---

## 9. Danh sách lệnh chạy nhanh (dán nhanh khi cần)

```powershell
.\scripts\demo-check.bat
Start-Process docs\BAO_CAO_TRUC_QUAN.html
Start-Process "http://localhost:8080/compare.html?qid=q28"
.\scripts\crawl.bat stats | Select-Object -First 15
.\scripts\search.bat query ký túc xá bách khoa
$u = "https://bktextile.hust.edu.vn/tin-tuc1/-/asset_publisher/L3swsazlHgWT/content/youngone-nam-inh-tuyen-dung"
.\scripts\extract.bat show --url $u | Where-Object { $_ -match '^\s*\d+ #|^(title|publishedAt|author|sourceRef) =' }
.\scripts\extract.bat mongo-demo
Get-Content eval\report\test_final.md -Encoding UTF8 | Select-Object -Skip 6 -First 24
git grep -n "\[DEMO:"
```
