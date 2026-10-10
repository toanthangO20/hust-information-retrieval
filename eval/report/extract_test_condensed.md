# Kết quả đánh giá

- Tập truy vấn: `test`, 40 truy vấn có nhãn (bỏ qua 0 truy vấn không có tài liệu phù hợp).
- Phù hợp nhị phân: mức >= 1. nDCG dùng độ lợi = mức phù hợp, chiết khấu theo slide Ch05 (r1 + Σ ri/log2 i), IDCG tính từ toàn bộ qrels.
- Hệ thống tham chiếu để kiểm định: `bm25x_ctx-full`. Kiểm định hoán vị dấu ghép cặp theo truy vấn (20.000 vòng), hiệu chỉnh Holm; dấu `*` = khác biệt có ý nghĩa ở mức 0,05 sau hiệu chỉnh.

## Bảng tổng hợp

| Hệ thống | nDCG@10 | MAP | MRR@10 | P@10 | R@100 | Judged@10 |
|---|---:|---:|---:|---:|---:|---:|
| `bm25x_ctx-full` | 0.6752 | 0.5576 | 0.8833 | 0.6650 | 0.6875 | 1.0000 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.6624 | 0.5539 | 0.8646 | 0.6800 | 0.6855 | 1.0000 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.6394 | 0.5310 | 0.8227 | 0.6850 | 0.6686 | 1.0000 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.6669 | 0.5604 | 0.8771 | 0.6750 | 0.6856 | 1.0000 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.6470 | 0.5302 | 0.8771 | 0.6575 | 0.6496 | 1.0000 |
| `bm25x_ctx-main` | 0.6758 | 0.5589 | 0.8958 | 0.6700 | 0.6859 | 1.0000 |
| `bm25x_ctx-main_pr-0.2` | 0.6718 | 0.5575 | 0.8771 | 0.6825 | 0.6859 | 1.0000 |

## So sánh với `bm25x_ctx-full` (nDCG@10)

| Hệ thống | Δ trung bình | KTC 95% (bootstrap) | p | p (Holm) | tốt hơn / kém hơn / bằng (số truy vấn) |
|---|---:|---|---:|---:|---|
| `bm25x_ctx-main+anchorc_aw-0.5` | -0.0127 | [-0.0308, +0.0046] | 0.1773 | 0.8867 | 10 / 14 / 16 |
| `bm25x_ctx-main+anchorc_aw-1` | -0.0358 | [-0.0745, -0.0021] | 0.0660 | 0.3960 | 12 / 20 / 8 |
| `bm25x_ctx-main+anchor_aw-0.3` | -0.0083 | [-0.0308, +0.0103] | 0.4801 | 1.0000 | 15 / 10 / 15 |
| `bm25x_ctx-main+anchor_aw-1.5` | -0.0282 | [-0.0784, +0.0086] | 0.2397 | 0.9590 | 11 / 16 / 13 |
| `bm25x_ctx-main` | +0.0006 | [-0.0082, +0.0103] | 0.8958 | 1.0000 | 8 / 7 / 25 |
| `bm25x_ctx-main_pr-0.2` | -0.0034 | [-0.0147, +0.0080] | 0.5669 | 1.0000 | 11 / 14 / 15 |

## nDCG@10 theo loại truy vấn

| Hệ thống | english (2) | keyword (27) | paraphrase (6) | question (5) |
|---|---:|---:|---:|---:|
| `bm25x_ctx-full` | 0.7538 | 0.7073 | 0.4761 | 0.7092 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.7082 | 0.7011 | 0.4458 | 0.6956 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.6774 | 0.6771 | 0.4520 | 0.6455 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.6831 | 0.6994 | 0.4735 | 0.7171 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.6470 | 0.6795 | 0.4456 | 0.7131 |
| `bm25x_ctx-main` | 0.7422 | 0.7117 | 0.4700 | 0.7026 |
| `bm25x_ctx-main_pr-0.2` | 0.7268 | 0.7066 | 0.4605 | 0.7152 |

## Độ ổn định của pool (bỏ từng hệ thống khỏi pool)

Pool = hợp top-15 của mọi hệ thống. Với mỗi hệ thống S, các tài liệu *chỉ* do S đóng góp vào pool bị coi như chưa gán nhãn; so sánh thứ tự các hệ thống theo nDCG@10 (tau của Kendall so với thứ tự gốc).

| Bỏ hệ thống | nDCG@10 của chính nó (gốc → sau khi bỏ) | tau |
|---|---|---:|
| `bm25x_ctx-full` | 0.6752 → 0.7617 | 0.905 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.6624 → 0.7476 | 0.905 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.6394 → 0.7277 | 0.905 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.6669 → 0.7507 | 0.905 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.6470 → 0.7428 | 0.905 |
| `bm25x_ctx-main` | 0.6758 → 0.7608 | 0.905 |
| `bm25x_ctx-main_pr-0.2` | 0.6718 → 0.7557 | 0.905 |
