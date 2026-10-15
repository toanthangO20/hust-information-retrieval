# Kết quả đánh giá

- Tập truy vấn: `test`, 40 truy vấn có nhãn (bỏ qua 0 truy vấn không có tài liệu phù hợp).
- Phù hợp nhị phân: mức >= 1. nDCG dùng độ lợi = mức phù hợp, chiết khấu theo slide Ch05 (r1 + Σ ri/log2 i), IDCG tính từ toàn bộ qrels.
- Hệ thống tham chiếu để kiểm định: `bm25`. Kiểm định hoán vị dấu ghép cặp theo truy vấn (20.000 vòng), hiệu chỉnh Holm; dấu `*` = khác biệt có ý nghĩa ở mức 0,05 sau hiệu chỉnh.

## Bảng tổng hợp

| Hệ thống | nDCG@10 | MAP | MRR@10 | P@10 | R@100 | Judged@10 |
|---|---:|---:|---:|---:|---:|---:|
| `bm25` | 0.6752 | 0.4876 | 0.8833 | 0.6650 | 0.6875 | 1.0000 |
| `current` | 0.5730 | 0.3461 | 0.7869 | 0.5700 | 0.4484 | 1.0000 |
| `vsm` | 0.4919* | 0.3606 | 0.7831 | 0.5600 | 0.6467 | 1.0000 |
| `prf_docs-5_terms-20_lambda-0.5` | 0.6736 | 0.4894 | 0.8938 | 0.6800 | 0.6678 | 1.0000 |
| `dense` | 0.6308 | 0.4480 | 0.8636 | 0.6700 | 0.6850 | 1.0000 |
| `hybrid_w-2` | 0.6854 | 0.5358 | 0.9604 | 0.6925 | 0.8093 | 1.0000 |
| `ce_depth-30_len-320` | 0.7016 | 0.6004 | 0.9208 | 0.6900 | 0.8764 | 0.8425 |

## So sánh với `bm25` (nDCG@10)

| Hệ thống | Δ trung bình | KTC 95% (bootstrap) | p | p (Holm) | tốt hơn / kém hơn / bằng (số truy vấn) |
|---|---:|---|---:|---:|---|
| `current` | -0.1022 | [-0.2084, -0.0070] | 0.0558 | 0.2792 | 13 / 18 / 9 |
| `vsm` | -0.1833 | [-0.2816, -0.0858] | 0.0007 | 0.0045 | 9 / 29 / 2 |
| `prf_docs-5_terms-20_lambda-0.5` | -0.0016 | [-0.0230, +0.0215] | 0.8987 | 1.0000 | 11 / 16 / 13 |
| `dense` | -0.0444 | [-0.1465, +0.0606] | 0.4119 | 1.0000 | 18 / 21 / 1 |
| `hybrid_w-2` | +0.0102 | [-0.0650, +0.0862] | 0.7964 | 1.0000 | 19 / 18 / 3 |
| `ce_depth-30_len-320` | +0.0265 | [-0.0611, +0.1144] | 0.5629 | 1.0000 | 20 / 17 / 3 |

## nDCG@10 theo loại truy vấn

| Hệ thống | english (2) | keyword (27) | paraphrase (6) | question (5) |
|---|---:|---:|---:|---:|
| `bm25` | 0.7538 | 0.7073 | 0.4761 | 0.7092 |
| `current` | 0.6145 | 0.7314 | 0.2881 | 0.0429 |
| `vsm` | 0.1706 | 0.5320 | 0.4828 | 0.4145 |
| `prf_docs-5_terms-20_lambda-0.5` | 0.7154 | 0.7143 | 0.4360 | 0.7220 |
| `dense` | 0.4568 | 0.6788 | 0.5029 | 0.5944 |
| `hybrid_w-2` | 0.6185 | 0.7124 | 0.5405 | 0.7405 |
| `ce_depth-30_len-320` | 0.5905 | 0.7228 | 0.6660 | 0.6747 |

## Độ ổn định của pool (bỏ từng hệ thống khỏi pool)

Pool = hợp top-15 của mọi hệ thống. Với mỗi hệ thống S, các tài liệu *chỉ* do S đóng góp vào pool bị coi như chưa gán nhãn; so sánh thứ tự các hệ thống theo nDCG@10 (tau của Kendall so với thứ tự gốc).

| Bỏ hệ thống | nDCG@10 của chính nó (gốc → sau khi bỏ) | tau |
|---|---|---:|
| `bm25` | 0.6752 → 0.6756 | 0.905 |
| `current` | 0.5730 → 0.5348 | 1.000 |
| `vsm` | 0.4919 → 0.2812 | 1.000 |
| `prf_docs-5_terms-20_lambda-0.5` | 0.6736 → 0.6668 | 1.000 |
| `dense` | 0.6308 → 0.5956 | 1.000 |
| `hybrid_w-2` | 0.6854 → 0.6767 | 0.810 |
| `ce_depth-30_len-320` | 0.7016 → 0.7072 | 1.000 |
