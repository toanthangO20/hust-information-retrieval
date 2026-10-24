#!/usr/bin/env node
'use strict';
// Mô-đun Đọc nội dung (N1) — bản tham chiếu để chạy thử toàn hệ thống.
//   POST /parse {url, format, content_type, encoding, content}
// HTML: tiêu đề, nội dung văn bản, liên kết đi ra kèm câu chứa thẻ <a>.
// Tài liệu khác: văn bản nếu đọc được (txt/csv/xml/json), còn lại để trống (bản tham chiếu không dùng Tika).
// Kết quả đẩy sang Tổng hợp dữ liệu (POST /add).

const cheerio = require('cheerio');
const { log, createJsonServer, listen, close, postJson, normalizeUrl, env } = require('../common/http');

const BLOCK = 'p,li,td,th,dd,dt,h1,h2,h3,h4,h5,h6,blockquote,figcaption,div,section,article';
const squash = (s) => String(s || '').replace(/\s+/g, ' ').trim();

/** Câu hoàn chỉnh chứa văn bản liên kết, lấy từ khối cha gần nhất. */
function sentenceAround($, a) {
  const anchor = squash($(a).text());
  const block = squash($(a).closest(BLOCK).first().text());
  if (!block || !anchor) return anchor || squash($(a).attr('title') || '');
  const sentences = block.split(/(?<=[.!?…])\s+/);
  return sentences.find((s) => s.includes(anchor)) || anchor;
}

function parseHtml(html, baseUrl) {
  const $ = cheerio.load(html);
  const title = squash($('title').first().text()) || squash($('h1').first().text());
  const intro = squash($('meta[name="description"]').attr('content') || $('meta[property="og:description"]').attr('content') || '');
  const links = [];
  const seen = new Set();
  $('a[href]').each((_, a) => {
    const url = normalizeUrl($(a).attr('href'), baseUrl);
    if (!url) return;
    const text = sentenceAround($, a);
    const key = url + '\u0001' + text;
    if (seen.has(key)) return;
    seen.add(key);
    links.push({ url, text });
  });
  $('script,style,noscript,template,svg,iframe').remove();
  const content = squash($('body').text() || $.root().text());
  return { title, intro, content, links };
}

function parseOther(body) {
  const fmt = String(body.format || 'other').toLowerCase();
  let content = '';
  if (['txt', 'csv', 'xml', 'json'].includes(fmt)) {
    content = body.encoding === 'base64' ? Buffer.from(body.content || '', 'base64').toString('utf8') : String(body.content || '');
  }
  return { title: '', intro: '', content: squash(content), links: [] };
}

function createParser(o = {}) {
  const cfg = {
    port: Number(o.port ?? env('PORT', 6600)),
    host: o.host ?? env('HOST', '0.0.0.0'),
    aggregatorUrl: o.aggregatorUrl ?? env('AGGREGATOR_URL', 'http://127.0.0.1:3300/add'),
    name: 'đọc nội dung',
    quiet: o.quiet,
  };
  const L = (...a) => !cfg.quiet && log(cfg.name, ...a);
  const stats = { parsed: 0, forwarded: 0, errors: 0 };
  const received = [];

  const routes = {
    'POST /parse': async (body) => {
      if (!body || typeof body.url !== 'string' || typeof body.content !== 'string') {
        return [400, { status: 'lỗi', error: 'cần url, format, content' }];
      }
      received.push({ url: body.url, format: body.format, encoding: body.encoding, downloader: body.downloader });
      const isHtml = body.format === 'html';
      const r = isHtml ? parseHtml(body.content, body.final_url || body.url) : parseOther(body);
      stats.parsed++;
      const record = { url: body.url, format: isHtml ? 'html' : body.format || 'other', title: r.title, content: r.content, intro: r.intro, 'out-links': r.links };
      try {
        await postJson(cfg.aggregatorUrl, record, { retries: 3 });
        stats.forwarded++;
      } catch (e) {
        stats.errors++;
        L('lỗi gửi sang tổng hợp', e.message);
      }
      L(`đọc ${body.url} (${record.format}) -> ${r.links.length} liên kết`);
      return [200, { status: 'ok', url: body.url, title: r.title, links: r.links.length }];
    },
    'GET /stats': async () => [200, { status: 'ok', ...stats }],
  };
  const server = createJsonServer(cfg.name, routes);
  return {
    cfg,
    server,
    stats,
    received,
    async start() {
      cfg.port = await listen(server, cfg.port, cfg.host);
      L(`nghe tại :${cfg.port} -> ${cfg.aggregatorUrl}`);
      return this;
    },
    stop: () => close(server),
  };
}

if (require.main === module) createParser().start();
module.exports = { createParser, parseHtml };
