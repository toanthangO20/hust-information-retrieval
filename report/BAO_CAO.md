# Báo cáo công việc: Thu thập URL của hust.edu.vn và các subdomain

**Môn học:** Truy hồi thông tin (Information Retrieval)
**Công việc:** Bước 1 của dự án — crawl miền `hust.edu.vn`, lọc URL không có nội dung, xuất danh sách URL đạt yêu cầu, có cơ chế cập nhật.
**Ngôn ngữ / công cụ:** Java 11, Apache Tika 2.9.2, Apache Lucene 9.10.0, jsoup 1.17.2, Maven 3.9.9
**Thư mục dự án:** `E:\Projects\Information Retrieval\hust-information-retrieval`

---

## 1. Tóm tắt kết quả

| Chỉ tiêu | Kết quả |
|---|---|
| Danh sách URL đạt yêu cầu | `output/urls_valid.txt` — **22.178 URL** trên **47 host** (miền chính + subdomain) |
| URL bị loại (kèm lý do) | `output/urls_invalid.tsv` — 12.589 URL |
| URL đã phát hiện nhưng chưa tải (hết ngân sách trang) | 11.159 URL — tự động được tải ở lần `update` kế tiếp |
| Tài liệu PDF/Office được trích văn bản bằng Tika | 2.025 URL (trong đó 1.725 PDF) |
| Cơ chế cập nhật | Lệnh `update` + nhật ký `output/update_log.tsv` + tệp chênh lệch `output/updates/` — đã chạy kiểm chứng (mục 6) |
| Kiểm thử | 7 test đều đạt: 6 unit test (chuẩn hóa URL, robots.txt, SimHash, hàng đợi) + 1 test tích hợp cơ chế cập nhật trên website cục bộ (mục 6) |

Chạy lại bất kỳ lúc nào: `scripts\crawl.bat update` (xem `README.md`).

> **Lưu ý về độ phủ.** Đây là kết quả của nhiều lần chạy có *ngân sách trang* (không phải crawl tới khi hết toàn bộ web).
> Một số host (đặc biệt `hust.edu.vn`) bị server giới hạn tốc độ nên mới được tải một phần; phần còn lại nằm ở trạng thái
> `pending` trong kho và sẽ được xử lý dần ở các lần `update` tiếp theo. Con số "URL hợp lệ" vì vậy là cận dưới, không phải tổng số.

---

## 2. Kiến thức môn học đã áp dụng

Tài liệu tham khảo: bộ slide Chương 1–12 (thư mục `resources_13.7m`). Hai chương trực tiếp quyết định thiết kế là
**Chương 9** (Cơ bản tìm kiếm thông tin trên Web) và **Chương 10** (Thu thập dữ liệu Web & chỉ mục ngược quy mô lớn).

