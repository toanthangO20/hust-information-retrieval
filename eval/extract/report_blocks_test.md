# Đánh giá thuật toán tìm khối nội dung

Tập vàng: `eval\extract\gold_test.tsv` (49 trang).
Đơn vị tính: số từ. Macro = trung bình theo trang; Micro = gộp mọi trang.

| Cấu hình | P (macro) | R (macro) | F1 (macro) | P (micro) | R (micro) | F1 (micro) |
|---|---|---|---|---|---|---|
| A:baseline(jsoup link-density) | 0.799 | 0.870 | 0.813 | 0.918 | 0.943 | 0.930 |
| B1:bte | 0.665 | 0.696 | 0.662 | 0.874 | 0.918 | 0.895 |
| B2:bte-link | 0.751 | 0.626 | 0.656 | 0.957 | 0.884 | 0.919 |
| C1:rules | 0.655 | 0.654 | 0.633 | 0.901 | 0.877 | 0.889 |
| C2:rules-largest | 0.661 | 0.667 | 0.645 | 0.927 | 0.788 | 0.852 |
| E:css(host rules; else rules-largest) | 0.664 | 0.685 | 0.647 | 0.924 | 0.786 | 0.849 |
| D:B1:bte+template | 0.736 | 0.690 | 0.694 | 0.930 | 0.908 | 0.919 |
| D:B2:bte-link+template | 0.755 | 0.622 | 0.657 | 0.962 | 0.876 | 0.917 |
| D:C1:rules+template | 0.763 | 0.644 | 0.684 | 0.978 | 0.867 | 0.919 |
| D:C2:rules-largest+template | 0.723 | 0.660 | 0.684 | 0.959 | 0.779 | 0.860 |
| D:E:css+template | 0.722 | 0.678 | 0.686 | 0.957 | 0.777 | 0.858 |
| L:learned (logistic regression) | 0.901 | 0.862 | 0.845 | 0.978 | 0.966 | 0.972 |
| D:L:learned+template | 0.950 | 0.841 | 0.870 | 0.993 | 0.951 | 0.971 |
