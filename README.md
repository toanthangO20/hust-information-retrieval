# HUST Crawler — thu thập URL của hust.edu.vn và các subdomain

Crawler viết bằng **Java 11**, dùng **Apache Tika** (trích văn bản HTML/PDF/Office), **Apache Lucene** (kho URL bền vững
và chỉ mục nội dung) và **jsoup** (phân tích DOM, lấy liên kết). Kết quả chính: `output/urls_valid.txt`.

## Build

```
set JAVA_HOME=C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot
tools\apache-maven-3.9.9\bin\mvn package          # chạy 7 test, sinh target\hust-crawler-1.0.jar + target\lib\
```

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
