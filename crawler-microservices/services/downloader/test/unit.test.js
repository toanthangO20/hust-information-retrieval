'use strict';
// Kiểm thử đơn vị: phân loại URL, định dạng, giải mã, dựng gói /parse.

const test = require('node:test');
const assert = require('node:assert/strict');
const { classifyUrl, formatOf, isHtmlType, decodeText, buildPayload } = require('../src/fetcher');

test('U01 phân loại URL: tài liệu theo đuôi, còn lại là trang web', () => {
  assert.equal(classifyUrl('https://hust.edu.vn/files/quy-che.pdf'), 'document');
  assert.equal(classifyUrl('https://hust.edu.vn/a/b.DOCX'), 'document');
  assert.equal(classifyUrl('https://hust.edu.vn/data.xlsx?v=2'), 'document');
  assert.equal(classifyUrl('https://hust.edu.vn/vi/news/abc-654601.html'), 'page');
  assert.equal(classifyUrl('https://hust.edu.vn/'), 'page');
  assert.equal(classifyUrl('https://hust.edu.vn/tin-tuc'), 'page');
});

test('U02 định dạng: ưu tiên Content-Type, sau đó đuôi URL', () => {
  assert.equal(formatOf('application/pdf', 'https://x/download?id=1'), 'pdf');
  assert.equal(formatOf('application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'https://x/f'), 'docx');
  assert.equal(formatOf('application/octet-stream', 'https://x/a.pptx'), 'pptx');
  assert.equal(formatOf('', 'https://x/a.htm'), 'html');
  assert.equal(formatOf('application/octet-stream', 'https://x/abc'), 'other');
  assert.ok(isHtmlType('text/html; charset=UTF-8'));
  assert.ok(isHtmlType('application/xhtml+xml'));
  assert.ok(!isHtmlType('application/pdf'));
});

test('U03 giải mã văn bản theo charset (header, thẻ meta, mặc định UTF-8)', () => {
  const vi = 'Đại học Bách khoa Hà Nội';
  assert.equal(decodeText(Buffer.from(vi, 'utf8'), 'text/html; charset=utf-8'), vi);
  assert.equal(decodeText(Buffer.from(vi, 'utf8'), 'text/html'), vi);
  const latin = Buffer.from('<meta charset="iso-8859-1"><p>caf\xe9</p>', 'latin1');
  assert.match(decodeText(latin, 'text/html'), /café/);
});

test('U04 gói /parse có đủ 5 trường đặc tả, HTML dùng encoding text', () => {
  const p = buildPayload({ url: 'https://hust.edu.vn/', contentType: 'text/html; charset=utf-8', body: '<html>x</html>', isHtml: true, httpStatus: 200, render: 'chrome', downloaderId: 'N5' });
  for (const k of ['url', 'format', 'content_type', 'encoding', 'content']) assert.ok(k in p, 'thiếu ' + k);
  assert.equal(p.format, 'html');
  assert.equal(p.encoding, 'text');
  assert.equal(p.content, '<html>x</html>');
  assert.equal(p.final_url, 'https://hust.edu.vn/');
  assert.equal(p.http_status, 200);
});

test('U05 tài liệu nhị phân mã hoá base64, giải mã lại đúng từng byte', () => {
  const bin = Buffer.from([0x25, 0x50, 0x44, 0x46, 0x00, 0xff, 0x10, 0x80]);
  const p = buildPayload({ url: 'https://hust.edu.vn/a.pdf', contentType: 'application/pdf', body: bin, isHtml: false, httpStatus: 200, render: 'http' });
  assert.equal(p.format, 'pdf');
  assert.equal(p.encoding, 'base64');
  assert.deepEqual(Buffer.from(p.content, 'base64'), bin);
});

test('U06 tệp văn bản (text/plain) gửi dạng text', () => {
  const p = buildPayload({ url: 'https://hust.edu.vn/a.txt', contentType: 'text/plain; charset=utf-8', body: Buffer.from('lịch thi'), isHtml: false, httpStatus: 200, render: 'http' });
  assert.equal(p.format, 'txt');
  assert.equal(p.encoding, 'text');
  assert.equal(p.content, 'lịch thi');
});
