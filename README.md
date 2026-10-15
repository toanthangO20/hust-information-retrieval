# HUST Crawler + Tìm Bách Khoa — thu thập URL hust.edu.vn, bộ dữ liệu ver1, chỉ mục và giao diện tìm kiếm

Crawler viết bằng **Java 11**, dùng **Apache Tika** (trích văn bản HTML/PDF/Office), **Apache Lucene** (kho URL bền vững
và chỉ mục nội dung) và **jsoup** (phân tích DOM, lấy liên kết). Kết quả chính: `output/urls_valid.txt`.

## Bắt đầu nhanh — báo cáo trực quan

| Lệnh | Việc |
|---|---|
| `scripts\report.bat` | Tính lại mọi số liệu (độ đo, kiểm định, đồ thị…) từ đầu ra của các bước → `docs\data\results.js`, mở **`docs\BAO_CAO_TRUC_QUAN.html`** (22 slide) |
| `scripts\search.bat serve` | Web tại http://localhost:8080 và **so sánh trực tiếp các mô hình** tại http://localhost:8080/compare.html |
| `scripts\demo-check.bat` | Kiểm tra trước buổi báo cáo: Java, jar, chỉ mục, mô hình, số liệu slide, MongoDB, web |
| `scripts\pipeline.bat` | Chạy lại đường ống từ dữ liệu đã crawl đến số liệu slide (bỏ qua bước đã có đầu ra; `-Force`, `-From N`) |

Kịch bản trình bày: `docs/KICH_BAN_BAO_CAO.md` (chỉ vị trí mã bằng nhãn `[DEMO:tên]`: `git grep -n "\[DEMO:"`).
Báo cáo đầy đủ: `report/BAO_CAO.md` (Phần D: đánh giá mô hình truy hồi; Phần E: báo cáo trực quan và quy trình).

## Build (chung cho crawler và ứng dụng tìm kiếm)

```
set JAVA_HOME=C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot
tools\apache-maven-3.9.9\bin\mvn package          # chạy toàn bộ test, sinh target\hust-crawler-1.0.jar + target\lib\
```

