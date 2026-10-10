# Đánh giá thuật toán tìm khối nội dung

Tập vàng: `eval\extract\gold_dev.tsv` (50 trang).
Đơn vị tính: số từ. Macro = trung bình theo trang; Micro = gộp mọi trang.

| Cấu hình | P (macro) | R (macro) | F1 (macro) | P (micro) | R (micro) | F1 (micro) |
|---|---|---|---|---|---|---|
| A:baseline(jsoup link-density) | 0.676 | 0.868 | 0.704 | 0.762 | 0.809 | 0.785 |
| B1:bte | 0.681 | 0.717 | 0.649 | 0.717 | 0.906 | 0.801 |
| B2:bte-link | 0.733 | 0.587 | 0.633 | 0.882 | 0.754 | 0.813 |
| C1:rules | 0.582 | 0.564 | 0.545 | 0.799 | 0.772 | 0.785 |
| C2:rules-largest | 0.639 | 0.669 | 0.624 | 0.845 | 0.806 | 0.825 |
| E:css(host rules; else rules-largest) | 0.623 | 0.696 | 0.608 | 0.823 | 0.820 | 0.821 |
| D:B1:bte+template | 0.739 | 0.686 | 0.667 | 0.799 | 0.852 | 0.825 |
| D:B2:bte-link+template | 0.739 | 0.580 | 0.632 | 0.890 | 0.733 | 0.804 |
| D:C1:rules+template | 0.743 | 0.554 | 0.598 | 0.910 | 0.750 | 0.822 |
| D:C2:rules-largest+template | 0.745 | 0.659 | 0.680 | 0.905 | 0.786 | 0.841 |
| D:E:css+template | 0.711 | 0.683 | 0.655 | 0.884 | 0.793 | 0.836 |
| L:learned (logistic regression) | 0.903 | 0.929 | 0.888 | 0.953 | 0.959 | 0.956 |
| D:L:learned+template | 0.960 | 0.890 | 0.904 | 0.969 | 0.909 | 0.938 |
