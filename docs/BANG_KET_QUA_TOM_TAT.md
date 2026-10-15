# Bảng tóm tắt kết quả công việc — HUST Information Retrieval

Hệ thống thu thập và truy vấn thông tin về ĐH Bách khoa Hà Nội (`hust.edu.vn` và các subdomain).
Công nghệ: Java 11 · Tika · Lucene · jsoup · MongoDB · ONNX Runtime.

Bản trực quan (biểu đồ, kiểm định, so sánh từng truy vấn): `docs/BAO_CAO_TRUC_QUAN.html` — số liệu sinh tự động bởi `scriptseport.bat`.

## 1. Tổng quan theo giai đoạn

| # | Giai đoạn | Việc đã làm | Kết quả chính | Kiến thức môn học |
|---|---|---|---|---|
| 1 | **Crawler** | Frontier kiểu Mercator; robots.txt + giãn cách theo host; chuẩn hoá và lọc URL; đọc sitemap; lọc "có nội dung" / soft-404; loại trùng SHA-1 + SimHash; giảm tốc khi bị 429; cập nhật tăng dần | **22.178** URL hợp lệ · 47 host · 16,7 triệu từ · 12.589 URL bị loại (có ghi lý do) · 7 lượt chạy thật | Ch10, Ch09 |
| 2 | **Bộ dữ liệu, chỉ mục, giao diện** | Bộ dữ liệu `ver1`; làm sạch UTF-8; chỉ mục Lucene BM25 có bộ phân tích tiếng Việt (bỏ dấu + trường giữ dấu); giao diện web | BM25 **≈ 1 ms**/truy vấn (công cụ web kèm đoạn trích ≈ 115 ms); giao diện chạy được trên điện thoại | Ch04, Ch07 |
| 3 | **Bóc tách nội dung** | BTE (O(n)); baseline; bộ phân loại hồi quy logistic 37 đặc trưng; lọc khuôn mẫu theo host; trích metadata | F1 macro **0,870** (học + lọc khuôn mẫu) so với baseline 0,813 · tiêu đề 0,75 / ngày đăng 0,96 / tác giả 0,96 | Ch10 |
| 4 | **Đồ thị liên kết + MongoDB** | Đồ thị nguồn → đích có văn bản liên kết; PageRank hai biến thể; lược đồ `$jsonSchema` + chỉ mục | **80.060** nút · **1.601.816** cạnh (69.989 trong nội dung chính) | Ch10 (liên kết) |
| 5 | **Đánh giá truy hồi** | Bộ kiểm thử kiểu TREC (60 truy vấn, qrels 3 mức); 6 mô hình; nDCG/MAP/MRR/Recall; kiểm định hoán vị + Holm | Hybrid BM25 + dense (w=2, chốt trên dev): nDCG@10 **0,685**, Recall@100 **0,809** (BM25: 0,675 / 0,688) | Ch04–Ch07 |

## 2. Crawler — số liệu

| Chỉ số | Giá trị |
|---|---:|
| URL hợp lệ | 22.178 |
| URL bị loại (`no-content`, `duplicate`, `http-404`, `soft-404`, `robots-disallow`…) | 12.589 |
| URL chưa tải (do `hust.edu.vn` giới hạn tốc độ) | 11.159 |
| Số lượt chạy thật | 7 |

## 3. Bóc tách nội dung — F1 mức từ (tập test, 49 trang)

| Cấu hình | F1 macro | F1 micro |
|---|---:|---:|
| Baseline jsoup (mật độ liên kết) | 0,813 | 0,930 |
| BTE thuần (Ch10) | 0,662 | 0,895 |
| Phân loại học | 0,845 | 0,972 |
| **Học + lọc khuôn mẫu** | **0,870** | **0,971** |

> BTE thuần kém baseline trên trang thật của HUST (menu nhiều chữ, nội dung ngắn) → phải kết hợp đặc trưng và học máy.

## 4. Đồ thị liên kết — số nút

| Loại nút | Số lượng |
|---|---:|
| html | 34.876 |
| ảnh | 23.284 |
| ngoài miền | 16.855 |
| tài liệu | 5.045 |
| **Tổng** | **80.060** |

## 5. Đánh giá truy hồi — tập test (40 truy vấn)

| Hệ thống | nDCG@10 | MAP | MRR@10 | R@100 |
|---|---:|---:|---:|---:|
| Công cụ ứng dụng hiện tại | 0,573 | 0,346 | 0,787 | 0,448 |
| M1 VSM `lnc.ltc` | 0,492 * | 0,361 | 0,783 | 0,647 |
| M2 BM25 đa trường | 0,675 | 0,488 | 0,883 | 0,688 |
| M3 BM25 + PRF (RM3) | 0,674 | 0,489 | 0,894 | 0,668 |
| Dense (multilingual-e5-small) | 0,631 | 0,448 | 0,864 | 0,685 |
| **M4 Hybrid (BM25 + dense, RRF, w=2)** | **0,685** | 0,536 | 0,960 | 0,809 |
| M6 Cross-encoder (thử nghiệm) | 0,702 | 0,600 | 0,921 | 0,876 |
| *Phân tích sau: hybrid w=1 (tốt nhất trên test, không dùng để kết luận)* | *0,701* | *0,585* | *0,953* | *0,876* |

`*` khác biệt có ý nghĩa thống kê so với BM25 (hoán vị ghép cặp, hiệu chỉnh Holm, p = 0,0045). M4 chọn w=2 trên dev trước khi chạy test; w=1 chỉ là phân tích sau..

## 6. Kết luận (nêu cả kết quả âm)

| # | Kết luận |
|---|---|
| 1 | Chỉ VSM kém hơn BM25 có ý nghĩa thống kê; PRF không cải thiện so với BM25. |
| 2 | Hybrid tăng Recall@100 rõ (0,69 → 0,81); nDCG@10 chưa khác biệt có ý nghĩa với 40 truy vấn; trọng số tốt nhất đảo ngược giữa dev và test (nhiễu mẫu nhỏ). |
| 3 | Văn bản liên kết và PageRank **không** cải thiện xếp hạng (kết quả âm, đã ghi nhận). |
| 4 | Cross-encoder: dev tăng mạnh nhưng test chỉ +0,016 so với M4 (không có ý nghĩa), chậm ≈ 12 giây/truy vấn → không kết luận là cải thiện. |
| 5 | Công cụ hiện tại yếu trên truy vấn dạng câu hỏi (nDCG ≈ 0,04) → hướng cải tiến. |

## 7. Hạn chế

| Hạn chế | Ghi chú |
|---|---|
| Độ phủ chưa đầy đủ | 11.159 URL chưa tải; trang dựng bằng JavaScript bị loại `no-content` |
| Nhãn đánh giá truy hồi do mô hình ngôn ngữ soạn/gán, chưa có người duyệt | Chấm lại mù 100 cặp: đồng thuận 77%, kappa 0,65 (nhất quán nội bộ, không phải giữa người với người) |
| Quy tắc metadata chỉnh sau khi xem lỗi cả dev và test | Số liệu test lạc quan hơn thực tế |
| Chỉ 40 truy vấn test | Khoảng tin cậy rộng |
| Chưa tách từ ghép tiếng Việt | Hiện dùng âm tiết; hướng phát triển ưu tiên |

## 8. Hướng tiếp theo

Tách từ ghép tiếng Việt · mở rộng bộ truy vấn và gán nhãn bởi người · chạy `update` để dựng `ver2` với 11.159 URL còn lại · học xếp hạng (LTR).
