# Kết quả đánh giá

- Tập truy vấn: `test`, 40 truy vấn có nhãn (bỏ qua 0 truy vấn không có tài liệu phù hợp).
- Phù hợp nhị phân: mức >= 1. nDCG dùng độ lợi = mức phù hợp, chiết khấu theo slide Ch05 (r1 + Σ ri/log2 i), IDCG tính từ toàn bộ qrels.
- Hệ thống tham chiếu để kiểm định: `bm25x_ctx-full`. Kiểm định hoán vị dấu ghép cặp theo truy vấn (20.000 vòng), hiệu chỉnh Holm; dấu `*` = khác biệt có ý nghĩa ở mức 0,05 sau hiệu chỉnh.

## Bảng tổng hợp

| Hệ thống | nDCG@10 | MAP | MRR@10 | P@10 | R@100 | Judged@10 |
|---|---:|---:|---:|---:|---:|---:|
| `bm25x_ctx-full` | 0.6752 | 0.4876 | 0.8833 | 0.6650 | 0.6875 | 1.0000 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.6344* | 0.4614 | 0.8646 | 0.6275 | 0.6855 | 0.8950 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.5554* | 0.4096 | 0.8133 | 0.5425 | 0.6686 | 0.7675 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.6540 | 0.4808 | 0.8771 | 0.6475 | 0.6856 | 0.9525 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.6258 | 0.4400 | 0.8758 | 0.6175 | 0.6496 | 0.9100 |
| `bm25x_ctx-main` | 0.6736 | 0.4866 | 0.8958 | 0.6650 | 0.6859 | 0.9850 |
| `bm25x_ctx-main_pr-0.2` | 0.6564 | 0.4760 | 0.8646 | 0.6575 | 0.6859 | 0.9250 |

## So sánh với `bm25x_ctx-full` (nDCG@10)

| Hệ thống | Δ trung bình | KTC 95% (bootstrap) | p | p (Holm) | tốt hơn / kém hơn / bằng (số truy vấn) |
|---|---:|---|---:|---:|---|
| `bm25x_ctx-main+anchorc_aw-0.5` | -0.0408 | [-0.0644, -0.0186] | 0.0011 | 0.0057 | 5 / 24 / 11 |
| `bm25x_ctx-main+anchorc_aw-1` | -0.1197 | [-0.1803, -0.0707] | 0.0000 | 0.0003 | 6 / 28 / 6 |
| `bm25x_ctx-main+anchor_aw-0.3` | -0.0211 | [-0.0511, +0.0021] | 0.1214 | 0.2429 | 13 / 13 / 14 |
| `bm25x_ctx-main+anchor_aw-1.5` | -0.0493 | [-0.1060, -0.0059] | 0.0392 | 0.1208 | 10 / 18 / 12 |
| `bm25x_ctx-main` | -0.0016 | [-0.0128, +0.0091] | 0.7759 | 0.7759 | 7 / 8 / 25 |
| `bm25x_ctx-main_pr-0.2` | -0.0188 | [-0.0359, -0.0030] | 0.0302 | 0.1208 | 9 / 20 / 11 |

## nDCG@10 theo loại truy vấn

| Hệ thống | english (2) | keyword (27) | paraphrase (6) | question (5) |
|---|---:|---:|---:|---:|
| `bm25x_ctx-full` | 0.7538 | 0.7073 | 0.4761 | 0.7092 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.6486 | 0.6673 | 0.4411 | 0.6830 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.5982 | 0.5814 | 0.3944 | 0.5914 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.6831 | 0.6814 | 0.4735 | 0.7114 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.6461 | 0.6514 | 0.4456 | 0.6957 |
| `bm25x_ctx-main` | 0.7135 | 0.7105 | 0.4700 | 0.7026 |
| `bm25x_ctx-main_pr-0.2` | 0.6975 | 0.6916 | 0.4605 | 0.6845 |

## Độ ổn định của pool (bỏ từng hệ thống khỏi pool)

Pool = hợp top-15 của mọi hệ thống. Với mỗi hệ thống S, các tài liệu *chỉ* do S đóng góp vào pool bị coi như chưa gán nhãn; so sánh thứ tự các hệ thống theo nDCG@10 (tau của Kendall so với thứ tự gốc).

| Bỏ hệ thống | nDCG@10 của chính nó (gốc → sau khi bỏ) | tau |
|---|---|---:|
| `bm25x_ctx-full` | 0.6752 → 0.7617 | 1.000 |
| `bm25x_ctx-main+anchorc_aw-0.5` | 0.6344 → 0.7165 | 1.000 |
| `bm25x_ctx-main+anchorc_aw-1` | 0.5554 → 0.6433 | 1.000 |
| `bm25x_ctx-main+anchor_aw-0.3` | 0.6540 → 0.7359 | 1.000 |
| `bm25x_ctx-main+anchor_aw-1.5` | 0.6258 → 0.7015 | 1.000 |
| `bm25x_ctx-main` | 0.6736 → 0.7582 | 1.000 |
| `bm25x_ctx-main_pr-0.2` | 0.6564 → 0.7395 | 1.000 |