| Kiến thức trong slide | Áp dụng trong dự án | Lớp |
|---|---|---|
| Ch.10 — *Giải thuật loang trên đồ thị Web* (hàng đợi URL → tải → trích URL → thêm hàng đợi) và *các hạn chế của giải thuật này* | Vòng lặp crawl cơ bản; khắc phục hạn chế bằng các mục dưới đây | `Crawler` |
| Ch.10 — *Các tính năng phải có*: tuân thủ **robots.txt**, kiểm soát tần suất truy cập, khả năng **khôi phục và tiếp tục** sau gián đoạn | Phân tích Allow/Disallow (khớp tiền tố dài nhất, `*`, `$`), `Crawl-delay`, `Sitemap`; mỗi host tối đa 1 request đồng thời + khoảng chờ; trạng thái bền vững trong Lucene nên dừng/chạy lại không mất tiến độ (đã gặp và dùng thật khi phải dừng tiến trình) | `RobotsCache`, `Frontier`, `UrlStore` |
| Ch.10 — *Các tính năng nên có*: cơ chế **ưu tiên**, vận hành liên tục (tải lại bản mới), mở rộng định dạng | Ba mức ưu tiên URL; chu kỳ tải lại; hỗ trợ HTML + PDF/Office qua Tika | `Frontier`, `Crawler`, `ContentExtractor` |
| Ch.10 — *Hàng đợi URL kiểu **Mercator***: hàng đợi phía trước (ưu tiên) và phía sau (mỗi host một hàng đợi), heap theo thời điểm *te* | Cài đặt đúng mô hình này: 3 hàng đợi ưu tiên cho mỗi host + `PriorityQueue` theo thời điểm sớm nhất được gửi request kế tiếp | `Frontier` |
| Ch.10 — *Đảm bảo tính lịch thiệp* vs. *tính cập nhật* | Khoảng chờ theo host (mặc định 500 ms, host chính 700 ms), giới hạn tốc độ toàn cục, tự điều chỉnh khi gặp HTTP 429 | `Crawler` |
| Ch.10 — *Chuẩn hóa URL* (liên kết tương đối → tuyệt đối), *Loại URL trùng lặp* (URL-seen test) | Giải liên kết tương đối, bỏ fragment, chuẩn hóa host/port/`%xx`, sắp xếp tham số truy vấn, bỏ tham số theo dõi (`utm_*`, `fbclid`…); bảng bản ghi URL là bộ lọc "đã thấy" | `UrlNormalizer`, `Crawler` |
| Ch.10 — *Bộ lọc URL* (định dạng tài liệu, phạm vi miền) và vấn đề *bẫy crawler* (Ch.9: nội dung sinh tự động → vô hạn trang) | Chỉ nhận `*.hust.edu.vn`; bỏ ảnh/CSS/JS/video; giới hạn độ dài URL, độ sâu, đoạn đường dẫn lặp, số tham số; trần số trang mỗi host | `UrlNormalizer`, `Config` |
| Ch.10 — *Tách nội dung*: trang chứa menu, quảng cáo là **nhiễu**; tìm khối nội dung bằng DOM/XPath/CSS | Loại `nav/aside/footer/header`, ô nhập liệu…, mở bọc `form`; chọn `article/main`; loại các khối có **mật độ liên kết** cao; Tika trích văn bản phần còn lại | `ContentExtractor` |
| Ch.9 — *Phát hiện nội dung trùng lặp*: shingling, hệ số **Jaccard**, vân tay | Băm SHA-1 văn bản chuẩn hóa để loại trùng chính xác; **SimHash 64-bit trên shingle 3 từ** để gắn cờ trùng gần đúng (khoảng cách Hamming ≤ 3) | `Crawler.evaluate`, `SimHash` |
| Ch.9 — *Web tĩnh có ~30% trùng lặp*, trang 404 sinh tự động | Đo thực tế: ~3,7% URL bị loại do trùng (chính xác hoặc canonical); phát hiện *soft-404* | `Crawler.evaluate` |
| Ch.10 — *Máy chủ liên kết*, biểu diễn danh sách kề | Mỗi trang lưu số liên kết ra (`outlinks`); dùng để chọn **trang hub** tải lại thường xuyên khi cập nhật. (Lưu đồ thị liên kết đầy đủ chưa làm — xem mục 8) | `Crawler.seedFrontier` |
| Ch.10 — *Phân giải DNS* là điểm nghẽn | Dùng bộ nhớ đệm DNS của JVM; mỗi host chỉ 1 kết nối đồng thời nên ít tra cứu lặp. Không cần DNS server riêng ở quy mô này | — |
| Ch.3 — Xây dựng chỉ mục ngược; Ch.2 — bộ từ vựng, tách từ | Văn bản trang hợp lệ được đưa vào **chỉ mục Lucene** (trường `content`, `StandardAnalyzer`) để dùng ngay cho các bước truy hồi sau | `UrlStore` |
| Ch.5 — Đánh giá (độ chính xác – precision) | Kiểm tra mẫu thủ công để ước lượng chất lượng bộ lọc (mục 5.4) | — |

Các chương còn lại (Ch.4 VSM/TF-IDF, Ch.6–8 xếp hạng/xác suất/phân lớp, Ch.11 phân tích liên kết, Ch.12) thuộc các bước sau
của dự án (xếp hạng, PageRank…); dữ liệu đầu ra của bước này được thiết kế để dùng lại cho chúng.

---

## 3. Lựa chọn công cụ

Yêu cầu: dùng **Java**, ưu tiên **Tika** và **Lucene** so với các ứng viên khác (không bắt buộc dùng cả hai ở bước này).

