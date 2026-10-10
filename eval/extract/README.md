# Tập vàng đánh giá bóc tách nội dung (bước 3)

```
eval/extract/
  sheets/dev.txt, test.txt         phiếu gán nhãn: mỗi trang một khối dòng "id|số từ|mật độ liên kết|thẻ|văn bản"
  gold_dev.tsv, gold_test.tsv      nhãn: url, số khối, các khối nội dung chính, tiêu đề, ngày, tác giả
  report_blocks_{dev,test}.md      so sánh 13 cấu hình tìm khối (P/R/F1 mức từ, macro & micro)
  report_blocks_*_per_page.csv     chi tiết theo trang (tp, số từ dự đoán, số từ vàng)
  report_meta_{dev,test}.md        độ chính xác tiêu đề / ngày đăng / tác giả + danh sách trường hợp sai
```

## Cách lấy mẫu

`scripts\extract.bat sheet --n 100 --seed 7` lấy trang HTML (2–150 khối) **vòng tròn theo host** rồi chia xen kẽ dev/test: mỗi host đóng góp
số trang gần bằng nhau, nên host nhỏ (hội nghị, phòng thí nghiệm) cũng được đánh giá thay vì bị `fami`/`clc`/`ctt` lấn át.
Phiếu phụ thuộc cách chia khối của `PageModel`: nếu đổi thuật toán chia khối, `nblocks` trong tệp vàng không còn khớp và trang đó bị bỏ qua
(báo cáo ghi "lệch phân khối").

## Quy ước gán nhãn

* **Nội dung chính** = phần riêng của trang: tiêu đề bài, thân bài, dòng ngày/tác giả của bài, danh sách hoặc bảng đặc thù của trang
  (ví dụ danh sách cán bộ, danh sách ấn phẩm), biểu mẫu của chính trang đó (tra cứu văn bằng, đăng nhập).
* **Không tính:** menu, breadcrumb, chuyển trang, tin liên quan / mới hơn / cũ hơn, ô tìm kiếm, widget dùng chung (bài mới, thống kê truy cập, lịch),
  chân trang, đánh giá/chia sẻ/bình luận, tên tệp ảnh trong thư viện.
* **Tiêu đề** = tiêu đề bài; với trang không phải bài viết: thẻ `<title>` bỏ hậu tố tên site (vd. "Home | SEDIC" → "Home"); `-` nếu không có; `?` = không xác định, bỏ qua.
* **Ngày đăng** = ngày của *bài viết* (hiển thị hoặc khai trong JSON-LD/`meta`), không phải ngày sự kiện/hạn đăng ký; `-` nếu không có.
* **Tác giả** = người/nhóm ký bài; không tính tên đơn vị/site.
* Nhãn do **một người** gán (không có kappa). 5 nhãn ngày đã được gán lại theo JSON-LD sau khi phát hiện phiếu không hiển thị dữ liệu có cấu trúc.

## Lệnh

```
scripts\extract.bat eval      --gold eval\extract\gold_test.tsv --report eval\extract\report_blocks_test.md
scripts\extract.bat eval-meta --gold eval\extract\gold_test.tsv --report eval\extract\report_meta_test.md
scripts\extract.bat train-blocks --l2 0.0001 --epochs 3000 --lr 0.5      (chỉ trên gold_dev.tsv)
```

Mô hình `block_model.json` huấn luyện **chỉ trên dev**; tập test không được dùng để chọn tham số của bộ phân loại khối. Riêng các quy tắc metadata
đã được chỉnh sau khi xem lỗi cả hai tập (xem báo cáo, mục 20).
