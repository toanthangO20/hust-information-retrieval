'use strict';
// Kiểm thử dịch vụ N5 qua HTTP thật: website giả lập + mô-đun Đọc nội dung giả.
// Chrome: dùng CHROME_PATH nếu đặt, không thì Chrome do Puppeteer tải về khi npm install.
// Nếu máy không chạy được Chrome, các ca cần Chrome được đánh dấu "skip" (không tính là đạt).

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const { createSite, createFakeParser } = require('./fixtures/site');
const { createDownloader } = require('../src/server');

const CHROME_PATH = process.env.CHROME_PATH || '';
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');

let site;
let chromeOk = false;

async function startN5(parser, o = {}) {
  return createDownloader({ port: 0, host: '127.0.0.1', parserUrl: parser.url, chromePath: CHROME_PATH, render: 'chrome', quiet: true, navTimeoutMs: 15000, forwardBackoffMs: 50, ...o }).start();
}

async function load(d, url, { wait = true } = {}) {
  const res = await fetch(`http://127.0.0.1:${d.cfg.port}/load${wait ? '?wait=1' : ''}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  });
  return { status: res.status, body: await res.json() };
}

const ping = async (d) => (await fetch(`http://127.0.0.1:${d.cfg.port}/ping`)).json();
const stats = async (d) => (await fetch(`http://127.0.0.1:${d.cfg.port}/stats`)).json();

test.before(async () => {
  site = await createSite().start();
  try {
    const puppeteer = require('puppeteer');
    const b = await puppeteer.launch({ headless: true, executablePath: CHROME_PATH || undefined, args: ['--no-sandbox'] });
    await b.close();
    chromeOk = true;
  } catch (e) {
    console.log('# Không chạy được Chrome, bỏ qua các ca cần Chrome:', e.message.split('\n')[0]);
  }
});
test.after(() => site.stop());

test('S01 GET /ping khi rảnh trả {"status":"đang chờ"}', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p);
  try {
    const r = await ping(d);
    assert.equal(r.status, 'đang chờ');
    assert.equal(r.active, 0);
    assert.equal(r.queued, 0);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S02 POST /load trang HTML -> gửi sang /parse đúng đặc tả', async (t) => {
  if (!chromeOk) return t.skip('cần Chrome');
  const p = await createFakeParser().start();
  const d = await startN5(p);
  try {
    const r = await load(d, site.base + '/');
    assert.equal(r.status, 200);
    assert.equal(r.body.result, 'đã gửi sang đọc nội dung');
    assert.equal(p.received.length, 1);
    const got = p.received[0];
    assert.equal(got.path, '/parse');
    assert.equal(got.method, 'POST');
    assert.match(got.headers['content-type'], /application\/json/);
    const b = got.body;
    assert.equal(b.url, site.base + '/');
    assert.equal(b.format, 'html');
    assert.match(b.content_type, /text\/html/);
    assert.equal(b.encoding, 'text');
    assert.match(b.content, /<title>Trang chủ Bách Khoa<\/title>/);
    assert.match(b.content, /Đại học Bách khoa Hà Nội/); // tiếng Việt giữ nguyên
    assert.equal(b.render, 'chrome');
    assert.equal(b.http_status, 200);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S03 trang sinh nội dung bằng JavaScript: Chrome lấy được, HTTP thuần thì không', async (t) => {
  if (!chromeOk) return t.skip('cần Chrome');
  const p = await createFakeParser().start();
  const d = await startN5(p);
  const p2 = await createFakeParser().start();
  const d2 = await startN5(p2, { render: 'http' });
  try {
    await load(d, site.base + '/js');
    await load(d2, site.base + '/js');
    // Chrome: DOM sau khi JS chạy đã chứa nội dung mới trong #app
    assert.match(p.received[0].body.content, /<div id="app"><p>NOI_DUNG_SINH_BOI_JAVASCRIPT<\/p><a href="\/a">Liên kết do JS tạo<\/a><\/div>/);
    // HTTP thuần: chỉ có HTML gốc, #app vẫn là "Đang tải…"
    assert.match(p2.received[0].body.content, /<div id="app">Đang tải…<\/div>/);
  } finally {
    await d.stop();
    await p.stop();
    await d2.stop();
    await p2.stop();
  }
});

test('S04 tài liệu PDF: tải trực tiếp, base64, toàn vẹn từng byte (SHA-256)', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p);
  try {
    await load(d, site.base + '/doc.pdf');
    const b = p.received[0].body;
    assert.equal(b.format, 'pdf');
    assert.equal(b.content_type, 'application/pdf');
    assert.equal(b.encoding, 'base64');
    assert.equal(b.render, 'http');
    assert.equal(sha(Buffer.from(b.content, 'base64')), sha(site.PDF));
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S05 tài liệu không có đuôi trong URL (Content-Disposition: attachment) vẫn nhận ra là PDF', async (t) => {
  if (!chromeOk) return t.skip('cần Chrome');
  const p = await createFakeParser().start();
  const d = await startN5(p);
  try {
    await load(d, site.base + '/download?id=1');
    const b = p.received[0].body;
    assert.equal(b.format, 'pdf');
    assert.equal(b.encoding, 'base64');
    assert.equal(sha(Buffer.from(b.content, 'base64')), sha(site.PDF));
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S06 chuyển hướng 302: url giữ nguyên địa chỉ yêu cầu, final_url là đích', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { render: chromeOk ? 'chrome' : 'http' });
  try {
    await load(d, site.base + '/redirect');
    const b = p.received[0].body;
    assert.equal(b.url, site.base + '/redirect');
    assert.equal(b.final_url, site.base + '/a');
    assert.match(b.content, /Thông báo tuyển sinh/);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S07 trang 404: không gửi sang Đọc nội dung, ghi vào /stats', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { render: chromeOk ? 'chrome' : 'http' });
  try {
    const r = await load(d, site.base + '/missing');
    assert.equal(r.body.http_status, 404);
    assert.equal(p.received.length, 0);
    const s = await stats(d);
    assert.equal(s.stats.skipped_http_error, 1);
    assert.equal(s.history[0].url, site.base + '/missing');
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S08 yêu cầu sai: thiếu url, JSON hỏng, giao thức không hỗ trợ, đường dẫn lạ', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p);
  const base = `http://127.0.0.1:${d.cfg.port}`;
  try {
    let r = await fetch(base + '/load', { method: 'POST', body: '{}' });
    assert.equal(r.status, 400);
    r = await fetch(base + '/load', { method: 'POST', body: '{url:' });
    assert.equal(r.status, 400);
    assert.equal((await r.json()).error, 'JSON không hợp lệ');
    r = await fetch(base + '/load', { method: 'POST', body: JSON.stringify({ url: 'ftp://hust.edu.vn/a' }) });
    assert.equal(r.status, 400);
    r = await fetch(base + '/khong-co');
    assert.equal(r.status, 404);
    assert.equal(p.received.length, 0);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S09 bận: /ping "đang bận" khi hết chỗ, hàng đợi đầy thì /load trả 503, xong thì về "đang chờ"', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { render: 'http', concurrency: 1, queueMax: 1 });
  try {
    const a = await load(d, site.base + '/slow?ms=800', { wait: false });
    assert.equal(a.status, 202);
    assert.equal(a.body.status, 'đã nhận');
    assert.equal((await ping(d)).status, 'đang bận');
    const b = await load(d, site.base + '/slow?ms=801', { wait: false }); // vào hàng đợi (queueMax=1)
    assert.equal(b.status, 202);
    const c = await load(d, site.base + '/slow?ms=802', { wait: false }); // vượt sức chứa
    assert.equal(c.status, 503);
    assert.equal(c.body.status, 'đang bận');
    await d.idle(10000);
    assert.equal((await ping(d)).status, 'đang chờ');
    assert.equal(p.received.length, 2);
    assert.equal((await stats(d)).stats.rejected_busy, 1);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S10 xử lý song song: CONCURRENCY=3 tải 3 trang chậm cùng lúc', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { render: 'http', concurrency: 3 });
  try {
    const t0 = Date.now();
    await Promise.all([1, 2, 3].map((i) => load(d, `${site.base}/slow?ms=600&i=${i}`)));
    const ms = Date.now() - t0;
    assert.equal(p.received.length, 3);
    assert.ok(ms < 1500, `mất ${ms}ms, mong đợi < 1500ms nếu chạy song song`);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S11 Đọc nội dung không phản hồi: thử lại rồi ghi lỗi, dịch vụ vẫn chạy', async () => {
  const d = await createDownloader({ port: 0, host: '127.0.0.1', parserUrl: 'http://127.0.0.1:9/parse', render: 'http', quiet: true, forwardRetries: 2, forwardBackoffMs: 20 }).start();
  try {
    const r = await load(d, site.base + '/a');
    assert.equal(r.body.result, 'lỗi gửi sang đọc nội dung');
    assert.equal(r.body.forward.attempts, 3);
    assert.equal((await ping(d)).status, 'đang chờ');
    assert.equal((await stats(d)).stats.failed_forward, 1);
  } finally {
    await d.stop();
  }
});

test('S12 Đọc nội dung lỗi 500 lần đầu: thử lại và gửi được', async () => {
  const p = await createFakeParser({ status: 500 }).start();
  const d = await startN5(p, { render: 'http', forwardBackoffMs: 100 });
  try {
    setTimeout(() => (p.status = 200), 50);
    const r = await load(d, site.base + '/a');
    assert.equal(r.body.result, 'đã gửi sang đọc nội dung');
    assert.ok(r.body.forward.attempts >= 2);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S13 máy chủ treo: dừng sau NAV_TIMEOUT_MS, báo lỗi timeout', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { render: 'http', navTimeoutMs: 700 });
  try {
    const t0 = Date.now();
    const r = await load(d, site.base + '/hang');
    assert.equal(r.body.result, 'lỗi tải');
    assert.equal(r.body.kind, 'timeout');
    assert.ok(Date.now() - t0 < 3000);
    assert.equal(p.received.length, 0);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S14 tệp vượt MAX_BYTES bị từ chối, không gửi đi', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { maxBytes: 1024 * 1024 });
  try {
    const r = await load(d, site.base + '/big.zip');
    assert.equal(r.body.result, 'lỗi tải');
    assert.equal(r.body.kind, 'too-large');
    assert.equal(p.received.length, 0);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S15 lỗi mạng (host không tồn tại) báo lỗi network', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { render: 'http' });
  try {
    const r = await load(d, 'http://127.0.0.1:9/khong-co');
    assert.equal(r.body.result, 'lỗi tải');
    assert.equal(r.body.kind, 'network');
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S16 chế độ auto: Chrome không khởi động được thì tự lùi về HTTP', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { render: 'auto', chromePath: '/khong/co/chrome' });
  try {
    const r = await load(d, site.base + '/a');
    assert.equal(r.body.result, 'đã gửi sang đọc nội dung');
    assert.equal(p.received[0].body.render, 'http');
    const s = await stats(d);
    assert.equal(s.render, 'http');
    assert.ok(s.chrome_error);
  } finally {
    await d.stop();
    await p.stop();
  }
});

test('S17 cùng URL gửi hai lần khi còn trong hàng đợi chỉ tải một lần', async () => {
  const p = await createFakeParser().start();
  const d = await startN5(p, { render: 'http', concurrency: 1 });
  try {
    await load(d, site.base + '/slow?ms=400', { wait: false });
    const a = await load(d, site.base + '/b', { wait: false });
    const b = await load(d, site.base + '/b', { wait: false });
    assert.equal(a.status, 202);
    assert.equal(b.body.note, 'đã có trong hàng đợi');
    await d.idle(10000);
    assert.equal(p.received.filter((r) => r.body.url === site.base + '/b').length, 1);
  } finally {
    await d.stop();
    await p.stop();
  }
});
