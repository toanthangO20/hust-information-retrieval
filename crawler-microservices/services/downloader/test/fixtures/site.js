'use strict';
// Website giả lập cho kiểm thử: trang tĩnh, trang sinh nội dung bằng JavaScript, PDF, chuyển hướng, 404, trang chậm...

const http = require('http');

// PDF tối thiểu hợp lệ (1 trang, chữ "Hello PDF")
const PDF = Buffer.from(
  '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
    '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]/Contents 4 0 R>>endobj\n' +
    '4 0 obj<</Length 36>>stream\nBT /F1 12 Tf 20 50 Td (Hello PDF) Tj ET\nendstream endobj\n' +
    'trailer<</Root 1 0 R>>\n%%EOF\n',
  'latin1',
);

function page(title, body, extraHead = '') {
  return `<!DOCTYPE html><html lang="vi"><head><meta charset="utf-8"><title>${title}</title>${extraHead}</head><body>${body}</body></html>`;
}

function createSite() {
  const hits = {};
  const pendingHangs = new Set();
  const server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    hits[u.pathname] = (hits[u.pathname] || 0) + 1;
    const html = (status, s) => {
      res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(s);
    };
    switch (u.pathname) {
      case '/':
        return html(200, page('Trang chủ Bách Khoa',
          `<h1>Đại học Bách khoa Hà Nội</h1>
           <p>Chào mừng tân sinh viên. Xem <a href="/a">thông báo tuyển sinh</a> mới nhất. Học bổng được cập nhật hằng tuần.</p>
           <ul><li><a href="/b">Tin tức</a></li><li><a href="/doc.pdf">Quy chế đào tạo (PDF)</a></li>
           <li><a href="http://example.invalid/ngoai-mien">Trang ngoài miền</a></li><li><a href="/a#muc-2">Thông báo mục 2</a></li></ul>`,
          '<meta name="description" content="Cổng thông tin trường">'));
      case '/a':
        return html(200, page('Thông báo tuyển sinh', '<p>Nội dung thông báo tuyển sinh năm 2026.</p><a href="/">Về trang chủ</a> <a href="/b">Tin tức</a>'));
      case '/b':
        return html(200, page('Tin tức', '<p>Tin tức sự kiện.</p><a href="/">Trang chủ</a> <a href="/notes.txt">Ghi chú</a>'));
      case '/js':
        // Nội dung và liên kết chỉ xuất hiện sau khi JavaScript chạy -> chỉ trình duyệt headless mới thấy
        return html(200, page('Trang động', '<div id="app">Đang tải…</div>' +
          '<script>setTimeout(function(){document.getElementById("app").innerHTML=' +
          '\'<p>NOI_DUNG_SINH_BOI_JAVASCRIPT</p><a href="/a">Liên kết do JS tạo</a>\';},50);</script>'));
      case '/doc.pdf':
        res.writeHead(200, { 'Content-Type': 'application/pdf', 'Content-Length': PDF.length });
        return res.end(PDF);
      case '/download':
        // Tài liệu không có đuôi trong URL, trình duyệt sẽ chuyển sang tải tệp
        res.writeHead(200, { 'Content-Type': 'application/pdf', 'Content-Disposition': 'attachment; filename="qd.pdf"', 'Content-Length': PDF.length });
        return res.end(PDF);
      case '/notes.txt':
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('Ghi chú: lịch thi học kỳ 1.');
      case '/missing':
        return html(404, page('Không tìm thấy', '<p>404</p>'));
      case '/redirect':
        res.writeHead(302, { Location: '/a' });
        return res.end();
      case '/slow':
        return setTimeout(() => html(200, page('Trang chậm', '<p>chậm</p>')), Number(u.searchParams.get('ms') || 1000));
      case '/hang':
        pendingHangs.add(res);
        return; // không bao giờ trả lời
      case '/big.zip':
        res.writeHead(200, { 'Content-Type': 'application/zip', 'Content-Length': 50 * 1024 * 1024 });
        return res.end();
      default:
        return html(404, page('404', 'không có'));
    }
  });
  return {
    hits,
    PDF,
    server,
    async start() {
      await new Promise((r) => server.listen(0, '127.0.0.1', r));
      this.base = `http://127.0.0.1:${server.address().port}`;
      return this;
    },
    async stop() {
      for (const r of pendingHangs) r.destroy();
      server.closeAllConnections?.();
      await new Promise((r) => server.close(() => r()));
    },
  };
}

/** Mô-đun Đọc nội dung giả: ghi lại mọi gói nhận được. */
function createFakeParser({ status = 200 } = {}) {
  const received = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      try {
        received.push({ path: req.url, method: req.method, headers: req.headers, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) });
      } catch (e) {
        received.push({ path: req.url, error: e.message });
      }
      res.writeHead(fake.status, { 'Content-Type': 'application/json' });
      res.end('{"status":"ok"}');
    });
  });
  const fake = {
    status,
    received,
    server,
    async start() {
      await new Promise((r) => server.listen(0, '127.0.0.1', r));
      this.url = `http://127.0.0.1:${server.address().port}/parse`;
      return this;
    },
    async stop() {
      server.closeAllConnections?.();
      await new Promise((r) => server.close(() => r()));
    },
  };
  return fake;
}

module.exports = { createSite, createFakeParser, PDF };
