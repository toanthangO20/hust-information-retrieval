# Đánh giá metadata (50 trang; tập vàng `eval\extract\gold_dev.tsv`)

Chính xác = dự đoán đúng (kể cả "không có" khi nhãn vàng không có); Precision = đúng / số trường được điền; Recall = đúng / số trường có nhãn vàng.

| Trường | Chính xác | Precision | Recall | Có nhãn vàng | Có dự đoán |
|---|---|---|---|---|---|
| title | 0.780 (39/50) | 0.780 | 0.813 | 48 | 50 |
| publishedAt | 0.920 (46/50) | 0.933 | 0.824 | 17 | 15 |
| author | 0.960 (48/50) | 1.000 | 0.667 | 6 | 4 |

## Theo nguồn

| Nguồn | Số trang | Đúng |
|---|---|---|
| title:dom:first-heading | 8 | 4 |
| title:dom:h1 | 25 | 23 |
| title:meta:og:title | 8 | 6 |
| publishedAt:meta:article:published_time | 5 | 5 |
| author:text:byline | 4 | 4 |
| publishedAt:text:head | 7 | 6 |
| publishedAt:json-ld:datePublished | 3 | 3 |
| title:dom:first-block | 4 | 2 |
| title:html:title | 5 | 4 |

## Sai

- title | https://act.hust.edu.vn/vi/organs/vieworg/Vien-cong-nghe-dieu-khien-va-tu-dong-hoa-1/ | gold=Danh sách cán bộ | pred=PGS.TS. Nguyễn Quang Địch [dom:first-heading]
- author | https://icamt2024.smse.hust.edu.vn/hello-world.html | gold=admin | pred=null
- author | http://nct.soict.hust.edu.vn/?p=1 | gold=nctadmin | pred=null
- publishedAt | https://sdh.hust.edu.vn/default.aspx?CategoryID=117&nid=3189&scid=23 | gold=2023-12-07 | pred=null
- publishedAt | https://seee.hust.edu.vn/vi/tin-tuc/2026-04-19_hoi-nghi-sinh-vien-nckh-lan-thu-43-truong-dien-dien-tu/ | gold=2026-04-19 | pred=null
- title | https://svbk.hust.edu.vn/vi/news/author/dtn/page-25/ | gold=Đoàn Thanh Niên - Trang 25 | pred=Đoàn Thanh Niên [meta:og:title]
- title | https://fintech.hust.edu.vn/research/r&d-funding-projects/cyber-clinic | gold=- | pred=Học viên [dom:first-heading]
- publishedAt | https://giving.hust.edu.vn/en/cau-chuyen/am-ap-ngay-tro-ve-nha-quy-chap-canh-trao-55-suat-hoc-bong-tai-dai-hoc-bach-khoa-ha-noi | gold=2026-09-20 | pred=null
- title | https://icef.hust.edu.vn/category/show?id=6 | gold=Keynote Speakers | pred=Prof. Thomas Kohler [dom:first-heading]
- title | http://mso.soict.hust.edu.vn/?limit=6&page_id=17 | gold=Publications | pred=Son, Dao Duy; Sang, Dinh Viet; Binh, Huynh Thi Thanh; Thuy, Nguyen Thi [dom:first-block]
- title | https://qldt.hust.edu.vn/students | gold=Cổng thông tin sinh viên | pred=Săn học bổng hàng đầu cùng HUST [dom:first-heading]
- title | https://sme.hust.edu.vn/en/research/ | gold=Research | pred=Trường Cơ khí [dom:h1]
- title | https://soict.hust.edu.vn/en/education/undergraduate/elitech-programs/global-ict | gold=Global ICT Program (IT-E7) | pred=Global ICT - SOICT [meta:og:title]
- title | https://bkcs.hust.edu.vn/ | gold=Bach Khoa Cybersecurity Center | pred=Join us now [dom:h1]
- publishedAt | https://giving.hust.edu.vn/vi/cau-chuyen | gold=- | pred=2026-09-20 [text:head]
- title | https://icea.hust.edu.vn/lien-he4 | gold=- | pred=Phòng 304, Nhà công nghệ cao, Trường ĐH Bách khoa Hà Nội [dom:first-block]
- title | https://icef.hust.edu.vn/category/show?id=18 | gold=Tourism Information | pred=ICEF [html:title]
