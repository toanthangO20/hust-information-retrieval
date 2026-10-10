# Đánh giá metadata (49 trang; tập vàng `eval\extract\gold_test.tsv`)

Chính xác = dự đoán đúng (kể cả "không có" khi nhãn vàng không có); Precision = đúng / số trường được điền; Recall = đúng / số trường có nhãn vàng.

| Trường | Chính xác | Precision | Recall | Có nhãn vàng | Có dự đoán |
|---|---|---|---|---|---|
| title | 0.750 (36/48) | 0.750 | 0.783 | 46 | 48 |
| publishedAt | 0.959 (47/49) | 0.818 | 0.900 | 10 | 11 |
| author | 0.959 (47/49) | 0.750 | 0.600 | 5 | 4 |

## Theo nguồn

| Nguồn | Số trang | Đúng |
|---|---|---|
| title:html:title | 3 | 1 |
| publishedAt:json-ld:datePublished | 3 | 3 |
| title:dom:first-heading | 11 | 8 |
| title:dom:h1 | 22 | 19 |
| publishedAt:text:head | 4 | 2 |
| author:text:byline | 4 | 3 |
| title:meta:og:title | 8 | 7 |
| title:dom:first-block | 4 | 1 |
| publishedAt:meta:article:published_time | 4 | 4 |

## Sai

- title | https://ai4life.hust.edu.vn/blog/page/3/ | gold=Latest News | pred=Blog - Page 3 of 3 [html:title]
- title | https://csv.hust.edu.vn/hao-tam/undefined | gold=- | pred=Chưa có dữ liệu [dom:first-heading]
- publishedAt | https://giving.hust.edu.vn/en/cau-chuyen/mai-nha-bach-khoa-va-diem-tua-tiep-suc-cho-chang-sinh-vien-toan-tin-nguyen-xuan-bach | gold=2026-09-21 | pred=2026-09-28 [text:head]
- title | https://icef.hust.edu.vn/category/show?id=26 | gold=PROGRAM | pred=International Conference on Educational Science and Foreign Language Teaching (ICEF) [dom:first-heading]
- title | https://lms-sepd.hust.edu.vn/ | gold=LMS - School of Engineering Pedagogy | pred=GIỚI THIỆU CHUNG [dom:h1]
- title | http://mso.soict.hust.edu.vn/?limit=9&page_id=17 | gold=Publications | pred=Hoai, Nguyen Van; Cong, Luu Thanh; Dung, Pham Quang [dom:first-block]
- publishedAt | https://research.hust.edu.vn/lan.lethi1 | gold=- | pred=2024-05-28 [text:head]
- title | http://sedic.soict.hust.edu.vn/ | gold=Home | pred=Software Engineering and Distributed Computing Lab [dom:h1]
- title | https://ts-hn.hust.edu.vn/ | gold=CỔNG THÔNG TIN TUYỂN SINH - HƯỚNG NGHIỆP ĐẠI HỌC BÁCH KHOA HÀ NỘI | pred=CỔNG THÔNG TIN TUYỂN SINH [html:title]
- title | https://act.hust.edu.vn/vi/phong-lab-nghien-cuu/phong-lab-he-thong-va-thiet-bi-thong-minh/gioi-thieu-chung-1.html | gold=Giới thiệu chung | pred=Gồm 3 nhóm CM: [dom:first-block]
- title | https://research.hust.edu.vn/sme-welding-processes/nghien-cuu | gold=- | pred=Danh sách đề tài nghiên cứu [dom:first-heading]
- title | http://sedic.soict.hust.edu.vn/contact.html | gold=Contact | pred=Software Engineering and Distributed Computing Lab [dom:h1]
- author | https://icamt2024.smse.hust.edu.vn/conference-info/conference-venue | gold=admin | pred=null
- title | https://icech.hust.edu.vn/article/page/3/ | gold=Articles | pred=Lưu trữ Articles - Trang 3 trên 5 [meta:og:title]
- author | https://library.hust.edu.vn/vi/node/1566 | gold=Nguyễn Thị Thu Huyền | pred=Brian Tracy [text:byline]
- title | http://mso.soict.hust.edu.vn/?limit=2&page_id=17 | gold=Publications | pred=Huynh Thi Thanh Binh Debabrata Samanta, Sayar Ahmad Kuchy [dom:first-block]
