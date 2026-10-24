'use strict';
// Lõi tải về: trang web -> Puppeteer + Chrome headless; tài liệu -> tải trực tiếp (HTTP).
// Kết quả là gói JSON đúng đặc tả lệnh /parse của mô-đun Đọc nội dung.

const DOC_EXT = new Set([
  'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'odt', 'ods', 'odp', 'rtf', 'txt', 'csv',
  'zip', 'rar', '7z', 'xml', 'json', 'epub',
]);

const CT_FORMAT = {
  'text/html': 'html',
  'application/xhtml+xml': 'html',
  'application/pdf': 'pdf',
  'application/msword': 'doc',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
  'application/vnd.ms-excel': 'xls',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
  'application/vnd.ms-powerpoint': 'ppt',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'pptx',
  'application/vnd.oasis.opendocument.text': 'odt',
  'application/rtf': 'rtf',
  'text/plain': 'txt',
  'text/csv': 'csv',
  'application/zip': 'zip',
  'application/xml': 'xml',
  'text/xml': 'xml',
  'application/json': 'json',
};

function extOf(url) {
  try {
    const p = new URL(url).pathname.toLowerCase();
    const m = p.match(/\.([a-z0-9]{1,5})$/);
    return m ? m[1] : '';
  } catch (_) {
    return '';
  }
}

/** 'document' nếu đuôi URL là tài liệu, ngược lại 'page' (sẽ render bằng Chrome). */
function classifyUrl(url) {
  return DOC_EXT.has(extOf(url)) ? 'document' : 'page';
}

function mimeOf(contentType) {
  return String(contentType || '').split(';')[0].trim().toLowerCase();
}

function isHtmlType(contentType) {
  const m = mimeOf(contentType);
  return m === 'text/html' || m === 'application/xhtml+xml';
}

/** Định dạng tài liệu: ưu tiên Content-Type, sau đó đuôi URL, cuối cùng 'other'. */
function formatOf(contentType, url) {
  const m = mimeOf(contentType);
  if (CT_FORMAT[m]) return CT_FORMAT[m];
  const e = extOf(url);
  if (e) return e === 'htm' ? 'html' : e;
  return 'other';
}

