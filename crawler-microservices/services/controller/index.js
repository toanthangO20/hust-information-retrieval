#!/usr/bin/env node
'use strict';
// Mô-đun Điều khiển (BAT) — bản tham chiếu để chạy thử toàn hệ thống.
//   POST /add {"urls": [...]}  thêm URL cần thu thập
//   GET  /stats
// Lập lịch: xoay vòng (round-robin) giữa các mô-đun Tải về; chỉ gửi khi /ping trả "đang chờ";
// giãn cách giữa hai lần gửi cho cùng một mô-đun tải về và cùng một host.

const { log, createJsonServer, listen, close, getJson, requestJson, sleep, env } = require('../common/http');

function createController(o = {}) {
  const cfg = {
    port: Number(o.port ?? env('PORT', 5200)),
    host: o.host ?? env('HOST', '0.0.0.0'),
    downloaders: (o.downloaders ?? env('DOWNLOADERS', 'http://127.0.0.1:8800')).toString().split(',').map((s) => s.trim().replace(/\/$/, '')).filter(Boolean),
    tickMs: Number(o.tickMs ?? env('TICK_MS', 200)),
    downloaderDelayMs: Number(o.downloaderDelayMs ?? env('DOWNLOADER_DELAY_MS', 0)),
    hostDelayMs: Number(o.hostDelayMs ?? env('HOST_DELAY_MS', 700)),
    maxDispatch: Number(o.maxDispatch ?? env('MAX_DISPATCH', 0)), // 0 = không giới hạn
    name: 'điều khiển',
    quiet: o.quiet,
  };
  const L = (...a) => !cfg.quiet && log(cfg.name, ...a);
  const queue = [];
  const seen = new Set();
  const nextAt = new Map(); // downloader -> thời điểm được gửi tiếp
  const hostNextAt = new Map();
  const perDownloader = Object.fromEntries(cfg.downloaders.map((d) => [d, { dispatched: 0, errors: 0, up: null }]));
  let rr = 0;
  let dispatched = 0;
  let timer = null;
  let ticking = false;

  function hostOf(u) {
    try {
      return new URL(u).host;
    } catch (_) {
      return '';
    }
  }

  /** Lấy URL đầu tiên trong hàng đợi có host đã hết thời gian giãn cách. */
  function takeReady(now) {
    for (let i = 0; i < queue.length; i++) {
      const h = hostOf(queue[i]);
      if ((hostNextAt.get(h) || 0) <= now) return queue.splice(i, 1)[0];
    }
    return null;
  }

  async function tick() {
    if (ticking) return;
    ticking = true;
    try {
      for (let n = 0; n < cfg.downloaders.length && queue.length; n++) {
        if (cfg.maxDispatch && dispatched >= cfg.maxDispatch) break;
        const d = cfg.downloaders[rr];
        rr = (rr + 1) % cfg.downloaders.length;
        const now = Date.now();
        if ((nextAt.get(d) || 0) > now) continue;
        let ping;
        try {
          ping = await getJson(d + '/ping', { timeoutMs: 2000 });
        } catch (_) {
          if (perDownloader[d].up !== false) L('không liên lạc được', d);
          perDownloader[d].up = false;
          nextAt.set(d, now + 2000);
          continue;
        }
        perDownloader[d].up = true;
        if (!ping.data || ping.data.status !== 'đang chờ') continue;
        const url = takeReady(now);
        if (!url) break;
        try {
          const r = await requestJson('POST', d + '/load', { url }, { timeoutMs: 5000 });
          if (r.status === 202 || r.status === 200) {
            dispatched++;
            perDownloader[d].dispatched++;
            hostNextAt.set(hostOf(url), Date.now() + cfg.hostDelayMs);
            nextAt.set(d, Date.now() + cfg.downloaderDelayMs);
            L(`-> ${d} ${url}`);
          } else {
            queue.unshift(url);
            perDownloader[d].errors++;
            if (r.status !== 503) L(`tải về từ chối ${r.status}`, url);
          }
        } catch (e) {
          queue.unshift(url);
          perDownloader[d].errors++;
          nextAt.set(d, Date.now() + 2000);
        }
      }
    } finally {
      ticking = false;
    }
  }

  const routes = {
    'POST /add': async (body) => {
      const urls = Array.isArray(body?.urls) ? body.urls : body?.url ? [body.url] : null;
      if (!urls) return [400, { status: 'lỗi', error: 'cần {"urls": [...]}' }];
      let added = 0;
      for (const u of urls) {
        if (typeof u !== 'string' || seen.has(u)) continue;
        seen.add(u);
        queue.push(u);
        added++;
      }
      return [200, { status: 'ok', added, queued: queue.length }];
    },
    'GET /stats': async () => [200, { status: 'ok', queued: queue.length, seen: seen.size, dispatched, downloaders: perDownloader }],
    'GET /ping': async () => [200, { status: 'đang chờ' }],
  };
  const server = createJsonServer(cfg.name, routes);
  return {
    cfg,
    server,
    get queue() {
      return queue;
    },
    get dispatched() {
      return dispatched;
    },
    perDownloader,
    async start() {
      cfg.port = await listen(server, cfg.port, cfg.host);
      timer = setInterval(tick, cfg.tickMs);
      L(`nghe tại :${cfg.port}, tải về: ${cfg.downloaders.join(', ')}`);
      return this;
    },
    async stop() {
      clearInterval(timer);
      while (ticking) await sleep(20);
      await close(server);
    },
  };
}

if (require.main === module) createController().start();
module.exports = { createController };