| Công cụ | Vai trò | Lý do chọn / so sánh |
|---|---|---|
| **Apache Tika** | Phát hiện kiểu nội dung và trích văn bản (HTML, PDF, Word, Excel, PowerPoint, TXT) | Một API cho mọi định dạng. Ứng viên khác (PDFBox, POI, Boilerpipe riêng lẻ) phải ghép nhiều thư viện. Nhờ Tika, trang PDF/Word của trường (quy chế, thông báo, đề án tuyển sinh) cũng được kiểm tra "có nội dung" thay vì bị bỏ qua |
| **Apache Lucene** | Kho trạng thái URL bền vững + chỉ mục nội dung | Dùng như một "cơ sở dữ liệu" khóa–giá trị có cập nhật theo khóa (`updateDocument` theo `url`): đọc lại được toàn bộ trạng thái cho lần chạy sau, không cần cài DB ngoài. Đồng thời tạo sẵn chỉ mục ngược nội dung cho bước tìm kiếm — đúng chủ đề môn học. Ứng viên khác: SQLite/H2 (không có chỉ mục văn bản), Solr/Elasticsearch (cần chạy dịch vụ riêng) |
| jsoup | Phân tích DOM, lấy liên kết, loại nhiễu | Tika không cung cấp truy cập DOM/CSS selector; jsoup cần để làm phần "tìm khối nội dung" ở Ch.10 |
| `java.net.http.HttpClient` | Tải trang | Có sẵn trong JDK 11, đủ cho yêu cầu; kiểm soát hoàn toàn chuyển hướng/ timeout |
| Không dùng Apache Nutch / crawler4j / Heritrix | — | Nutch dựa trên Hadoop, quá nặng và che giấu các cơ chế của môn học; tự cài đặt frontier/robots/lọc giúp bám sát nội dung Ch.10 và kiểm soát được tiêu chí lọc |

Maven đã được tải về `tools/apache-maven-3.9.9` (máy chưa có Maven); phụ thuộc được copy vào `target/lib` để chạy độc lập.

---

## 4. Thiết kế và cài đặt

### 4.1 Luồng xử lý

```
          ┌──────────────── nạp hàng đợi (mỗi lần chạy) ────────────────┐
 seeds ──►│ seed + trang hub cũ + sitemap (URL mới / lastmod mới)       │
 kho URL ►│ + URL PENDING còn dang dở + URL đã quá hạn tải lại          │
          └──────────────────────────────┬──────────────────────────────┘
                                         ▼
                       Frontier (kiểu Mercator: ưu tiên + mỗi host một hàng đợi + heap te)
                                         ▼
   N luồng: robots.txt ─► tải (HttpClient) ─► phân loại (HTML / tài liệu / khác)
                                         ▼
        ContentExtractor: jsoup (liên kết, loại nhiễu, mật độ liên kết) ─► Tika (văn bản)
                          │                                      │
                chuẩn hóa + lọc liên kết ─► URL mới (PENDING)    ▼
                                              đánh giá: có nội dung? soft-404? trùng?
                                                            ▼
                         UrlStore (Lucene): trạng thái VALID / INVALID(+lý do) / PENDING
                                                            ▼
                    Exporter: urls_valid.txt, urls_invalid.tsv, updates/, update_log.tsv
```

### 4.2 Tiêu chí "URL đạt yêu cầu"

Một URL được đưa vào `urls_valid.txt` khi **đồng thời**:

1. Nằm trong `hust.edu.vn` hoặc subdomain, được `robots.txt` cho phép, và trả **HTTP 200** (chuyển hướng 3xx được ghi nhận là không hợp lệ; URL đích được thu thập riêng).
2. Là HTML hoặc tài liệu Tika đọc được (PDF, Word, Excel, PowerPoint, TXT).
3. **Có nội dung:** sau khi loại nhiễu, văn bản chính có ≥ 200 ký tự và ≥ 30 từ (có thể đổi bằng `--min-chars`, `--min-words`).
4. Không phải trang lỗi trá hình (*soft-404*): tiêu đề/đầu văn bản chứa "404", "không tìm thấy", "not found" và văn bản ngắn.
5. Không có `<meta name="robots" content="noindex">`.
6. Không trùng lặp: nội dung khác với mọi URL hợp lệ đã có (băm SHA-1), và nếu trang khai báo `rel=canonical` trỏ sang một URL khác đã hợp lệ thì trang này bị xem là bản sao.

Mỗi URL bị loại đều có **lý do** trong `urls_invalid.tsv`: `no-content`, `http-404`, `redirect:<đích>`, `duplicate:<url gốc>`, `canonical-dup:<url>`, `noindex`, `soft-404`, `robots-disallow`, `unsupported-type`, `redirect-out-of-scope`, `error:<loại lỗi>`…
Trùng lặp *gần đúng* (SimHash) chỉ được **gắn cờ** (`neardup` trong kho); loại bỏ chỉ khi bật `--drop-near-dup`, vì nhiều trang thông báo của trường có mẫu rất giống nhau nhưng thông tin khác nhau.

