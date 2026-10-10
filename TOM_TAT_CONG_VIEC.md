# Tóm tắt công việc đã làm

## Yêu cầu
Crawl thông tin về Đại học Bách khoa Hà Nội qua `hust.edu.vn` và các subdomain; lọc URL không có nội dung; xuất danh sách URL đạt yêu cầu ra file `.txt`; có cơ chế cập nhật danh sách khi có thông tin mới; viết báo cáo; tham khảo slide môn học. Công cụ: Java, ưu tiên Tika và Lucene.

## Đã thực hiện
1. **Nghiên cứu slide môn học** (`resources_13.7m`): chủ yếu Chương 10 (kiến trúc crawler, hàng đợi Mercator, robots.txt, chuẩn hóa URL, tách nội dung) và Chương 9 (phát hiện trùng lặp, shingling/SimHash).
2. **Khảo sát website:** `hust.edu.vn` dùng NukeViet, có sitemap; phát hiện hàng chục subdomain (NukeViet, ASP.NET, WordPress…), nhiều site giới hạn tốc độ (HTTP 429).
3. **Dựng dự án Java 11 + Maven** (tải Maven về `tools/`): Tika (trích văn bản HTML/PDF/Office), Lucene (kho URL bền vững + chỉ mục nội dung), jsoup (DOM, liên kết).
4. **Cài đặt crawler** (13 lớp trong `src/main/java/vn/hust/ir/crawler`): frontier kiểu Mercator, robots.txt, giãn cách theo host, chuẩn hóa/lọc URL, đọc sitemap, trích nội dung, lọc "có nội dung", loại trùng (SHA-1, canonical, SimHash gắn cờ), điều chỉnh tốc độ khi gặp 429.
5. **Cơ chế cập nhật:** lệnh `update` quét sitemap, tải lại trang hub/URL quá hạn/URL còn chờ; xuất `urls_added_*`, `urls_removed_*`, `update_log.tsv`; tùy chọn `--recheck`, `--only-hosts`; lệnh `inspect` để chẩn đoán URL.
6. **Chạy crawl thật** qua 7 lượt, vừa chạy vừa sửa lỗi phát sinh:
   - trang ASP.NET bị loại nhầm do thẻ `<form>`;
   - server treo làm kẹt luồng (thêm watchdog);
   - bị 429 và sitemap đọc thiếu (backoff theo từng host, thử lại);
   - tham số URL bị nhân đôi khi redirect; lỗi 421; file xuất CRLF.
7. **Kiểm thử:** 7 test đạt (6 unit test + 1 test tích hợp cơ chế cập nhật trên website cục bộ); kiểm tra mẫu thủ công chất lượng bộ lọc.
8. **Viết tài liệu:** `README.md`, `report/BAO_CAO.md`.

## Kết quả
| Hạng mục | Giá trị |
|---|---|
| URL đạt yêu cầu | **22.178** (47 host; 2.025 PDF/Office) → `output/urls_valid.txt` |
| URL bị loại + lý do | 12.589 → `output/urls_invalid.tsv` |
| URL đã phát hiện, chưa tải | 11.159 (riêng `hust.edu.vn`: 5.361) |
| Kho Lucene | `data/index` (~95 MB) |

## Hạn chế chính
- Độ phủ chưa đầy đủ; miền chính `hust.edu.vn` bị giới hạn tốc độ nhất nên mới tải được một phần. Chạy tiếp `scripts\crawl.bat update` (hoặc `--only-hosts hust.edu.vn`) để hoàn thiện.
- Trang dựng bằng JavaScript cho văn bản rỗng nên bị loại `no-content`.
- Kiểm tra chất lượng mới trên mẫu nhỏ.

## Tệp quan trọng
- `output/urls_valid.txt` — danh sách kết quả
- `report/BAO_CAO.md` — báo cáo chi tiết
- `README.md` — hướng dẫn build/chạy/lập lịch
- `scripts/crawl.bat` — chạy crawler (`crawl | update | export | stats | inspect`)

---

# Cập nhật bước 2 — Bộ dữ liệu ver1, chỉ mục, giao diện (2026-10-10)

## Yêu cầu
Dựa trên danh sách URL đã crawl: tạo bộ dữ liệu `ver1` và chỉ mục để truy vấn; xây giao diện đẹp, tươi sáng, ít dấu hiệu "do AI tạo"; cập nhật báo cáo.

## Đã thực hiện
1. **Bộ dữ liệu `dataset/ver1`** từ `urls_valid.txt` + văn bản trong kho Lucene: 22.178 tài liệu / 47 host / 16,7 triệu từ; `manifest.json` có thống kê và SHA-256.
2. **Làm sạch** khi kiểm tra văn bản thật: bỏ URL/dấu thời gian CMS lẫn trong nội dung; **sửa chữ vỡ UTF-8** (41 tài liệu `ctt`); gắn nhãn 43 tài liệu font TCVN3/VNI cũ và loại khỏi chỉ mục.
3. **Chỉ mục `index/ver1`** (Lucene BM25): 22.135 tài liệu, bỏ dấu tiếng Việt, thêm trường giữ dấu để phân biệt `ký/kỳ`, `xá/xã`; thưởng khớp cụm; tự nới `AND`→`OR`.
4. **Ứng dụng web**: máy chủ HTTP Java + giao diện HTML/CSS/JS thuần; tô sáng, lọc theo trang con/loại, phân trang, thống kê bộ dữ liệu; chạy tốt trên điện thoại.
5. **Sửa lỗi bước 1** phát hiện khi chạy lại test: `UpdateMechanismTest` chập chờn do URL trùng bị xét theo trạng thái cũ của URL gốc trong cùng một lượt; thêm lượt xét lại trong `Crawler.run()`.
6. **Kiểm thử:** 14 test đạt (7 test mới); đo 13 truy vấn mẫu, ≈ 19 ms/truy vấn, 31/36 vị trí top-3 đúng chủ đề (nhận xét chủ quan).
7. **Báo cáo:** thêm Phần B (mục 10–16) vào `report/BAO_CAO.md`; cập nhật `README.md`.

## Lệnh chạy
`scripts\search.bat dataset` → `scripts\search.bat index` → `scripts\search.bat serve` → http://localhost:8080

## Hạn chế chính
- 43 tài liệu font cũ chưa chuyển mã; chưa tách từ ghép tiếng Việt; chưa có tập đánh giá có nhãn.
- Độ phủ dữ liệu vẫn như bước 1 (11.159 URL chờ); chạy `update` rồi dựng `ver2`.
- `ký túc xá` và `quy chế đào tạo đại học` còn kết quả chưa tốt ở top 3 (xem báo cáo mục 12.4).
