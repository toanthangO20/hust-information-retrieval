# Kết quả đánh giá

- Tập truy vấn: `dev`, 20 truy vấn có nhãn (bỏ qua 0 truy vấn không có tài liệu phù hợp).
- Phù hợp nhị phân: mức >= 1. nDCG dùng độ lợi = mức phù hợp, chiết khấu theo slide Ch05 (r1 + Σ ri/log2 i), IDCG tính từ toàn bộ qrels.
- Hệ thống tham chiếu để kiểm định: `bm25`. Kiểm định hoán vị dấu ghép cặp theo truy vấn (20.000 vòng), hiệu chỉnh Holm; dấu `*` = khác biệt có ý nghĩa ở mức 0,05 sau hiệu chỉnh.

## Bảng tổng hợp

| Hệ thống | nDCG@10 | MAP | MRR@10 | P@10 | R@100 | Judged@10 |
|---|---:|---:|---:|---:|---:|---:|
| `bm25` | 0.5381 | 0.3806 | 0.8625 | 0.5100 | 0.5939 | 1.0000 |
| `current` | 0.4826 | 0.3323 | 0.7500 | 0.4500 | 0.5278 | 0.9300 |
| `vsm` | 0.3875 | 0.2951 | 0.6130 | 0.4650 | 0.6541 | 1.0000 |
| `prf_docs-5_terms-20_lambda-0.5` | 0.5455 | 0.3861 | 0.7421 | 0.5150 | 0.5847 | 1.0000 |
| `dense` | 0.6680 | 0.4876 | 0.8833 | 0.6050 | 0.6954 | 1.0000 |
| `hybrid_w-2` | 0.6486 | 0.5306 | 0.8092 | 0.6200 | 0.7697 | 1.0000 |
| `drocchio_m-5_alpha-0.5_wo-0.5` | 0.6125 | 0.5190 | 0.7833 | 0.5900 | 0.7974 | 0.9150 |
| `ce_depth-30_len-320` | 0.7550* | 0.6078 | 0.9250 | 0.6600 | 0.7722 | 0.8600 |

## So sánh với `bm25` (nDCG@10)

| Hệ thống | Δ trung bình | KTC 95% (bootstrap) | p | p (Holm) | tốt hơn / kém hơn / bằng (số truy vấn) |
|---|---:|---|---:|---:|---|
| `current` | -0.0555 | [-0.1993, +0.0739] | 0.4571 | 0.9143 | 9 / 7 / 4 |
| `vsm` | -0.1507 | [-0.3063, +0.0021] | 0.0787 | 0.4725 | 6 / 13 / 1 |
| `prf_docs-5_terms-20_lambda-0.5` | +0.0074 | [-0.0479, +0.0590] | 0.7988 | 0.9143 | 7 / 4 / 9 |
| `dense` | +0.1298 | [-0.0399, +0.3027] | 0.1707 | 0.6830 | 10 / 6 / 4 |
| `hybrid_w-2` | +0.1104 | [-0.0140, +0.2449] | 0.1272 | 0.6360 | 10 / 6 / 4 |
| `drocchio_m-5_alpha-0.5_wo-0.5` | +0.0743 | [-0.0386, +0.1944] | 0.2475 | 0.7426 | 9 / 4 / 7 |
| `ce_depth-30_len-320` | +0.2168 | [+0.1022, +0.3380] | 0.0015 | 0.0108 | 14 / 2 / 4 |

## nDCG@10 theo loại truy vấn

| Hệ thống | english (1) | keyword (10) | known-item (1) | paraphrase (4) | question (4) |
|---|---:|---:|---:|---:|---:|
| `bm25` | 0.5570 | 0.6000 | 0.0000 | 0.4984 | 0.5530 |
| `current` | 0.5250 | 0.6567 | 0.0625 | 0.4111 | 0.2135 |
| `vsm` | 0.2248 | 0.2870 | 0.1085 | 0.5330 | 0.6037 |
| `prf_docs-5_terms-20_lambda-0.5` | 0.7875 | 0.5863 | 0.0000 | 0.5126 | 0.5523 |
| `dense` | 0.3899 | 0.7110 | 0.9193 | 0.4512 | 0.7839 |
| `hybrid_w-2` | 0.6772 | 0.6685 | 0.8033 | 0.4429 | 0.7587 |
| `drocchio_m-5_alpha-0.5_wo-0.5` | 0.7351 | 0.6342 | 0.7536 | 0.4525 | 0.6522 |
| `ce_depth-30_len-320` | 0.7589 | 0.7225 | 0.9305 | 0.6787 | 0.8675 |

## Độ ổn định của pool (bỏ từng hệ thống khỏi pool)

Pool = hợp top-15 của mọi hệ thống. Với mỗi hệ thống S, các tài liệu *chỉ* do S đóng góp vào pool bị coi như chưa gán nhãn; so sánh thứ tự các hệ thống theo nDCG@10 (tau của Kendall so với thứ tự gốc).

| Bỏ hệ thống | nDCG@10 của chính nó (gốc → sau khi bỏ) | tau |
|---|---|---:|
| `bm25` | 0.5381 → 0.5329 | 1.000 |
| `current` | 0.4826 → 0.4111 | 1.000 |
| `vsm` | 0.3875 → 0.2366 | 1.000 |
| `prf_docs-5_terms-20_lambda-0.5` | 0.5455 → 0.5326 | 0.929 |
| `dense` | 0.6680 → 0.6554 | 1.000 |
| `hybrid_w-2` | 0.6486 → 0.6486 | 1.000 |
| `drocchio_m-5_alpha-0.5_wo-0.5` | 0.6125 → 0.6125 | 1.000 |
| `ce_depth-30_len-320` | 0.7550 → 0.7504 | 1.000 |
