#!/usr/bin/env node
'use strict';
// Trang kiểm tra trực quan mô-đun Tải về (N5) qua mạng LAN, không cần các nhóm khác và không sửa gì N5:
//   - Giao diện web (mặc định :7000): gọi /ping, /load, /stats của N5 theo địa chỉ nhập vào (ví dụ http://192.168.1.10:8800).
//     Máy chủ của trang gọi hộ N5 (N5 không bật CORS nên trình duyệt không gọi thẳng được).
//   - Đọc nội dung giả (mặc định :6600): nhận POST /parse từ N5, hiện từng gói và kiểm tra 5 trường đặc tả.
//   node scripts/demo-n5.js [--n5 http://IP:8800] [--port 7000] [--parser-port 6600]
// N5 phải có PARSER_URL trỏ về máy chạy trang này: http://<IP máy này>:6600/parse (cùng máy thì mặc định đã đúng).

const http = require('http');
const os = require('os');
const crypto = require('crypto');

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, arr) => (a.startsWith('--') ? [...acc, [a.slice(2), arr[i + 1]]] : acc), []),
);
const UI_PORT = Number(args.port || 7000);
const PARSER_PORT = Number(args['parser-port'] || 6600);
const N5_DEFAULT = args.n5 || 'http://127.0.0.1:8800';
const SPEC = ['url', 'format', 'content_type', 'encoding', 'content'];

/** Các địa chỉ IPv4 của máy (để chọn nhanh IP LAN). */
function lanIps() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal) out.push({ name, address: a.address });
  }
  return out;
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error('body quá lớn'));
        req.destroy();
      } else chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function send(res, status, obj, type = 'application/json; charset=utf-8') {
  const body = typeof obj === 'string' ? obj : JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(body), 'Cache-Control': 'no-store' });
  res.end(body);
}

// ---------- Đọc nội dung giả: nhận gói từ N5 ----------

const received = []; // mới nhất ở đầu
let seq = 0;

function summarize(p, from, bytesIn) {
  const missing = SPEC.filter((k) => !(k in p));
  const s = {
    id: ++seq, at: new Date().toISOString(), from, json_bytes: bytesIn, missing,
    url: p.url, format: p.format, content_type: p.content_type, encoding: p.encoding,
    final_url: p.final_url, http_status: p.http_status, render: p.render, downloader: p.downloader,
    chars: typeof p.content === 'string' ? p.content.length : 0,
  };
  if (typeof p.content !== 'string') return s;
  if (p.encoding === 'base64') {
    const buf = Buffer.from(p.content, 'base64');
    s.bytes = buf.length;
    s.sha256 = crypto.createHash('sha256').update(buf).digest('hex');
    s.magic = buf.subarray(0, 8).toString('latin1').replace(/[^\x20-\x7e]/g, '.');
  } else {
    const t = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(p.content);
    s.title = t ? t[1].replace(/\s+/g, ' ').trim() : '';
    const text = p.content.replace(/<(script|style|noscript)[\s\S]*?<\/\1>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
    s.preview = text.slice(0, 400);
    s.links = (p.content.match(/<a\s[^>]*href=/gi) || []).length;
  }
  return s;
}

const parser = http.createServer(async (req, res) => {
  if (req.method !== 'POST' || new URL(req.url, 'http://x').pathname !== '/parse') return send(res, 404, { status: 'lỗi', error: 'chỉ có POST /parse' });
  try {
    const raw = await readBody(req, 128 * 1024 * 1024);
    const p = JSON.parse(raw);
    const s = summarize(p, req.socket.remoteAddress.replace(/^::ffff:/, ''), Buffer.byteLength(raw));
    received.unshift(s);
    if (received.length > 50) received.pop();
    console.log(`[đọc nội dung giả] #${s.id} từ ${s.from}: ${s.url} (${s.format}, ${s.encoding}, ${s.chars} ký tự)`);
    send(res, 200, { status: 'ok' });
  } catch (e) {
    send(res, 400, { status: 'lỗi', error: e.message });
  }
});

// ---------- Giao diện + gọi hộ N5 ----------

function n5Base(raw) {
  try {
    const u = new URL(String(raw || '').trim());
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    return u.origin;
  } catch (_) {
    return null;
  }
}

async function callN5(method, url, body, timeoutMs) {
  const t0 = Date.now();
  try {
    const r = await fetch(url, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await r.text();
    let data;
    try {
      data = JSON.parse(text);
    } catch (_) {
      data = { raw: text.slice(0, 2000) };
    }
    return { ok: true, http: r.status, ms: Date.now() - t0, data };
  } catch (e) {
    return { ok: false, ms: Date.now() - t0, error: e.cause?.code || e.name + ': ' + e.message };
  }
}

const ui = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  try {
    if (req.method === 'GET' && u.pathname === '/') return send(res, 200, PAGE, 'text/html; charset=utf-8');
    if (req.method === 'GET' && u.pathname === '/api/info') {
      return send(res, 200, { n5: N5_DEFAULT, ips: lanIps(), parser_port: PARSER_PORT, hostname: os.hostname() });
    }
    if (req.method === 'GET' && u.pathname === '/api/received') return send(res, 200, received);
    if (req.method === 'POST' && u.pathname === '/api/received/clear') {
      received.length = 0;
      return send(res, 200, { status: 'ok' });
    }
    if (req.method === 'GET' && (u.pathname === '/api/ping' || u.pathname === '/api/stats')) {
      const base = n5Base(u.searchParams.get('n5'));
      if (!base) return send(res, 400, { ok: false, error: 'địa chỉ N5 không hợp lệ' });
      return send(res, 200, { target: base + u.pathname.slice(4), ...(await callN5('GET', base + u.pathname.slice(4), null, 3000)) });
    }
    if (req.method === 'POST' && u.pathname === '/api/load') {
      const b = JSON.parse((await readBody(req, 64 * 1024)) || '{}');
      const base = n5Base(b.n5);
      if (!base) return send(res, 400, { ok: false, error: 'địa chỉ N5 không hợp lệ' });
      const target = base + '/load' + (b.wait ? '?wait=1' : '');
      return send(res, 200, { target, request: { url: b.url }, ...(await callN5('POST', target, { url: b.url }, b.wait ? 120000 : 10000)) });
    }
    send(res, 404, { error: 'không có ' + u.pathname });
  } catch (e) {
    send(res, 500, { ok: false, error: e.message });
  }
});

