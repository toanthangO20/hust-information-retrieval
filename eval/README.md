# Hạ tầng đánh giá các mô hình truy vấn

Bộ kiểm thử theo kiểu TREC (Ch05 của slide): **tập văn bản** (`dataset/ver1`, 22.135 tài liệu trong chỉ mục) +
**tập truy vấn** (`topics.tsv`) + **tập đánh giá phù hợp** (`qrels/`). Mọi hệ thống chạy trên cùng chỉ mục `index/ver1`.

```
eval/
  topics.tsv            qid, split (dev|test), type, truy vấn, mô tả nhu cầu thông tin
  runs/{dev,test}/      mỗi hệ thống một tệp TREC run: "qid Q0 docid rank score tag" (top-100)
  pool/{dev,test}.tsv   tập (truy vấn, tài liệu) cần gán nhãn + hệ thống đóng góp
  pool/{dev,test}.txt   bản đọc được: tiêu đề, URL, đoạn trích khớp truy vấn
  judgments/*.tsv       nhãn đã gán: "qid<TAB>mức<TAB>id,id,..." (mức 2 hoặc 1; tài liệu trong pool không liệt kê = 0)
  qrels/{dev,test}.tsv  qrels chuẩn TREC "qid 0 docid mức" (đã gồm mức 0 cho tài liệu trong pool)
  report/               bảng kết quả (.md) và chi tiết theo truy vấn (.csv)
  work/                 tệp tạm (không đưa vào git)
```

## Lệnh (`scripts\eval.bat`, cần `mvn package` trước)

| Lệnh | Việc |
|---|---|
| `embed` | Nhúng toàn bộ tài liệu bằng multilingual-e5-small (ONNX int8) -> `index/ver1/dense_e5small_int8_256.bin` |
| `run --systems "a;b;c" --split dev --out eval/runs/dev` | Chạy các hệ thống, xuất run |
| `pool --runs "a.run;b.run" --depth 15 --split dev --out eval/pool/dev` | Gộp top-k của các run thành pool để gán nhãn |
| `qrels --pool eval/pool/dev.tsv --judgments eval/judgments/dev.tsv --out eval/qrels/dev.tsv` | Gộp nhãn thành qrels |
| `eval --runs "…" --qrels eval/qrels/test.tsv --split test --baseline NAME --out eval/report/test.md` | Chấm điểm, kiểm định, báo cáo |
| `bench --systems "a;b" --split test [--rounds 5]` | Đo độ trễ: 1 lượt khởi động + trung vị các lượt → `eval/report/latency.tsv` (giữ dòng của hệ thống không đo lại) |
| `dashboard` (hoặc `scripts\report.bat`) | Sinh `docs/data/results.js` cho `docs/BAO_CAO_TRUC_QUAN.html`: tính lại độ đo, kiểm định, theo loại truy vấn, ma trận cặp, lưới tham số, độ nhạy trọng số hybrid |

Kiểm định trong `dashboard` dùng cùng hạt giống với `eval` khi `--runs` liệt kê `bm25` trước rồi theo thứ tự W, M1, M3, D, M4, M5, M6
(như `report/{dev,test}_final.md`), nên số trên slide và trong báo cáo markdown trùng khớp.

Đặc tả hệ thống: `current` · `vsm` · `bm25[:k1=,b=]` · `prf[:k1=,b=,docs=,terms=,lambda=]` · `dense` · `hybrid[:k1=,b=,w=,rrf=]`.

## Các mô hình

| Mã | Mô hình | Slide |
|---|---|---|
| `current` | Công cụ đang chạy của ứng dụng (BM25 + thưởng cụm từ/đúng dấu + nới AND→OR). Tham chiếu | Ch07 |
| M1 `vsm` | VSM tf-idf, `lnc.ltc` (log tf, cosine), một trường tiêu đề+nội dung | Ch04 |
| M2 `bm25` | BM25 đa trường (tiêu đề ×3, nội dung ×1, URL ×0,6), ngữ nghĩa OR; chỉnh `k1`, `b` | Ch07 |
| M3 `prf` | M2 + giả lập phản hồi (RM3/Rocchio) | Ch06 |
| M4 `hybrid` | M2 + dense (multilingual-e5-small) hợp nhất bằng RRF | ngoài slide |
| `dense` | Chỉ vec-tơ dày (để phân tích đóng góp của từng thành phần) | ngoài slide |
| M5 `drocchio` | Rocchio trên vec-tơ dày, dẫn đường bằng xếp hạng lai (chỉ chạy trên dev) | Ch06 mở rộng |
| M6 `ce` | Cross-encoder bge-reranker-v2-m3 xếp lại top-30 của hybrid | ngoài slide |

## Quy ước đánh giá

