# Lược đồ dữ liệu bóc tách (MongoDB `hust_ir`)

Sinh bởi `scripts\extract.bat run` → `graph` → `load`. Cùng nội dung dạng JSONL nằm ở `data/extract/<ver>/`
(`pages.final.jsonl`, `links.final.jsonl`, `graph_stats.json`) nên vẫn dùng được khi không có MongoDB.

## Collection `pages` — một nút của đồ thị (trang, tài liệu, ảnh, URL ngoài)

`_id` = SHA-1 (hex) của `url` chuẩn. Trang thuộc bộ dữ liệu có `inDataset: true`; các nút còn lại là *nút phụ* (stub)
chỉ có thông tin suy ra từ liên kết trỏ tới nó.

| Trường | Kiểu | Ý nghĩa |
|---|---|---|
| `url`, `host`, `finalUrl` | string | URL chuẩn của bộ dữ liệu, host, URL sau chuyển hướng |
| `kind` | `html` \| `document` \| `image` \| `external` | loại nút |
| `docType` | string | `html`, `pdf`, `word`, `excel`, `powerpoint`, `text`, `image`, `other` |
| `inDataset` | bool | true: nằm trong `urls_valid.txt` và đã được bóc nội dung |
| `httpStatus`, `contentType`, `fetchedAt`, `lastModified`, `contentHash` | | thông tin lúc chụp HTML thô |
| `title` | `{value, source, conf}` | tiêu đề; `source` ∈ `dom:h1`, `meta:og:title`, `html:title`, `dom:first-heading`, `dom:first-block`, `tika:title` |
| `publishedAt` | `{value:"yyyy-MM-dd", source, conf}` | ngày đăng; nguồn: `json-ld:datePublished`, `meta:*`, `dom:time`, `dom:class-date`, `text:head`, `tika:created` (không dùng `Last-Modified` của server) |
| `publishedDate` | date | `publishedAt.value` ở dạng BSON date (chỉ có trong MongoDB, để truy vấn theo khoảng) |
| `author` | `{value, source, conf}` | tác giả (đã bỏ tên đơn vị/site): `json-ld:author`, `meta:author`, `dom:author`, `text:byline`, `tika:creator` |
| `sourceRef` | `{value, source, conf}` | "Nguồn: ..." ở cuối bài (nếu có) |
| `canonical`, `lang`, `pages` | | `<link rel=canonical>`, thuộc tính `lang`, số trang (tài liệu) |
| `content.text` | string | văn bản khối nội dung chính (HTML) hoặc toàn văn (tài liệu), các khối ngăn bằng dòng trống |
| `content.words`, `content.pageWords` | int | số từ nội dung / toàn trang |
| `content.algo` | string | `learned` (+`tpl` = đã loại khuôn mẫu theo host; mặc định), hoặc `css`, `rules-largest`, `bte`…, `tika` cho tài liệu |
| `content.blocks`, `content.contentBlocks`, `content.root` | | số khối, số khối được chọn, bộ chọn mô tả phần tử gốc của nội dung |
| `inlinks` | int | số trang (khác nhau) trỏ tới nút này |
| `inlinksContent` | int | trong đó, số trang có liên kết nằm *trong khối nội dung* |
| `inlinkHosts` | int | số host khác nhau trỏ tới |
| `outlinksResolved` | int | số cạnh đi ra (sau gộp) |
| `anchors` | `[{text, count}]` | văn bản liên kết tới nút (đã bỏ neo vô nghĩa như "Xem thêm"), top 15 theo số trang |
| `altTexts`, `captions`, `linkTitles` | `[{text, count}]` | với ảnh/tài liệu: `alt`, chú thích (`figcaption`), thuộc tính `title` của thẻ |
| `pagerank`, `pagerankContent` | double | PageRank trên mọi liên kết `<a>` / chỉ liên kết trong nội dung (d = 0,85, 40 vòng) |

Chỉ mục: `url` (duy nhất), `(host, kind)`, `pagerankContent`↓, `publishedDate`↓, text trên `title.value`.

## Collection `links` — một cạnh nguồn → đích : văn bản mô tả

| Trường | Ý nghĩa |
|---|---|
| `src`, `srcUrl`, `srcHost` | trang chứa liên kết (luôn là trang trong bộ dữ liệu) |
| `dst`, `dstUrl`, `dstHost` | đích đã quy về URL chuẩn (theo chuyển hướng / trùng lặp / canonical đã biết) |
| `kind` | `a` (liên kết), `img` (ảnh nhúng), `iframe`, `embed` |
| `anchor` | văn bản liên kết (với ảnh: `alt`/`title`); rút gọn tối đa 200 ký tự |
| `alt`, `title`, `caption` | thông tin bổ sung của ảnh/liên kết (nếu có) |
| `inContent` | liên kết nằm trong khối nội dung chính (không phải menu/chân trang/khuôn mẫu) |
| `count` | số lần xuất hiện cùng (src, dst, kind, anchor) trong trang |

Chỉ mục: `(dst, inContent)`, `src`, `(srcHost, dstHost)`.

## Truy vấn mẫu

```js
// Nguồn giới thiệu và văn bản liên kết của một tệp ảnh / tài liệu
db.links.find({ dstUrl: "https://.../anh.jpg" }, { srcUrl: 1, anchor: 1, alt: 1, caption: 1, inContent: 1 })
// 20 trang được nhiều trang nội dung trỏ tới nhất
db.pages.find({ inDataset: true }).sort({ pagerankContent: -1 }).limit(20)
// bài đăng 2023 của host soict
db.pages.find({ host: "soict.hust.edu.vn", publishedDate: { $gte: ISODate("2023-01-01"), $lt: ISODate("2024-01-01") } })
```

## Ghi chú quan trọng

- Ngày đăng/tác giả có thể vắng (không có trường) — không đoán. `conf` cho biết độ tin cậy của nguồn (0,3 – 0,95).
- Trường dùng được cho trang HTML lẫn tài liệu; với tài liệu, `title`/`author`/`publishedAt` lấy từ metadata tệp (Tika).
- Ảnh không được tải về; nút ảnh chỉ có URL và ngữ cảnh giới thiệu (`anchors`, `altTexts`, `captions`).
- Liên kết ra ngoài miền `hust.edu.vn` được giữ ở dạng nút `external` (không tải).
