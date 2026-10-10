# Kết quả đánh giá

- Tập truy vấn: `dev`, 20 truy vấn có nhãn (bỏ qua 0 truy vấn không có tài liệu phù hợp).
- Phù hợp nhị phân: mức >= 1. nDCG dùng độ lợi = mức phù hợp, chiết khấu theo slide Ch05 (r1 + Σ ri/log2 i), IDCG tính từ toàn bộ qrels.
- Hệ thống tham chiếu để kiểm định: `bm25x_ctx-full`. Kiểm định hoán vị dấu ghép cặp theo truy vấn (20.000 vòng), hiệu chỉnh Holm; dấu `*` = khác biệt có ý nghĩa ở mức 0,05 sau hiệu chỉnh.

## Bảng tổng hợp

| Hệ thống | nDCG@10 | MAP | MRR@10 | P@10 | R@100 | Judged@10 |
|---|---:|---:|---:|---:|---:|---:|
| `bm25x_ctx-full` | 0.5381 | 0.3806 | 0.8625 | 0.5100 | 0.5939 | 1.0000 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.5225 | 0.3673 | 0.7738 | 0.4850 | 0.6007 | 0.9350 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.4746* | 0.3361 | 0.7113 | 0.4500 | 0.5853 | 0.8200 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.5409 | 0.3774 | 0.8167 | 0.5100 | 0.6410 | 0.9850 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.4554* | 0.3321 | 0.7583 | 0.4250 | 0.6034 | 0.8800 |
| `bm25x_ctx-main` | 0.5473 | 0.3837 | 0.8500 | 0.5100 | 0.6448 | 1.0000 |
| `bm25x_ctx-main_pr-0.2` | 0.5423 | 0.3755 | 0.8167 | 0.4950 | 0.6448 | 0.9650 |

## So sánh với `bm25x_ctx-full` (nDCG@10)

| Hệ thống | Δ trung bình | KTC 95% (bootstrap) | p | p (Holm) | tốt hơn / kém hơn / bằng (số truy vấn) |
|---|---:|---|---:|---:|---|
| `bm25x_ctx-main+anchorc_aw-0.5` | -0.0157 | [-0.0514, +0.0153] | 0.3928 | 1.0000 | 5 / 6 / 9 |
| `bm25x_ctx-main+anchorc_aw-1` | -0.0636 | [-0.1043, -0.0247] | 0.0069 | 0.0347 | 2 / 11 / 7 |
| `bm25x_ctx-main+anchor_aw-0.3` | +0.0028 | [-0.0150, +0.0198] | 0.8759 | 1.0000 | 3 / 1 / 16 |
| `bm25x_ctx-main+anchor_aw-1.5` | -0.0827 | [-0.1934, -0.0130] | 0.0045 | 0.0273 | 0 / 9 / 11 |
| `bm25x_ctx-main` | +0.0092 | [+0.0002, +0.0223] | 0.1202 | 0.4810 | 4 / 0 / 16 |
| `bm25x_ctx-main_pr-0.2` | +0.0042 | [-0.0177, +0.0267] | 0.7263 | 1.0000 | 7 / 3 / 10 |

## nDCG@10 theo loại truy vấn

| Hệ thống | english (1) | keyword (10) | known-item (1) | paraphrase (4) | question (4) |
|---|---:|---:|---:|---:|---:|
| `bm25x_ctx-full` | 0.5570 | 0.6000 | 0.0000 | 0.4984 | 0.5530 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.6236 | 0.6001 | 0.0000 | 0.4493 | 0.5069 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.5139 | 0.5297 | 0.0000 | 0.4275 | 0.4926 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.6653 | 0.5948 | 0.0000 | 0.4984 | 0.5530 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.4643 | 0.4551 | 0.0000 | 0.4856 | 0.5378 |
| `bm25x_ctx-main` | 0.6653 | 0.6067 | 0.0000 | 0.4984 | 0.5550 |
| `bm25x_ctx-main_pr-0.2` | 0.6687 | 0.6166 | 0.0000 | 0.4773 | 0.5255 |

## Độ ổn định của pool (bỏ từng hệ thống khỏi pool)

Pool = hợp top-15 của mọi hệ thống. Với mỗi hệ thống S, các tài liệu *chỉ* do S đóng góp vào pool bị coi như chưa gán nhãn; so sánh thứ tự các hệ thống theo nDCG@10 (tau của Kendall so với thứ tự gốc).

| Bỏ hệ thống | nDCG@10 của chính nó (gốc → sau khi bỏ) | tau |
|---|---|---:|
| `bm25x_ctx-full` | 0.5381 → 0.7102 | 1.000 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.5225 → 0.6728 | 1.000 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.4746 → 0.6219 | 1.000 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.5409 → 0.6960 | 1.000 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.4554 → 0.5979 | 1.000 |
| `bm25x_ctx-main` | 0.5473 → 0.7063 | 1.000 |
| `bm25x_ctx-main_pr-0.2` | 0.5423 → 0.7032 | 1.000 |