const PAGE = `<!doctype html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Kiểm tra N5</title>
<style>
:root {
  --bg: #f6f7f9; --card: #fff; --ink: #1d232b; --muted: #5d6875; --line: #e2e6eb; --accent: #1f5fbf;
  --ok: #1b7f3b; --ok-bg: #e5f4ea; --warn: #9a5b00; --warn-bg: #fdf1dc; --err: #b42318; --err-bg: #fde8e6; --code: #f1f3f6;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #12161b; --card: #1a2027; --ink: #e6eaef; --muted: #9aa5b1; --line: #2b333d; --accent: #6ea8ff;
    --ok: #5fd08a; --ok-bg: #173323; --warn: #f0b860; --warn-bg: #3a2c12; --err: #ff8a80; --err-bg: #3d1c1a; --code: #222a33;
  }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink); font: 14px/1.5 system-ui, "Segoe UI", Roboto, sans-serif; }
main { max-width: 1180px; margin: 0 auto; padding: 20px 16px 40px; }
h1 { font-size: 20px; margin: 0 0 2px; }
h2 { font-size: 15px; margin: 0 0 10px; }
.sub { color: var(--muted); margin: 0 0 16px; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
@media (max-width: 860px) { .grid { grid-template-columns: 1fr; } }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px; min-width: 0; }
.full { grid-column: 1 / -1; }
label { display: block; color: var(--muted); font-size: 12px; margin-bottom: 4px; }
input[type=text] { width: 100%; padding: 8px 10px; border: 1px solid var(--line); border-radius: 7px; background: var(--bg); color: var(--ink); font: inherit; }
button { padding: 8px 14px; border: 1px solid var(--accent); background: var(--accent); color: #fff; border-radius: 7px; font: inherit; cursor: pointer; }
button.ghost { background: transparent; color: var(--accent); }
button.chip { padding: 3px 9px; font-size: 12px; border-radius: 999px; background: transparent; color: var(--ink); border-color: var(--line); }
button.chip:hover { border-color: var(--accent); }
button:disabled { opacity: .55; cursor: wait; }
.row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.chips { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.pill { display: inline-flex; align-items: center; gap: 8px; padding: 6px 14px; border-radius: 999px; font-weight: 600; font-size: 15px; }
.pill::before { content: ""; width: 10px; height: 10px; border-radius: 50%; background: currentColor; }
.pill.ok { color: var(--ok); background: var(--ok-bg); }
.pill.warn { color: var(--warn); background: var(--warn-bg); }
.pill.err { color: var(--err); background: var(--err-bg); }
.pill.idle { color: var(--muted); background: var(--code); }
.kv { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 2px 12px; margin-top: 10px; font-size: 13px; }
.kv span { overflow-wrap: anywhere; }
.kv span:nth-child(odd) { color: var(--muted); }
code, pre { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
pre { background: var(--code); border-radius: 7px; padding: 10px; overflow: auto; max-height: 260px; margin: 8px 0 0; white-space: pre-wrap; word-break: break-all; }
.muted { color: var(--muted); }
.note { font-size: 12px; color: var(--muted); margin-top: 8px; }
.pkt { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; margin-top: 10px; }
.pkt .head { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.pkt .url { font-weight: 600; word-break: break-all; }
.fields { display: flex; gap: 6px; flex-wrap: wrap; margin: 6px 0; }
.fields span { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: var(--ok-bg); color: var(--ok); }
.fields span.miss { background: var(--err-bg); color: var(--err); }
.title { font-size: 15px; margin: 4px 0; }
table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
th { color: var(--muted); font-weight: 600; }
td.u { word-break: break-all; }
.counters { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 8px; }
.counters b { font-size: 18px; display: block; }
.counters div { color: var(--muted); font-size: 12px; }
.scroll { overflow-x: auto; }
</style>
</head>
<body>
<main>
  <h1>Kiểm tra mô-đun Tải về (N5)</h1>
  <p class="sub">Gọi N5 qua mạng bằng HTTP + JSON đúng đặc tả. Trang này cũng đóng vai <b>Đọc nội dung giả</b> trên cổng <code id="pp"></code> để thấy gói N5 gửi sang <code>/parse</code>.</p>

  <div class="grid">
    <section class="card">
      <h2>1. Địa chỉ N5</h2>
      <label for="n5">Địa chỉ (nên dùng IP LAN, không dùng localhost)</label>
      <div class="row"><input id="n5" type="text" spellcheck="false"></div>
      <div class="chips" id="ips"></div>
      <div class="row" style="margin-top:12px"><span id="pill" class="pill idle">chưa kiểm tra</span><span id="pingms" class="muted"></span></div>
      <div class="kv" id="pingkv"></div>
      <div class="note">Tự gọi <code>GET /ping</code> mỗi giây. <code id="pingtarget"></code></div>
    </section>

    <section class="card">
      <h2>2. Gửi lệnh <code>POST /load</code></h2>
      <label for="url">URL cần tải</label>
      <input id="url" type="text" spellcheck="false" value="https://ctt.hust.edu.vn/">
      <div class="chips">
        <button class="chip" data-url="https://hust.edu.vn/">Trang chủ</button>
        <button class="chip" data-url="https://hust.edu.vn/vi/news/tin-tuc-su-kien/">Tin tức</button>
        <button class="chip" data-url="https://ctt.hust.edu.vn/">ctt.hust.edu.vn</button>
        <button class="chip" data-url="https://library.hust.edu.vn/sites/default/files/DM%20397%20Ebook%20Sciendirect%20.pdf">PDF</button>
        <button class="chip" data-url="https://ctt.hust.edu.vn/khong-ton-tai-404">Trang 404</button>
        <button class="chip" data-url="ftp://hust.edu.vn/a">URL sai (ftp)</button>
      </div>
      <div class="row" style="margin-top:12px">
        <button id="go">Gửi /load?wait=1</button>
        <button id="go2" class="ghost">Gửi /load (không chờ)</button>
      </div>
      <div id="loadres" class="note"></div>
      <pre id="loadjson" hidden></pre>
    </section>

    <section class="card full">
      <div class="row" style="justify-content:space-between"><h2 style="margin:0">3. Gói Đọc nội dung (giả) nhận được từ N5</h2><button id="clear" class="chip">Xoá danh sách</button></div>
      <div class="note" id="parsernote"></div>
      <div id="pkts"><p class="muted">Chưa nhận gói nào.</p></div>
    </section>

    <section class="card full">
      <h2>4. <code>GET /stats</code> của N5</h2>
      <div class="counters" id="counters"></div>
      <div class="scroll"><table><thead><tr><th>Lúc</th><th>URL</th><th>Kết quả</th><th>HTTP</th><th>format</th><th>render</th><th>ms</th></tr></thead><tbody id="hist"></tbody></table></div>
    </section>
  </div>
</main>
<script>
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const n5 = () => $('n5').value.trim();
let info = { ips: [], parser_port: 6600 };

function store(k, v) { try { localStorage.setItem(k, v); } catch (_) {} }
function load(k) { try { return localStorage.getItem(k); } catch (_) { return null; } }

async function getJson(url, opts) { const r = await fetch(url, opts); return r.json(); }

async function init() {
  info = await getJson('/api/info');
  $('pp').textContent = ':' + info.parser_port;
  $('n5').value = load('n5') || info.n5;
  $('ips').innerHTML = info.ips.map((i) => '<button class="chip" data-ip="' + esc(i.address) + '">' + esc(i.name) + ': ' + esc(i.address) + '</button>').join('')
    + '<button class="chip" data-ip="127.0.0.1">localhost</button>';
  $('parsernote').innerHTML = 'N5 phải đặt <code>PARSER_URL</code> trỏ về máy chạy trang này, ví dụ '
    + info.ips.map((i) => '<code>http://' + esc(i.address) + ':' + info.parser_port + '/parse</code>').join(', ')
    + '. N5 chạy cùng máy thì mặc định <code>http://127.0.0.1:6600/parse</code> đã đúng.';
  tickPing(); tickStats(); tickPkts();
  setInterval(tickPing, 1000); setInterval(tickStats, 2000); setInterval(tickPkts, 1000);
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('button');
  if (!b) return;
  if (b.dataset.ip) {
    const u = new URL(n5() || 'http://127.0.0.1:8800');
    $('n5').value = 'http://' + b.dataset.ip + ':' + (u.port || '8800');
    store('n5', n5()); tickPing(); tickStats();
  }
  if (b.dataset.url) $('url').value = b.dataset.url;
});
$('n5').addEventListener('change', () => { store('n5', n5()); tickPing(); tickStats(); });

async function tickPing() {
  const r = await getJson('/api/ping?n5=' + encodeURIComponent(n5())).catch((e) => ({ ok: false, error: e.message }));
  const pill = $('pill');
  $('pingtarget').textContent = r.target || '';
  if (!r.ok) {
    pill.className = 'pill err'; pill.textContent = 'không liên lạc được';
    $('pingms').textContent = r.error || ''; $('pingkv').innerHTML = '';
    return;
  }
  const d = r.data || {};
  pill.className = 'pill ' + (d.status === 'đang chờ' ? 'ok' : d.status === 'đang bận' ? 'warn' : 'err');
  pill.textContent = d.status || ('HTTP ' + r.http);
  $('pingms').textContent = r.ms + ' ms';
  $('pingkv').innerHTML = [['id', d.id], ['đang tải', d.active], ['hàng đợi', d.queued], ['sức chứa', d.capacity], ['render', d.render]]
    .map(([k, v]) => '<span>' + k + '</span><span>' + esc(v) + '</span>').join('');
}

async function doLoad(wait) {
  const btns = [$('go'), $('go2')];
  btns.forEach((b) => (b.disabled = true));
  $('loadres').textContent = wait ? 'Đang chờ N5 tải xong…' : 'Đang gửi…';
  $('loadjson').hidden = true;
  const r = await getJson('/api/load', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ n5: n5(), url: $('url').value.trim(), wait }) })
    .catch((e) => ({ ok: false, error: e.message }));
  btns.forEach((b) => (b.disabled = false));
  if (!r.ok) { $('loadres').innerHTML = '<b style="color:var(--err)">Lỗi:</b> ' + esc(r.error); return; }
  $('loadres').innerHTML = '<code>POST ' + esc(r.target) + '</code> → <b>HTTP ' + r.http + '</b> sau ' + r.ms + ' ms';
  $('loadjson').textContent = JSON.stringify(r.data, null, 2);
  $('loadjson').hidden = false;
  tickStats(); tickPkts();
}
$('go').onclick = () => doLoad(true);
$('go2').onclick = () => doLoad(false);
$('clear').onclick = async () => { await fetch('/api/received/clear', { method: 'POST' }); tickPkts(); };

async function tickStats() {
  const r = await getJson('/api/stats?n5=' + encodeURIComponent(n5())).catch(() => ({ ok: false }));
  if (!r.ok || !r.data || !r.data.stats) { $('counters').innerHTML = '<span class="muted">Không lấy được /stats.</span>'; $('hist').innerHTML = ''; return; }
  const s = r.data.stats;
  $('counters').innerHTML = [['đã nhận', s.received], ['đã tải', s.downloaded], ['đã gửi /parse', s.forwarded], ['lỗi tải', s.failed_download],
    ['lỗi gửi', s.failed_forward], ['bỏ qua 4xx/5xx', s.skipped_http_error], ['từ chối (bận)', s.rejected_busy], ['dữ liệu', (s.bytes / 1048576).toFixed(1) + ' MB']]
    .map(([k, v]) => '<div><b>' + esc(v) + '</b>' + k + '</div>').join('')
    + '<div><b>' + esc(r.data.parser_url) + '</b>PARSER_URL của N5</div>';
  $('hist').innerHTML = (r.data.history || []).slice(0, 15).map((h) => '<tr><td>' + esc((h.started_at || '').slice(11, 19)) + '</td><td class="u">' + esc(h.url)
    + '</td><td>' + esc(h.result) + (h.error ? '<br><span class="muted">' + esc(h.error) + '</span>' : '') + '</td><td>' + esc(h.http_status) + '</td><td>' + esc(h.format)
    + '</td><td>' + esc(h.render) + '</td><td>' + esc(h.ms) + '</td></tr>').join('') || '<tr><td colspan="7" class="muted">Chưa có.</td></tr>';
}

let lastPkt = -1;
async function tickPkts() {
  const list = await getJson('/api/received').catch(() => null);
  if (!list) return;
  const top = list.length ? list[0].id : 0;
  if (top === lastPkt) return;
  lastPkt = top;
  if (!list.length) { $('pkts').innerHTML = '<p class="muted">Chưa nhận gói nào.</p>'; return; }
  const SPEC = ['url', 'format', 'content_type', 'encoding', 'content'];
  $('pkts').innerHTML = list.slice(0, 10).map((p) => {
    const fields = SPEC.map((k) => '<span class="' + (p.missing.includes(k) ? 'miss' : '') + '">' + (p.missing.includes(k) ? '✗ ' : '✓ ') + k + '</span>').join('');
    const body = p.encoding === 'base64'
      ? '<div class="kv"><span>dung lượng</span><span>' + p.bytes.toLocaleString('vi-VN') + ' byte (base64 ' + p.chars.toLocaleString('vi-VN') + ' ký tự)</span><span>8 byte đầu</span><span><code>' + esc(p.magic) + '</code></span><span>SHA-256</span><span><code>' + esc(p.sha256) + '</code></span></div>'
      : '<div class="title">' + esc(p.title || '(không có <title>)') + '</div><div class="muted">' + esc(p.preview) + '…</div>'
        + '<div class="kv"><span>độ dài</span><span>' + p.chars.toLocaleString('vi-VN') + ' ký tự, ' + p.links + ' thẻ &lt;a&gt;</span></div>';
    return '<div class="pkt"><div class="head"><span class="url">#' + p.id + ' ' + esc(p.url) + '</span><span class="muted">' + esc(p.at.slice(11, 19)) + ' · từ <b>' + esc(p.from) + '</b> · ' + esc(p.downloader) + '</span></div>'
      + '<div class="fields">' + fields + '</div>'
      + '<div class="kv"><span>format</span><span><b>' + esc(p.format) + '</b></span><span>content_type</span><span>' + esc(p.content_type) + '</span><span>encoding</span><span><b>' + esc(p.encoding) + '</b></span>'
      + '<span>http_status / render</span><span>' + esc(p.http_status) + ' / ' + esc(p.render) + '</span><span>final_url</span><span>' + esc(p.final_url) + '</span></div>'
      + body + '</div>';
  }).join('');
}

init();
</script>
</body>
</html>`;

(async () => {
  await new Promise((resolve, reject) => {
    parser.once('error', reject);
    parser.listen(PARSER_PORT, '0.0.0.0', resolve);
  }).catch((e) => {
    console.error(`Không mở được cổng ${PARSER_PORT} cho Đọc nội dung giả (${e.code}). Dùng --parser-port khác.`);
    process.exit(1);
  });
  await new Promise((resolve) => ui.listen(UI_PORT, '0.0.0.0', resolve));
  console.log(`Trang kiểm tra N5: http://127.0.0.1:${UI_PORT}/`);
  for (const i of lanIps()) console.log(`  ${i.name}: http://${i.address}:${UI_PORT}/   (PARSER_URL cho N5: http://${i.address}:${PARSER_PORT}/parse)`);
  console.log('Ctrl+C để dừng.');
})();