### 4.3 Cơ chế cập nhật danh sách URL

Kho URL (thư mục `data/index`) lưu cho mỗi URL: trạng thái, mã HTTP, lý do, mã băm nội dung, SimHash, thời điểm thấy lần đầu / tải gần nhất / nội dung đổi lần cuối, số liên kết ra, `lastmod` trong sitemap. Mỗi lần `update`:

1. **Phát hiện URL mới:** đọc lại `sitemap.xml` (và sitemap index) của mọi host đã biết; URL chưa có trong kho được đăng ký. Ngoài ra, các **trang hub** (nhiều liên kết ra, như trang chủ/danh mục tin) được tải lại sớm hơn (mặc định mỗi 24 giờ) để thấy liên kết mới; mọi liên kết mới trích được đều vào kho.
2. **Phát hiện thay đổi:** URL có `lastmod` trong sitemap mới hơn lần tải gần nhất, hoặc đã quá `--revisit-days` (mặc định 7 ngày), được tải lại; thay đổi nội dung nhận biết qua mã băm (server của trường trả `Cache-Control: no-cache` nên không dựa vào ETag/Last-Modified).
3. **Phục hồi lỗi:** URL lỗi tạm thời (5xx, 429, 421, lỗi mạng) thử lại sau 1 ngày; `--recheck <lý do>` buộc tải lại các URL từng bị loại vì một lý do cụ thể (đã dùng khi sửa lỗi bộ lọc, mục 7).
4. **Xuất chênh lệch:** so với `urls_valid.txt` trước đó, ghi `updates/urls_added_<time>.txt` (URL mới đạt yêu cầu) và `urls_removed_<time>.txt` (URL không còn đạt: 404, hết nội dung…), thêm một dòng vào `update_log.tsv`. Tệp `urls_valid.txt` được ghi nguyên tử (ghi tệp tạm rồi đổi tên) nên không bao giờ ở trạng thái dở dang.
5. Lập lịch tự động bằng Windows Task Scheduler (lệnh mẫu trong `README.md`).

### 4.4 Các biện pháp lịch thiệp và an toàn

* Tuân thủ `robots.txt` (đệm theo host); User-Agent `HUSTIRBot/1.0` nêu rõ mục đích học tập.
* Mỗi host chỉ 1 request tại một thời điểm, cách nhau ≥ 500 ms (host chính 700 ms), thêm khoảng cách tối thiểu 100 ms giữa hai request bất kỳ.
* Gặp HTTP 429/503: khoảng chờ của host tăng gấp đôi (tối đa 10 s, tôn trọng `Retry-After`), giảm một nửa sau mỗi lần thành công; mỗi URL thử lại tối đa 3 lần.
* Đóng kết nối nếu server treo khi đang trả nội dung (watchdog 60 s); giới hạn kích thước (HTML 4 MB, tài liệu 20 MB).
* Host liên tục không kết nối được (5 lần, chưa lần nào thành công) bị loại tạm; host `mail.hust.edu.vn` bị chặn sẵn.
* Không đăng nhập, không gửi biểu mẫu; chỉ yêu cầu GET.

---

## 5. Kết quả

### 5.1 Số liệu tổng hợp

| Chỉ số | Giá trị |
|---|---|
| Số lần chạy (đã ghi trong `update_log.tsv`) | 6 (lần 1 crawl đầu, các lần sau là `update`/tiếp tục) |
| Tổng số trang đã tải về và đánh giá | ≈ 34.800 (22.178 hợp lệ + 12.589 không hợp lệ) |
| URL đã biết nhưng chưa tải (`pending`) | 11.159 (riêng `hust.edu.vn`: 5.361) |
| Số host (subdomain) đã gặp / có ít nhất 1 URL hợp lệ | 122 / 47 |
| Tỉ lệ hợp lệ trên số trang đã đánh giá | 63,8% |
| Tỉ lệ trùng lặp (chính xác + canonical) trên số trang đã đánh giá | 3,7% |
| Kích thước kho Lucene (`data/index`, gồm văn bản các trang hợp lệ) | ≈ 95 MB |
| Tệp `urls_valid.txt` | 1,8 MB, UTF-8, LF, một URL mỗi dòng |

