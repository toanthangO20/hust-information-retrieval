#!/usr/bin/env node
'use strict';
// Kiểm tra mô-đun Tải về (N5) trên trang thật của hust.edu.vn, không cần các nhóm khác:
// dựng một "Đọc nội dung" giả để nhận gói, gọi /load cho từng URL và in bảng kết quả.
//   node scripts/smoke-hust.js [url1 url2 ...]
// Kết quả chi tiết (5 trường đặc tả + bổ sung, nội dung cắt ngắn) ghi ra data/smoke-hust.json.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { createDownloader } = require('../services/downloader/src/server');

const urls = process.argv.slice(2).length
  ? process.argv.slice(2)
  : ['https://hust.edu.vn/', 'https://hust.edu.vn/vi/news/tin-tuc-su-kien/', 'https://ctt.hust.edu.vn/', 'https://hust.edu.vn/khong-ton-tai-404'];

(async () => {
  const received = [];
  const parser = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      received.push(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"status":"ok"}');
    });
  });
  await new Promise((r) => parser.listen(0, '127.0.0.1', r));
  const d = await createDownloader({ port: 0, host: '127.0.0.1', parserUrl: `http://127.0.0.1:${parser.address().port}/parse`, quiet: true }).start();

  console.log(`N5 tại :${d.cfg.port}, render=${d.cfg.render}\n`);
  const rows = [];
  for (const url of urls) {
    const r = await fetch(`http://127.0.0.1:${d.cfg.port}/load?wait=1`, { method: 'POST', body: JSON.stringify({ url }) }).then((x) => x.json());
    rows.push({ url, http: r.http_status || '', format: r.format || '', render: r.render || '', chars: r.chars || 0, ms: r.ms, result: r.result + (r.error ? ': ' + r.error : '') });
  }
  console.table(rows);
  const out = path.join(__dirname, '..', 'data');
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'smoke-hust.json'), JSON.stringify(received.map((p) => ({ ...p, content: p.content.slice(0, 500) + (p.content.length > 500 ? '…' : '') })), null, 2));
  console.log(`\nĐọc nội dung (giả) nhận ${received.length} gói, chi tiết: data/smoke-hust.json`);
  await d.stop();
  parser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
