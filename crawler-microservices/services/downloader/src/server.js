'use strict';
// Mô-đun Tải về (N5) — dịch vụ HTTP/JSON.
//   POST /load {"url": "..."}   nhận URL cần tải, xếp hàng, tải xong đẩy sang Đọc nội dung (POST /parse)
//   GET  /ping                  {"status": "đang chờ" | "đang bận", ...}
//   GET  /stats                 số liệu và lịch sử gần đây
// Tệp này tự chứa (không phụ thuộc services/common) để có thể tách mô-đun ra chạy riêng.

const http = require('http');
const { loadConfig } = require('./config');
const { download, Browser, DownloadError } = require('./fetcher');

const STATUS_IDLE = 'đang chờ';
const STATUS_BUSY = 'đang bận';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function log(cfg, ...args) {
  if (cfg.quiet) return;
  const t = new Date().toISOString().slice(11, 23);
  console.log(`[${t}] [${cfg.id}]`, ...args);
}

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function readJson(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(Object.assign(new Error('body quá lớn'), { status: 413 }));
        req.destroy();
      } else chunks.push(c);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch (_) {
        reject(Object.assign(new Error('JSON không hợp lệ'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function validUrl(s) {
  if (typeof s !== 'string' || !s.trim()) return null;
  try {
    const u = new URL(s.trim());
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.toString() : null;
  } catch (_) {
    return null;
  }
}

/** Gửi gói sang Đọc nội dung, thử lại với độ trễ tăng dần khi lỗi mạng hoặc 5xx. */
async function forward(cfg, payload) {
  let last;
  for (let i = 0; i <= cfg.forwardRetries; i++) {
    try {
      const res = await fetch(cfg.parserUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(cfg.forwardTimeoutMs),
      });
      await res.text().catch(() => '');
      if (res.status < 500) return { ok: res.status < 300, status: res.status, attempts: i + 1 };
      last = `HTTP ${res.status}`;
    } catch (e) {
      last = e.cause?.code || e.message;
    }
    if (i < cfg.forwardRetries) await sleep(cfg.forwardBackoffMs * 2 ** i);
  }
  return { ok: false, status: 0, attempts: cfg.forwardRetries + 1, error: last };
}

function createDownloader(overrides = {}) {
  const cfg = loadConfig(overrides);
  const browser = new Browser(cfg);
  const fetchState = {}; // chromeBroken: lý do nếu Chrome không chạy được (chế độ auto lùi về HTTP)
  const queue = []; // [{url, waiters: []}]
  let active = 0;
  const stats = { received: 0, rejected_busy: 0, downloaded: 0, forwarded: 0, failed_download: 0, failed_forward: 0, skipped_http_error: 0, bytes: 0 };
  const history = [];
  const startedAt = new Date().toISOString();

  function record(entry) {
    history.unshift(entry);
    if (history.length > cfg.historySize) history.pop();
  }

  const busy = () => active + queue.length >= cfg.concurrency;

  async function processJob(job) {
    const t0 = Date.now();
    const entry = { url: job.url, started_at: new Date(t0).toISOString() };
    try {
      const { payload } = await download(job.url, cfg, browser, fetchState);
      stats.downloaded++;
      stats.bytes += Buffer.byteLength(payload.content);
      Object.assign(entry, {
        http_status: payload.http_status, format: payload.format, content_type: payload.content_type,
        encoding: payload.encoding, render: payload.render, final_url: payload.final_url, chars: payload.content.length,
      });
      if (payload.http_status >= 400 && !cfg.forwardErrors) {
        stats.skipped_http_error++;
        entry.result = 'bỏ qua (HTTP ' + payload.http_status + ')';
      } else {
        const f = await forward(cfg, payload);
        entry.forward = f;
        if (f.ok) {
          stats.forwarded++;
          entry.result = 'đã gửi sang đọc nội dung';
        } else {
          stats.failed_forward++;
          entry.result = 'lỗi gửi sang đọc nội dung';
        }
      }
    } catch (e) {
      stats.failed_download++;
      entry.result = 'lỗi tải';
      entry.error = e.message;
      entry.kind = e instanceof DownloadError ? e.kind : 'error';
      if (e.httpStatus) entry.http_status = e.httpStatus;
    }
    entry.ms = Date.now() - t0;
    record(entry);
    log(cfg, `${entry.result} ${job.url} ${entry.http_status || ''} ${entry.format || ''} ${entry.render || ''} ${entry.ms}ms${entry.error ? ' — ' + entry.error : ''}`);
    for (const w of job.waiters) w(entry);
  }

  function pump() {
    while (active < cfg.concurrency && queue.length) {
      const job = queue.shift();
      active++;
      processJob(job).finally(() => {
        active--;
        pump();
      });
    }
  }

  function ping() {
    return {
      status: busy() ? STATUS_BUSY : STATUS_IDLE,
      id: cfg.id,
      active,
      queued: queue.length,
      capacity: cfg.concurrency,
      render: cfg.render === 'http' || fetchState.chromeBroken ? 'http' : cfg.render,
    };
  }

  async function handle(req, res) {
    const u = new URL(req.url, 'http://x');
    try {
      if (req.method === 'GET' && u.pathname === '/ping') return send(res, 200, ping());
      if (req.method === 'GET' && u.pathname === '/stats') {
        return send(res, 200, { ...ping(), started_at: startedAt, parser_url: cfg.parserUrl, chrome_error: fetchState.chromeBroken || null, stats, history });
      }
      if (req.method === 'POST' && u.pathname === '/load') {
        const body = await readJson(req);
        const url = validUrl(body && body.url);
        if (!url) return send(res, 400, { status: 'lỗi', error: 'thiếu hoặc sai "url" (chỉ nhận http/https)' });
        const wait = u.searchParams.get('wait') === '1';
        const existing = queue.find((j) => j.url === url);
        if (existing && !wait) return send(res, 202, { status: 'đã nhận', url, note: 'đã có trong hàng đợi', queued: queue.length });
        if (!existing && active + queue.length >= cfg.concurrency + cfg.queueMax) {
          stats.rejected_busy++;
          return send(res, 503, { status: STATUS_BUSY, url, error: 'hàng đợi đầy, thử lại sau' });
        }
        const job = existing || { url, waiters: [] };
        const done = wait ? new Promise((r) => job.waiters.push(r)) : null;
        if (!existing) {
          stats.received++;
          queue.push(job);
          pump();
        }
        if (wait) return send(res, 200, { status: 'xong', ...(await done) });
        return send(res, 202, { status: 'đã nhận', url, queued: queue.length, active });
      }
      send(res, 404, { status: 'lỗi', error: `không có ${req.method} ${u.pathname}` });
    } catch (e) {
      send(res, e.status || 500, { status: 'lỗi', error: e.message });
    }
  }

  const server = http.createServer((req, res) => {
    handle(req, res);
  });

  return {
    cfg,
    server,
    stats,
    history,
    ping,
    async start() {
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(cfg.port, cfg.host, resolve);
      });
      cfg.port = server.address().port;
      log(cfg, `Tải về (N5) nghe tại http://${cfg.host}:${cfg.port} -> đọc nội dung: ${cfg.parserUrl}, render=${cfg.render}, concurrency=${cfg.concurrency}`);
      return this;
    },
    async stop() {
      await new Promise((r) => server.close(() => r()));
      await browser.close();
    },
    /** Chờ đến khi hàng đợi rỗng và không còn việc đang chạy (dùng cho kiểm thử). */
    async idle(timeoutMs = 30000) {
      const end = Date.now() + timeoutMs;
      while (active + queue.length > 0) {
        if (Date.now() > end) throw new Error('quá thời gian chờ rảnh');
        await sleep(50);
      }
    },
  };
}

module.exports = { createDownloader, STATUS_IDLE, STATUS_BUSY };