### 5.2 Các host có nhiều URL hợp lệ nhất

| Host | URL hợp lệ | URL bị loại | Còn chờ |
|---|---:|---:|---:|
| fami.hust.edu.vn | 3.286 | 572 | 0 |
| clc.hust.edu.vn | 3.285 | 587 | 0 |
| ctt.hust.edu.vn | 2.822 | 1.423 | 0 |
| sdh.hust.edu.vn | 2.669 | 1.058 | 3 |
| soict.hust.edu.vn | 1.798 | 637 | 0 |
| sep.hust.edu.vn | 939 | 348 | 0 |
| seee.hust.edu.vn | 785 | 330 | 0 |
| **hust.edu.vn** (miền chính) | **635** | 134 | **5.361** |
| library.hust.edu.vn | 588 | 50 | 310 |
| users.soict.hust.edu.vn | 548 | 278 | 0 |

Miền chính `hust.edu.vn` còn nhiều URL chờ nhất vì server giới hạn tốc độ (HTTP 429) chặt hơn các subdomain; đây là ưu tiên của các lần `update` kế tiếp
(có thể chạy riêng bằng `--only-hosts hust.edu.vn`). Danh sách đầy đủ theo host: `crawl.bat stats`.

### 5.3 Lý do loại URL

| Lý do | Số URL | Ý nghĩa |
|---|---:|---|
| `no-content` | 4.632 | HTTP 200 nhưng sau khi loại nhiễu còn < 200 ký tự / < 30 từ (trang danh sách chỉ có liên kết, trang đăng nhập, trang JS…) |
| `http-404` | 3.276 | Trang không tồn tại (nhiều liên kết cũ trong sitemap/trang tin) |
| `redirect` | 2.087 | Chuyển hướng 3xx; URL đích được thu thập riêng |
| `canonical-dup` | 715 | Khai báo `rel=canonical` trỏ tới một URL khác đã hợp lệ |
| `noindex` | 672 | Trang đặt `meta robots noindex` |
| `duplicate` | 566 | Nội dung trùng chính xác với URL hợp lệ khác (SHA-1) |
| `error:*` | 290 | Lỗi mạng/timeout/kết nối |
| `redirect-out-of-scope` | 174 | Chuyển hướng ra ngoài `hust.edu.vn` |
| `robots-disallow` | 109 | `robots.txt` không cho phép |
| Khác (`http-403/400/500/421`, `unsupported-type`, `soft-404`) | 68 | |

### 5.4 Kiểm tra mẫu chất lượng bộ lọc

Kiểm tra thủ công bằng lệnh `crawl.bat inspect <url>` (tải lại trang và in kết quả trích xuất) trên mẫu ngẫu nhiên:

* **14 URL hợp lệ**: cả 14 đều có nội dung thực (thông báo, bài viết, lịch seminar, danh sách thi, danh sách cán bộ, 1 PDF luận văn 35.000 ký tự) → precision mẫu 14/14. Mẫu nhỏ nên chỉ mang tính chỉ báo.
* **16 URL bị loại `no-content`**: 11 trang thật sự rất ít chữ (18–151 ký tự: trang danh sách chỉ có liên kết, hồ sơ cán bộ một dòng, thư mục "Index of", tệp tải xuống); 5 trang trả nội dung rỗng hoàn toàn (3 trang `sdh.../home/default.aspx`, 1 trang `fintech`, 1 trang `*.sem`) — chưa xác định được đó là trang rỗng thật hay nội dung nạp bằng JavaScript. Không thấy trường hợp nào bị loại nhầm một bài viết rõ ràng. Lưu ý: lỗi loại nhầm hàng loạt từng xảy ra trước khi sửa (mục 7, dòng 1), mẫu này kiểm tra *sau* khi sửa.

---

## 6. Kiểm chứng cơ chế cập nhật

Các lần chạy thực tế (`output/update_log.tsv`):

| Thời điểm | Hợp lệ | Không hợp lệ | Chờ | Thêm | Bớt |
|---|---:|---:|---:|---:|---:|
| 20261010_005012 | 7196 | 4504 | 12289 | +7196 | −0 |
| 20261010_014641 | 12309 | 8825 | 14731 | +5113 | −0 |
| 20261010_021842 | 18507 | 9639 | 12462 | +6198 | −0 |
| 20261010_023616 | 19863 | 10936 | 11595 | +1357 | −1 |
| 20261010_030541 | 21850 | 12499 | 10864 | +1987 | −0 |
| 20261010_032340 | 22178 | 12589 | 11159 | +328 | −0 |

