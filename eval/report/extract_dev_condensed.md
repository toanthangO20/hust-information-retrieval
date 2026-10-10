# Kết quả đánh giá

- Tập truy vấn: `dev`, 20 truy vấn có nhãn (bỏ qua 0 truy vấn không có tài liệu phù hợp).
- Phù hợp nhị phân: mức >= 1. nDCG dùng độ lợi = mức phù hợp, chiết khấu theo slide Ch05 (r1 + Σ ri/log2 i), IDCG tính từ toàn bộ qrels.
- Hệ thống tham chiếu để kiểm định: `bm25x_ctx-full`. Kiểm định hoán vị dấu ghép cặp theo truy vấn (20.000 vòng), hiệu chỉnh Holm; dấu `*` = khác biệt có ý nghĩa ở mức 0,05 sau hiệu chỉnh.

## Bảng tổng hợp

| Hệ thống | nDCG@10 | MAP | MRR@10 | P@10 | R@100 | Judged@10 |
|---|---:|---:|---:|---:|---:|---:|
| `bm25x_ctx-full` | 0.5381 | 0.4234 | 0.8625 | 0.5100 | 0.5939 | 1.0000 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.5388 | 0.4215 | 0.7738 | 0.5100 | 0.6007 | 1.0000 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.5241 | 0.4105 | 0.7613 | 0.5100 | 0.5853 | 1.0000 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.5409 | 0.4227 | 0.8167 | 0.5100 | 0.6410 | 1.0000 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.5181 | 0.4065 | 0.8083 | 0.4950 | 0.6034 | 1.0000 |
| `bm25x_ctx-main` | 0.5473 | 0.4288 | 0.8500 | 0.5100 | 0.6448 | 1.0000 |
| `bm25x_ctx-main_pr-0.2` | 0.5482 | 0.4266 | 0.8167 | 0.5050 | 0.6448 | 1.0000 |

## So sánh với `bm25x_ctx-full` (nDCG@10)

| Hệ thống | Δ trung bình | KTC 95% (bootstrap) | p | p (Holm) | tốt hơn / kém hơn / bằng (số truy vấn) |
|---|---:|---|---:|---:|---|
| `bm25x_ctx-main+anchorc_aw-0.5` | +0.0006 | [-0.0260, +0.0263] | 0.9696 | 1.0000 | 5 / 5 / 10 |
| `bm25x_ctx-main+anchorc_aw-1` | -0.0140 | [-0.0504, +0.0185] | 0.4407 | 1.0000 | 5 / 6 / 9 |
| `bm25x_ctx-main+anchor_aw-0.3` | +0.0028 | [-0.0150, +0.0198] | 0.8759 | 1.0000 | 3 / 1 / 16 |
| `bm25x_ctx-main+anchor_aw-1.5` | -0.0201 | [-0.0579, +0.0077] | 0.3340 | 1.0000 | 2 / 6 / 12 |
| `bm25x_ctx-main` | +0.0092 | [+0.0002, +0.0223] | 0.1202 | 0.7215 | 4 / 0 / 16 |
| `bm25x_ctx-main_pr-0.2` | +0.0100 | [-0.0072, +0.0300] | 0.3172 | 1.0000 | 7 / 2 / 11 |

## nDCG@10 theo loại truy vấn

| Hệ thống | english (1) | keyword (10) | known-item (1) | paraphrase (4) | question (4) |
|---|---:|---:|---:|---:|---:|
| `bm25x_ctx-full` | 0.5570 | 0.6000 | 0.0000 | 0.4984 | 0.5530 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.6264 | 0.6083 | 0.0000 | 0.4786 | 0.5378 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.5825 | 0.5874 | 0.0000 | 0.4535 | 0.5528 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.6653 | 0.5948 | 0.0000 | 0.4984 | 0.5530 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.5216 | 0.5687 | 0.0000 | 0.5004 | 0.5378 |
| `bm25x_ctx-main` | 0.6653 | 0.6067 | 0.0000 | 0.4984 | 0.5550 |
| `bm25x_ctx-main_pr-0.2` | 0.6687 | 0.6169 | 0.0000 | 0.4916 | 0.5398 |

## Độ ổn định của pool (bỏ từng hệ thống khỏi pool)

Pool = hợp top-15 của mọi hệ thống. Với mỗi hệ thống S, các tài liệu *chỉ* do S đóng góp vào pool bị coi như chưa gán nhãn; so sánh thứ tự các hệ thống theo nDCG@10 (tau của Kendall so với thứ tự gốc).

| Bỏ hệ thống | nDCG@10 của chính nó (gốc → sau khi bỏ) | tau |
|---|---|---:|
| `bm25x_ctx-full` | 0.5381 → 0.7102 | 0.810 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.5388 → 0.6895 | 0.810 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.5241 → 0.6790 | 0.810 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.5409 → 0.6960 | 0.810 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.5181 → 0.6700 | 0.905 |
| `bm25x_ctx-main` | 0.5473 → 0.7063 | 0.810 |
| `bm25x_ctx-main_pr-0.2` | 0.5482 → 0.7132 | 0.810 |
