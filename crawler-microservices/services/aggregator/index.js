#!/usr/bin/env node
'use strict';
// Mô-đun Tổng hợp dữ liệu (N8) — bản tham chiếu để chạy thử toàn hệ thống.
//   POST /add  {url, format, title, content, intro, out-links: [{url, text}]}
//   POST /seed {"urls": [...]}  nạp URL mầm (cũng tự nạp SEEDS khi khởi động)
// Xử lý: gửi bản ghi sang Lưu trữ; lọc URL đi ra trong miền cho phép, bỏ URL đã có, gửi URL mới cho Điều khiển.

const { log, createJsonServer, listen, close, postJson, normalizeUrl, sleep, env } = require('../common/http');

function createAggregator(o = {}) {
  const list = (v) => String(v || '').split(',').map((s) => s.trim()).filter(Boolean);
  const cfg = {
    port: Number(o.port ?? env('PORT', 3300)),
    host: o.host ?? env('HOST', '0.0.0.0'),
    controllerUrl: o.controllerUrl ?? env('CONTROLLER_URL', 'http://127.0.0.1:5200/add'),
    storageUrl: o.storageUrl ?? env('STORAGE_URL', 'http://127.0.0.1:2200/add'),
    domains: list(o.domains ?? env('ALLOWED_DOMAINS', 'hust.edu.vn')),
    seeds: list(o.seeds ?? env('SEEDS', 'https://hust.edu.vn/')),
    maxUrls: Number(o.maxUrls ?? env('MAX_URLS', 200)),
    name: 'tổng hợp',
    quiet: o.quiet,
  };
  const L = (...a) => !cfg.quiet && log(cfg.name, ...a);
  const seen = new Set();
  const stats = { records: 0, stored: 0, new_urls: 0, filtered_domain: 0, duplicates: 0, capped: 0, errors: 0 };

  function inDomain(u) {
    const h = new URL(u).hostname;
    return cfg.domains.some((d) => h === d || h.endsWith('.' + d));
  }

  async function sendToController(urls) {
    if (!urls.length) return;
    await postJson(cfg.controllerUrl, { urls }, { retries: 5 });
  }

  /** Lọc miền, bỏ trùng, giới hạn tổng số URL. Trả danh sách URL mới. */
  function admit(rawUrls, base) {
    const fresh = [];
    for (const raw of rawUrls) {
      const u = normalizeUrl(raw, base);
      if (!u) continue;
      if (!inDomain(u)) {
        stats.filtered_domain++;
        continue;
      }
      if (seen.has(u)) {
        stats.duplicates++;
        continue;
      }
      if (seen.size >= cfg.maxUrls) {
        stats.capped++;
        continue;
      }
      seen.add(u);
      fresh.push(u);
    }
    stats.new_urls += fresh.length;
    return fresh;
  }

  const routes = {
    'POST /add': async (body) => {
      if (!body || typeof body.url !== 'string') return [400, { status: 'lỗi', error: 'cần url' }];
      stats.records++;
      const u = normalizeUrl(body.url);
      if (u) seen.add(u);
      try {
        await postJson(cfg.storageUrl, body, { retries: 3 });
        stats.stored++;
      } catch (e) {
        stats.errors++;
        L('lỗi gửi sang lưu trữ', e.message);
      }
      const outs = Array.isArray(body['out-links']) ? body['out-links'].map((l) => (typeof l === 'string' ? l : l && l.url)).filter(Boolean) : [];
      const fresh = admit(outs, body.url);
      try {
        await sendToController(fresh);
      } catch (e) {
        stats.errors++;
        L('lỗi gửi sang điều khiển', e.message);
      }
      L(`nhận ${body.url}: ${outs.length} liên kết, ${fresh.length} URL mới`);
      return [200, { status: 'ok', new_urls: fresh.length }];
    },
    'POST /seed': async (body) => {
      const fresh = admit(Array.isArray(body?.urls) ? body.urls : []);
      await sendToController(fresh);
      return [200, { status: 'ok', added: fresh.length }];
    },
    'GET /stats': async () => [200, { status: 'ok', seen: seen.size, ...stats }],
  };
  const server = createJsonServer(cfg.name, routes);
  return {
    cfg,
    server,
    stats,
    seen,
    async start({ seed = true } = {}) {
      cfg.port = await listen(server, cfg.port, cfg.host);
      L(`nghe tại :${cfg.port}, miền: ${cfg.domains.join(', ')}, tối đa ${cfg.maxUrls} URL`);
      if (seed && cfg.seeds.length) {
        // Điều khiển có thể khởi động sau: thử lại đến khi gửi được
        (async () => {
          for (let i = 0; i < 30; i++) {
            try {
              const fresh = admit(cfg.seeds);
              await sendToController(fresh);
              L('đã nạp URL mầm:', fresh.join(', '));
              return;
            } catch (_) {
              cfg.seeds.forEach((s) => seen.delete(normalizeUrl(s)));
              await sleep(1000);
            }
          }
        })();
      }
      return this;
    },
    stop: () => close(server),
  };
}

if (require.main === module) createAggregator().start();
module.exports = { createAggregator };
