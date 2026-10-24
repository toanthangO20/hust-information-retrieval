'use strict';
// Tiện ích HTTP + JSON dùng chung cho các mô-đun tham chiếu (điều khiển, đọc nội dung, tổng hợp, lưu trữ).
// Mô-đun Tải về (N5) KHÔNG dùng tệp này để có thể tách ra chạy độc lập.

const http = require('http');

function log(name, ...args) {
  const t = new Date().toISOString().slice(11, 23);
  console.log(`[${t}] [${name}]`, ...args);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(Object.assign(new Error('body quá lớn'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

/**
 * routes: { 'POST /add': async (body, ctx) => [status, obj] , ... }
 * ctx = { query: URLSearchParams, req }
 */
function createJsonServer(name, routes, { bodyLimit = 64 * 1024 * 1024 } = {}) {
  return http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    const handler = routes[`${req.method} ${u.pathname}`];
    if (!handler) return send(res, 404, { status: 'lỗi', error: `không có ${req.method} ${u.pathname}` });
    try {
      let body = null;
      if (req.method === 'POST' || req.method === 'PUT') {
        const raw = await readBody(req, bodyLimit);
        try {
          body = raw ? JSON.parse(raw) : {};
        } catch (e) {
          return send(res, 400, { status: 'lỗi', error: 'JSON không hợp lệ' });
        }
      }
      const [status, obj] = await handler(body, { query: u.searchParams, req });
      send(res, status, obj);
    } catch (e) {
      log(name, 'lỗi xử lý', e.message);
      send(res, e.status || 500, { status: 'lỗi', error: e.message });
    }
  });
}

function listen(server, port, host = '0.0.0.0') {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve) => (server ? server.close(() => resolve()) : resolve()));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function requestJson(method, url, obj, { timeoutMs = 10000 } = {}) {
  const res = await fetch(url, {
    method,
    headers: obj ? { 'Content-Type': 'application/json' } : undefined,
    body: obj ? JSON.stringify(obj) : undefined,
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch (_) {
    data = { raw: text };
  }
  return { status: res.status, data };
}

/** POST JSON, thử lại với độ trễ tăng dần khi lỗi mạng hoặc 5xx. */
async function postJson(url, obj, { retries = 3, timeoutMs = 10000, backoffMs = 300 } = {}) {
  let lastErr;
  for (let i = 0; i <= retries; i++) {
    try {
      const r = await requestJson('POST', url, obj, { timeoutMs });
      if (r.status < 500) return r;
      lastErr = new Error(`HTTP ${r.status}`);
    } catch (e) {
      lastErr = e;
    }
    if (i < retries) await sleep(backoffMs * 2 ** i);
  }
  throw lastErr;
}

const getJson = (url, opts) => requestJson('GET', url, null, opts);

/** Chuẩn hoá URL để so trùng: bỏ #fragment, host chữ thường, bỏ cổng mặc định. */
function normalizeUrl(raw, base) {
  try {
    const u = new URL(raw, base);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    u.hash = '';
    u.hostname = u.hostname.toLowerCase();
    if ((u.protocol === 'http:' && u.port === '80') || (u.protocol === 'https:' && u.port === '443')) u.port = '';
    return u.toString();
  } catch (_) {
    return null;
  }
}

function env(name, def) {
  const v = process.env[name];
  return v === undefined || v === '' ? def : v;
}

module.exports = { log, createJsonServer, listen, close, sleep, postJson, getJson, requestJson, normalizeUrl, env };