Trong các lần chạy trên website thật, cơ chế được dùng và kiểm chứng ở các khía cạnh sau:

* **Tiếp tục/khôi phục:** nhiều lần phải dừng tiến trình (do lỗi treo, do chỉnh tham số); mỗi lần chạy lại đều tiếp tục từ kho Lucene mà không mất tiến độ, các URL `pending` được tải tiếp.
* **Sitemap tăng dần:** lần quét sitemap đầu tiên đăng ký hàng nghìn URL mỗi host lớn; ở lần `update` có quét sitemap sau đó chỉ còn phát hiện thêm **4** URL mới (`icamt2024.smse.hust.edu.vn`) vì phần còn lại đã có trong kho — đúng với tính chất cập nhật tăng dần. (Bộ đếm "URL thay đổi" lúc đó còn tính cả URL chưa tải nên con số lớn trong nhật ký là sai lệch; đã sửa.)
* **Loại URL không còn đạt:** lần chạy 4 có **−1** URL (`sep.hust.edu.vn/.../danh-sach-can-bo-7.html` trả HTTP 421 ở lần tải lại) và ghi vào `updates/urls_removed_*.txt`. Đây thật ra là lỗi tạm thời của server; sau đó 421 được xếp vào nhóm lỗi tạm thời, thử lại ngay và lại sau 1 ngày.
* Số "+1.357" của lần chạy 4 chủ yếu là các URL tồn đọng ở trạng thái `pending` được tải trong lần đó, không phải toàn bộ là nội dung mới trên website.

Vì website thật không cho phép tạo ra thay đổi có chủ đích, cơ chế cập nhật còn được kiểm chứng bằng **test tích hợp** `UpdateMechanismTest`
trên một website cục bộ có thể sửa giữa hai lần chạy (`mvn test`):

| Tình huống | Lần 1 (crawl) | Sau khi website thay đổi → lần 2 (update) | Kết quả test |
|---|---|---|---|
| `/index.html` (trang hub) | hợp lệ | thêm liên kết tới `/c.html` | `/c.html` được phát hiện và **thêm** vào danh sách |
| `/b.html` | hợp lệ | bị xóa (404) | **bị loại** khỏi `urls_valid.txt`, ghi vào `urls_removed_*` |
| `/a.html` | hợp lệ | đổi nội dung | vẫn hợp lệ, `lastChanged` được cập nhật |
| `/dup.html` | loại vì trùng `/a.html` | `/a.html` đổi nội dung nên không còn trùng | trở thành hợp lệ và được **thêm** |
| `/empty.html` | loại `no-content` | không đổi | vẫn bị loại |
| `/gone.html` | loại `http-404` | không đổi | vẫn bị loại |


---

## 7. Vấn đề gặp phải và cách giải quyết

| # | Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|---|
| 1 | Hàng nghìn trang ASP.NET (`sdh`, `ctt`, `ctt-daotao`, `sis`) bị loại `no-content` | ASP.NET WebForms đặt **toàn bộ trang trong một thẻ `<form>`**; bộ lọc nhiễu xóa `form` nên xóa luôn nội dung | "Mở bọc" `form` thay vì xóa; thêm `--recheck no-content` để tải lại các URL đã loại nhầm (≈ 4.700 URL được đưa vào tải lại; số URL `no-content` giảm từ 4.727 còn 3.329 dù hàng nghìn URL mới được thêm vào) |
| 2 | HTTP 429 hàng loạt, một số sitemap bị đọc thiếu (1.640 thay vì 3.252 URL) | Các site NukeViet của trường có bộ chống flood theo IP/host | Giới hạn tốc độ, backoff nhân đôi theo host (AIMD), thử lại sitemap khi 429/503. Thử "làm chậm toàn cục" cho mọi host làm giảm thông lượng ~10 lần nên bỏ, chuyển sang điều chỉnh theo từng host |
| 3 | Crawl dừng ở đúng 12.000 trang nhưng không thoát | Đọc phần thân phản hồi không có timeout; một server treo giữa chừng làm kẹt luồng | Watchdog đóng luồng đọc sau 60 s |
| 4 | URL lặp tham số (`?menu=11&menu=11`) → 404 | Server `ctt-daotao` tự nhân đôi tham số trong redirect `http→https` | Chuẩn hóa loại bỏ tham số trùng |
| 5 | Đọc sitemap tuần tự cho ~100 host mất > 10 phút (kể cả thử lại vô ích khi lỗi kết nối) | — | Đọc song song theo host; chỉ thử lại khi 429/503 |
| 6 | HTTP 421 ngẫu nhiên ở vài host (`sep`, `seee`) | Lỗi tạm thời của server (cùng URL tải bằng curl vẫn 200) | Xếp vào nhóm lỗi tạm thời: thử lại ngay và lại sau 1 ngày |
| 7 | Backoff của host chính kẹt ở mức rất chậm (≈ 1 trang / 15 s) | Chỉ giảm 10% mỗi lần thành công | Giảm một nửa sau mỗi lần thành công |
| 8 | Tệp xuất dùng CRLF làm hỏng việc đọc từng dòng trong shell | `Files.write` dùng ký tự xuống dòng của hệ điều hành | Ghi cố định LF, UTF-8 |
| 9 | Tìm thấy `www.hust.edu.vn`, `libopac...` không truy cập được | Không phân giải DNS / cổng đóng | Loại host sau 5 lần lỗi kết nối liên tiếp |