* Phù hợp nhị phân = mức >= 1; nDCG dùng mức 0/1/2 làm độ lợi, chiết khấu theo slide (`r1 + Σ ri/log2 i`),
  IDCG tính từ **toàn bộ qrels** của truy vấn (slide cảnh báo chuẩn hóa theo danh sách trả về gây sai lệch khi so sánh mô hình).
* Độ đo chính: **nDCG@10**. Bổ sung: MAP, MRR@10, P@10, R@100, Judged@10 (tỉ lệ top-10 đã có nhãn).
* Tài liệu không có trong qrels coi là không phù hợp (quy ước TREC); Judged@10 cho biết mức độ ảnh hưởng.
* Tham số chỉ chỉnh trên tập **dev**; tập **test** chỉ dùng cho kết quả cuối. Pool của test dựng *sau khi* đã chốt tham số.
* Kiểm định: hoán vị dấu ghép cặp theo truy vấn (20.000 vòng), hiệu chỉnh Holm; khoảng tin cậy bootstrap 95%.
* Độ ổn định pool: bỏ lần lượt từng hệ thống khỏi pool và so lại thứ tự (tau của Kendall).

## Cấu hình chốt và kết quả (tập test, 40 truy vấn)

Chọn trên dev (q01–q20), chạy một lần trên test (q21–q60). Báo cáo chính: `report/test_final.md` (chỉ các cấu hình chốt,
cùng họ so sánh Holm như slide); `report/test.md` là bản cũ gồm cả các biến thể tham số.

| Mã | Cấu hình (chốt trên dev) | nDCG@10 | R@100 | Δ so với BM25 [KTC 95%] · p (Holm) |
|---|---|---:|---:|---|
| W | `current` | 0,573 | 0,448 | −0,102 [−0,208; −0,007] · 0,279 |
| M1 | `vsm` | **0,492** | 0,647 | −0,183 [−0,282; −0,086] · **0,0045** |
| M2 | `bm25` (k1, b mặc định) | 0,675 | 0,688 | tham chiếu |
| M3 | `prf:docs=5,terms=20,lambda=0.5` | 0,674 | 0,668 | −0,002 · 1,000 |
| D | `dense` | 0,631 | 0,685 | −0,044 · 1,000 |
| M4 | `hybrid:w=2` | 0,685 | 0,809 | +0,010 [−0,065; +0,086] · 1,000 |
| M6 | `ce:depth=30,len=320` (Judged@10 0,84) | 0,702 | 0,876 | +0,026 · 1,000 |

Phân tích sau (không dùng để kết luận, vì chọn bằng test): `hybrid:w=1` 0,701; `hybrid:w=4` 0,666. Trên dev thứ tự ngược lại
(w=4 > w=2 > w=1), tức trọng số tối ưu không ổn định giữa hai tập — chênh lệch nằm trong nhiễu của 20–40 truy vấn.

Kết luận: chỉ VSM kém hơn BM25 có ý nghĩa thống kê; hybrid tăng độ phủ rõ (R@100 0,69 → 0,81) nhưng 40 truy vấn chưa đủ để khẳng định nDCG@10.
Công cụ hiện tại (`current`) sụp đổ trên truy vấn dạng câu hỏi (nDCG@10 ~0,04, trong khi BM25 0,71) vì ngữ nghĩa AND/cụm từ.

Các run thử nghiệm (M5 `drocchio_*`, M6 `ce_*`, `hybrid_w-3/4`) đã được chép từ `eval/work/` vào `eval/runs/{dev,test}/` để tái lập được.

## Hạn chế cần nêu khi báo cáo

* **Nhãn do một mô hình ngôn ngữ (Claude) gán, không có người duyệt**; truy vấn cũng do mô hình soạn. Kết quả phản ánh
  "phù hợp theo đánh giá của Claude", có thể thiên về hệ thống cho kết quả giống cách Claude đọc (nghiêng về ngữ nghĩa).
* Pool độ sâu 10 từ các hệ thống tham gia; tài liệu ngoài pool coi là không phù hợp. Hệ thống mới ngoài pool bị thiệt (Judged@10 = 1,0 cho các hệ thống đã đóng góp).
* Kiểm tra nhất quán nhãn (`tools/kappa.py`, `judgments/recheck.tsv`): chấm lại mù 100 cặp (mẫu phân tầng 40/30/30 theo nhãn cũ):
  đồng thuận 77%, kappa 3 mức 0,653, kappa có trọng số 0,745, kappa nhị phân (>=1) 0,684, kappa (>=2) 0,813.
  Đây là **đồng thuận nội bộ của cùng một người chấm**, không phải đồng thuận giữa người với người. Phần lệch chủ yếu ở ranh giới
  mức 0/1 ("liên quan một phần") và 1/2; trang trống/tiêu đề chung dễ lệch.
* 40 truy vấn test: khoảng tin cậy rộng, nhiều chênh lệch giữa M2/M3/M4 không có ý nghĩa.