Các script trong `scripts\` dùng chung `scripts\_env.bat` (UTF-8, tự dò JAVA_HOME, báo lỗi rõ nếu chưa build).

## Chạy

```
scripts\crawl.bat crawl  [tuỳ chọn]   :: lần đầu (kho rỗng)
scripts\crawl.bat update [tuỳ chọn]   :: các lần sau: chỉ tải URL mới + URL đã "cũ" + trang hub; cập nhật danh sách
scripts\crawl.bat export              :: xuất lại danh sách từ kho, không tải thêm
scripts\crawl.bat stats               :: thống kê theo host
scripts\crawl.bat inspect <url>       :: chẩn đoán một URL (in tiêu đề, số ký tự/từ, văn bản trích được)
```

Tuỳ chọn thường dùng: `--max-pages N` (ngân sách mỗi lần chạy, mặc định 5000), `--max-per-host N`, `--threads N`,
`--delay-ms N` (giãn cách tới cùng một host, mặc định 500; host `hust.edu.vn` tối thiểu 700), `--global-delay-ms N` (giãn cách toàn cục, mặc định 100),
`--min-chars N` / `--min-words N` (ngưỡng "có nội dung": 200 ký tự / 30 từ), `--revisit-days N` (mặc định 7),
`--hub-revisit-hours N` (mặc định 24), `--seeds url1,url2`, `--only-hosts h1,h2` (chỉ tải các host này), `--recheck lý_do1,lý_do2` (tải lại URL từng bị loại với lý do đó, ví dụ `no-content`), `--no-sitemap`, `--drop-near-dup`, `--out DIR`, `--data DIR`.

`crawl` và `update` là cùng một luồng xử lý: kho URL (Lucene, thư mục `data/index`) cho biết URL nào đã biết, còn chờ,
đã lỗi hay đã cũ, nên chạy lại bất cứ lúc nào cũng là *tiếp tục* hoặc *cập nhật* chứ không bắt đầu lại.

## Đầu ra (`output/`)

| Tệp | Nội dung |
|---|---|
| `urls_valid.txt` | Danh sách URL đạt yêu cầu (HTTP 200, có nội dung, không trùng lặp), mỗi dòng một URL, đã sắp xếp |
| `urls_invalid.tsv` | URL bị loại: `url`, mã HTTP, lý do (`no-content`, `duplicate:<url>`, `redirect:<url>`, `http-404`, `soft-404`, `robots-disallow`, ...) |
| `updates/urls_added_<time>.txt` | URL mới đạt yêu cầu so với lần xuất trước |
| `updates/urls_removed_<time>.txt` | URL không còn đạt yêu cầu (hết hạn, 404, nội dung rỗng...) |
| `update_log.tsv` | Nhật ký từng lần chạy: số URL hợp lệ / không hợp lệ / đang chờ / thêm / bớt |

## Lên lịch cập nhật tự động (Windows Task Scheduler)

```
schtasks /Create /SC DAILY /ST 02:00 /TN "HUST-Crawler-Update" /TR "\"E:\Projects\Information Retrieval\hust-information-retrieval\scripts\crawl.bat\" update --max-pages 5000"
```

## Cấu trúc mã nguồn (`src/main/java/vn/hust/ir/crawler`)

| Lớp | Vai trò |
|---|---|
| `Main`, `Config` | CLI và cấu hình |
| `Crawler` | Điều phối: nạp hàng đợi, vòng lặp worker, kiểm tra điều kiện lọc |
| `Frontier` | Hàng đợi URL kiểu Mercator (ưu tiên phía trước, hàng đợi theo host phía sau + heap thời điểm te) |
| `Fetcher`, `RobotsCache`, `SitemapReader` | Tải trang, robots.txt, sitemap |
| `UrlNormalizer` | Chuẩn hóa URL, lọc bẫy crawler |
| `ContentExtractor` | jsoup (loại nhiễu, mật độ liên kết) + Tika (trích văn bản) |
| `SimHash` | Dấu vân tay nội dung để phát hiện trùng lặp gần đúng |
| `UrlStore` | Kho URL và chỉ mục nội dung bằng Lucene |
| `Exporter` | Xuất danh sách, chênh lệch, nhật ký |

---

# Bước 2 — Bộ dữ liệu ver1, chỉ mục và ứng dụng tìm kiếm

Xây trên `output/urls_valid.txt` và văn bản đã lưu trong `data/index` (không tải lại trang nào).

```
scripts\search.bat dataset            :: dựng dataset\ver1\ (documents.jsonl + manifest.json)
scripts\search.bat index              :: dựng index\ver1\ (Lucene, BM25)
scripts\search.bat serve              :: chạy giao diện tại http://localhost:8080
scripts\search.bat query học bổng     :: truy vấn nhanh trên dòng lệnh (--host, --type, --size)
```

Tuỳ chọn: `--version ver2` (đặt tên phiên bản), `--port N`, `--web-dir DIR` (đọc giao diện từ thư mục để chỉnh sửa không cần build lại),
`--urls FILE`, `--crawl-index DIR`. Cần `mvn package` trước (xem phần Build).

Tìm được có dấu lẫn không dấu (`hoc bong`), hỗ trợ `"cụm từ"`, `+bắt buộc`, `-loại trừ`, `tiêu*`; lọc theo trang con và loại tài liệu.
API: `GET /api/search?q=&host=&type=&page=&size=` và `GET /api/stats` (JSON).
Xuất kết quả: nút **Xuất kết quả** trên trang kết quả, hoặc `GET /api/export?q=&host=&type=&n=100&format=csv|txt` (n tối đa 1000).
So sánh mô hình: trang `/compare.html`, API `GET /api/compare-info` (mô hình + 60 truy vấn có nhãn) và
`GET /api/compare?q=&qid=&systems=current;bm25;dense;hybrid:w=2&k=10` (top-k từng mô hình; có `qid` thì kèm nhãn qrels và nDCG@10).
Mô hình nạp ở nền khi khởi động (`--no-compare 1` để tắt); dense/hybrid cần `models/multilingual-e5-small` và `index/ver1/dense_e5small_int8_256.bin`.

| Thành phần | Vai trò |
|---|---|
| `DatasetBuilder`, `Text`, `Mojibake` | Dựng bộ dữ liệu: làm sạch URL/dấu thời gian lẫn trong nội dung, sửa chữ vỡ UTF-8, gắn nhãn font cũ |
| `ViAnalyzer`, `SearchIndexer` | Phân tích tiếng Việt (bỏ dấu) và dựng chỉ mục |
| `SearchEngine` | Truy vấn BM25, thưởng khớp cụm/đúng dấu, lọc, facet, đoạn trích tô sáng |
| `WebServer`, `SearchMain` | Máy chủ HTTP (`127.0.0.1`) và các lệnh |
| `src/main/resources/web` | Giao diện (HTML/CSS/JS thuần, phông Be Vietnam Pro) |

`documents.jsonl` (~116 MB) và `index/` (~123 MB) không đưa vào git; dựng lại bằng hai lệnh đầu. `dataset/ver1/manifest.json` có số liệu và SHA-256.

---

# Bước 3 — Bóc tách nội dung, đồ thị liên kết, MongoDB

Tìm **khối nội dung chính** của từng trang (Ch10 tr.15–18), bóc tiêu đề / ngày đăng / tác giả / nguồn, văn bản tài liệu, và dựng
**đồ thị liên kết nguồn → đích : văn bản mô tả** (gồm cả ảnh và tài liệu). Chi tiết thuật toán, đánh giá: `report/BAO_CAO.md` (Phần C);
lược đồ dữ liệu: `docs/SCHEMA.md`; tập vàng và quy ước gán nhãn: `eval/extract/README.md`.

```
scripts\extract.bat snapshot          :: P1: tải lại urls_valid.txt, lưu HTML/tài liệu thô vào data\raw\ (chạy lại được, ~1 giờ)
scripts\extract.bat run               :: P2-P5: tìm khối nội dung + metadata + liên kết -> data\extract\v1\pages.jsonl, links.jsonl
scripts\extract.bat graph             :: gộp đích về URL chuẩn, văn bản neo, PageRank -> pages.final.jsonl, links.final.jsonl
scripts\extract.bat index             :: chỉ mục thử nghiệm index\ver1x (main_t, anchor_t, anchor_c, PageRank)
scripts\extract.bat load              :: P6: nạp MongoDB (mặc định mongodb://localhost:27017, CSDL hust_ir)
scripts\extract_all.bat               :: run + graph + index
scripts\extract.bat eval  --gold eval\extract\gold_test.tsv --report ...   :: đánh giá thuật toán tìm khối (so 13 cấu hình)
scripts\extract.bat eval-meta --gold eval\extract\gold_test.tsv --report ... :: đánh giá tiêu đề/ngày/tác giả
scripts\extract.bat train-blocks      :: huấn luyện bộ phân loại khối (hồi quy logistic) trên tập vàng dev
scripts\extract.bat show --url URL    :: chẩn đoán một trang: khối, nhãn nội dung, metadata
scripts\eval.bat run --index index\ver1x --systems "bm25x:ctx=main+anchor,aw=0.3;..." --split dev --out ...  :: so sánh truy hồi
scripts\extract.bat strips            :: nhãn từng khối của tập vàng test -> eval\extract\strips_test.json (slide "dải khối")
```

MongoDB: `scripts\mongo.bat` (MongoDB 7.0.14 bản zip trong `tools\mongodb`, dữ liệu `data\mongo`; hoặc `docker run -d -p 27017:27017 mongo:7`)
rồi `scripts\extract.bat load` (tuỳ chọn `--uri`, `--db`, `--keep`). Đã nạp và kiểm tra: `hust_ir.pages` 80.060, `hust_ir.links` 1.601.816.
Không có MongoDB vẫn dùng được toàn bộ kết quả ở dạng JSONL (`data\extract\v1\*.final.jsonl`, cùng lược đồ).

| Lớp (`vn.hust.ir.extract`) | Vai trò |
|---|---|
| `Snapshot`, `RawStore` | Tải lại và lưu HTML/tài liệu thô (gzip) + manifest; theo chuyển hướng, thử lại, lịch thiệp theo host |
| `PageModel` | DOM → dãy token (thẻ/từ) và các khối văn bản |
| `BlockFinder` | BTE (slide Ch10), phân loại đặc trưng nông, quy tắc CSS theo host, lọc khuôn mẫu theo host, bộ phân loại học có giám sát |
| `BlockFeatures`, `LogReg`, `BlockTrainer` | Đặc trưng khối, hồi quy logistic, huấn luyện + kiểm định chéo |
| `MetadataExtractor`, `DocExtractor` | Tiêu đề / ngày đăng / tác giả / nguồn (HTML), metadata tệp (Tika) |
| `LinkExtractor`, `GraphBuilder` | Cạnh `a/img/iframe`, văn bản neo, `inContent`, quy đích về URL chuẩn, PageRank |
| `MongoLoader`, `ExtractIndexer` | Nạp MongoDB có `$jsonSchema`; chỉ mục thử nghiệm cho so sánh truy hồi |
| `BlockEval`, `GoldSheet` | Đánh giá trên tập vàng; tạo phiếu gán nhãn |

`data/raw` (~1,5 GB) và `data/extract` (~1,4 GB) không đưa vào git.

---

# Bước 4 — Đánh giá mô hình truy hồi và báo cáo trực quan

Bộ kiểm thử kiểu TREC (60 truy vấn, 20 dev / 40 test, qrels 3 mức), 7 mô hình (W, VSM, BM25, PRF, dense, hybrid RRF, cross-encoder),
kiểm định hoán vị + Holm + bootstrap. Hướng dẫn và kết quả: `eval/README.md`; phân tích: `report/BAO_CAO.md` Phần D.

```
scriptsval.bat run --systems "bm25;hybrid:w=2" --split test --out evaluns	est   :: chạy mô hình, xuất run TREC
scriptsval.bat eval --runs evaluns	est --qrels eval\qrels	est.tsv --split test --baseline bm25 --out evaleport	est.md
scriptsval.bat bench --systems "bm25;hybrid:w=2" --split test                     :: độ trễ -> evaleport\latency.tsv
scriptseport.bat                                                                   :: số liệu slide -> docs\dataesults.js
```

| Lớp (`vn.hust.ir.eval`) | Vai trò |
|---|---|
| `EvalMain`, `EvalIo`, `Metrics`, `Significance` | Lệnh embed / run / pool / qrels / eval / bench / dashboard; độ đo; kiểm định |
| `VsmRetriever`, `Bm25Retriever`, `PrfRetriever`, `DenseRetriever`, `HybridRetriever`, `DenseRocchioRetriever`, `CrossEncoderRetriever` | Các mô hình M1–M6 |
| `Dashboard` | Sinh `docs/data/results.js` từ đầu ra của mọi bước (một nguồn số liệu cho slide) |
| `LiveCompare` | So sánh trực tiếp các mô hình trên web (`/compare.html`) |