---

## 8. Hạn chế và hướng phát triển

* **Độ phủ chưa đầy đủ.** Lần chạy dùng ngân sách trang; 11.159 URL còn ở trạng thái chờ. Chạy tiếp `update` (có thể đặt lịch hằng đêm) sẽ hoàn thiện dần. Host chính `hust.edu.vn` bị giới hạn tốc độ chặt nhất nên chậm nhất.
* **Trang dựng bằng JavaScript** (ví dụ `csv.hust.edu.vn`, một số trang `.sem`) cho văn bản rỗng vì crawler không chạy JS; các URL này bị loại `no-content`. Hướng xử lý: dùng trình duyệt headless hoặc gọi trực tiếp API dữ liệu của trang.
* **Ngưỡng 200 ký tự / 30 từ** là tham số kinh nghiệm; có thể gây loại nhầm vài trang rất ngắn nhưng hợp lệ (thông báo một dòng). Có thể hiệu chỉnh theo loại trang.
* **Trùng lặp gần đúng** mới được gắn cờ SimHash (so sánh tuyến tính O(n) trên bộ nhớ, đủ cho vài chục nghìn trang); quy mô lớn hơn cần bảng hoán vị hoặc LSH/MinHash như slide Ch.9.
* **Chứng chỉ TLS lỗi/hết hạn** ở một số subdomain làm trang không tải được (được tính là lỗi kết nối); chưa tắt kiểm tra chứng chỉ vì lý do an toàn.
* **Đồ thị liên kết** mới lưu số liên kết ra, chưa lưu danh sách kề; để làm PageRank/phân tích liên kết (Ch.11) cần lưu cạnh `(nguồn, đích)` và nén bằng kỹ thuật Boldi–Vigna như Ch.10.
* **Chạy phân tán** (Ch.10: chia host cho nhiều nút bằng hàm băm) chưa cần ở quy mô này; kiến trúc `Frontier` theo host cho phép mở rộng.
* Bước tiếp theo: dùng chính chỉ mục Lucene trong `data/index` (trường `content`, `title_t`) làm đầu vào cho truy vấn Boolean/VSM/BM25 của các bước sau.

---

## 9. Cấu trúc sản phẩm

```
hust-information-retrieval/
├── pom.xml, README.md
├── scripts/crawl.bat                 chạy crawler (crawl | update | export | stats | inspect)
├── src/main/java/vn/hust/ir/crawler/ mã nguồn (13 lớp, xem README)
├── src/test/java/.../                CoreTest (6 unit test) + UpdateMechanismTest (tích hợp)
├── output/
│   ├── urls_valid.txt                ◄ DANH SÁCH URL ĐẠT YÊU CẦU
│   ├── urls_invalid.tsv              URL bị loại + lý do
│   ├── updates/                      urls_added_*.txt, urls_removed_*.txt
│   └── update_log.tsv                nhật ký các lần chạy
├── data/index/                       kho URL + chỉ mục nội dung Lucene (≈ 95 MB)
├── logs/                             nhật ký các lần crawl
├── report/BAO_CAO.md                 báo cáo này
└── tools/apache-maven-3.9.9/         Maven đã tải về để build
```