function charsetOf(contentType, buf) {
  const m = /charset=([^;]+)/i.exec(contentType || '');
  if (m) return m[1].trim().replace(/["']/g, '').toLowerCase();
  // Tìm <meta charset> trong 2 KB đầu
  const head = buf.subarray(0, 2048).toString('latin1');
  const mm = /<meta[^>]+charset=["']?([\w-]+)/i.exec(head);
  return mm ? mm[1].toLowerCase() : 'utf-8';
}

function decodeText(buf, contentType) {
  const cs = charsetOf(contentType, buf);
  try {
    return new TextDecoder(cs).decode(buf);
  } catch (_) {
    return new TextDecoder('utf-8').decode(buf);
  }
}

/** Gói gửi sang Đọc nội dung (POST /parse). 5 trường đầu là đặc tả; các trường sau là bổ sung, bên nhận có thể bỏ qua. */
function buildPayload({ url, finalUrl, contentType, body, isHtml, httpStatus, render, downloaderId }) {
  const textual = isHtml || /^text\//.test(mimeOf(contentType));
  return {
    url,
    format: isHtml ? 'html' : formatOf(contentType, finalUrl || url),
    content_type: contentType || (isHtml ? 'text/html; charset=utf-8' : 'application/octet-stream'),
    encoding: textual ? 'text' : 'base64',
    content: typeof body === 'string' ? body : textual ? decodeText(body, contentType) : body.toString('base64'),
    final_url: finalUrl || url,
    http_status: httpStatus,
    fetched_at: new Date().toISOString(),
    render,
    downloader: downloaderId,
  };
}

class DownloadError extends Error {
  constructor(message, { httpStatus = 0, kind = 'error' } = {}) {
    super(message);
    this.httpStatus = httpStatus;
    this.kind = kind;
  }
}

/** Tải trực tiếp bằng fetch (theo chuyển hướng, giới hạn dung lượng và thời gian). */
async function httpFetch(url, cfg) {
  let res;
  try {
    res = await fetch(url, {
      redirect: 'follow',
      headers: { 'User-Agent': cfg.userAgent, Accept: '*/*' },
      signal: AbortSignal.timeout(cfg.navTimeoutMs),
    });
  } catch (e) {
    const kind = e.name === 'TimeoutError' || e.name === 'AbortError' ? 'timeout' : 'network';
    throw new DownloadError(`${kind}: ${e.cause?.code || e.message}`, { kind });
  }
  const len = Number(res.headers.get('content-length') || 0);
  if (len > cfg.maxBytes) {
    res.body?.cancel().catch(() => {});
    throw new DownloadError(`quá dung lượng: ${len} > ${cfg.maxBytes} byte`, { httpStatus: res.status, kind: 'too-large' });
  }
  const chunks = [];
  let size = 0;
  try {
    for await (const c of res.body) {
      size += c.length;
      if (size > cfg.maxBytes) throw new DownloadError(`quá dung lượng: > ${cfg.maxBytes} byte`, { httpStatus: res.status, kind: 'too-large' });
      chunks.push(c);
    }
  } catch (e) {
    if (e instanceof DownloadError) throw e;
    const kind = e.name === 'TimeoutError' || e.name === 'AbortError' ? 'timeout' : 'network';
    throw new DownloadError(`${kind}: ${e.message}`, { kind, httpStatus: res.status });
  }
  return {
    httpStatus: res.status,
    finalUrl: res.url || url,
    contentType: res.headers.get('content-type') || '',
    body: Buffer.concat(chunks.map((c) => Buffer.from(c))),
  };
}

/** Quản lý một tiến trình Chrome headless dùng chung, tự khởi động lại khi bị ngắt. */
class Browser {
  constructor(cfg) {
    this.cfg = cfg;
    this.browser = null;
    this.launching = null;
  }

  async get() {
    if (this.browser && this.browser.connected) return this.browser;
    if (!this.launching) {
      this.launching = (async () => {
        const puppeteer = require('puppeteer');
        const opts = {
          headless: true,
          args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
        };
        if (this.cfg.chromePath) opts.executablePath = this.cfg.chromePath;
        this.browser = await puppeteer.launch(opts);
        return this.browser;
      })().finally(() => {
        this.launching = null;
      });
    }
    return this.launching;
  }

  async close() {
    if (this.browser) await this.browser.close().catch(() => {});
    this.browser = null;
  }
}

/** Render trang bằng Chrome. Trả null nếu tài nguyên không phải HTML (để tải lại dạng tài liệu). */
async function chromeFetch(url, cfg, browserMgr) {
  const browser = await browserMgr.get();
  const page = await browser.newPage();
  try {
    await page.setUserAgent(cfg.userAgent);
    page.setDefaultNavigationTimeout(cfg.navTimeoutMs);
    if (cfg.blockResources) {
      await page.setRequestInterception(true);
      page.on('request', (r) => {
        const t = r.resourceType();
        if (t === 'image' || t === 'media' || t === 'font') r.abort().catch(() => {});
        else r.continue().catch(() => {});
      });
    }
    let resp;
    try {
      resp = await page.goto(url, { waitUntil: cfg.waitUntil, timeout: cfg.navTimeoutMs });
    } catch (e) {
      if (/ERR_ABORTED|download/i.test(e.message)) return null; // trình duyệt chuyển sang tải tệp -> là tài liệu
      if (/timeout/i.test(e.name + e.message)) throw new DownloadError(`timeout: ${e.message}`, { kind: 'timeout' });
      throw new DownloadError(`network: ${e.message}`, { kind: 'network' });
    }
    if (!resp) return null;
    const contentType = resp.headers()['content-type'] || '';
    if (!isHtmlType(contentType)) return null;
    const html = await page.content();
    return { httpStatus: resp.status(), finalUrl: page.url(), contentType, body: html };
  } finally {
    await page.close().catch(() => {});
  }
}

/**
 * Tải một URL theo chế độ cấu hình. Trả { payload } (gói /parse).
 * Ném DownloadError khi lỗi mạng/quá thời gian/quá dung lượng.
 */
async function download(url, cfg, browserMgr, state = {}) {
  const useChrome = cfg.render !== 'http' && !state.chromeBroken && classifyUrl(url) === 'page';
  if (useChrome) {
    let r;
    try {
      r = await chromeFetch(url, cfg, browserMgr);
    } catch (e) {
      if (e instanceof DownloadError) throw e;
      // Không khởi động được Chrome
      if (cfg.render === 'chrome') throw new DownloadError(`không khởi động được Chrome: ${e.message}`, { kind: 'browser' });
      state.chromeBroken = e.message;
      r = undefined;
    }
    if (r) {
      return {
        payload: buildPayload({ url, finalUrl: r.finalUrl, contentType: r.contentType, body: r.body, isHtml: true, httpStatus: r.httpStatus, render: 'chrome', downloaderId: cfg.id }),
      };
    }
  }
  const r = await httpFetch(url, cfg);
  return {
    payload: buildPayload({ url, finalUrl: r.finalUrl, contentType: r.contentType, body: r.body, isHtml: isHtmlType(r.contentType), httpStatus: r.httpStatus, render: 'http', downloaderId: cfg.id }),
  };
}

module.exports = { classifyUrl, formatOf, isHtmlType, decodeText, buildPayload, download, Browser, DownloadError, extOf };
