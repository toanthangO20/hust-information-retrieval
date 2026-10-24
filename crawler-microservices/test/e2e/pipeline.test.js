'use strict';
// Kiểm thử đầu-cuối: chạy đủ chuỗi Điều khiển -> Tải về (2 bản, N5) -> Đọc nội dung -> Tổng hợp -> Lưu trữ
// trên website giả lập, kiểm tra dữ liệu cuối cùng trong Lưu trữ.

const test = require('node:test');
const assert = require('node:assert/strict');
const { createSite } = require('../../services/downloader/test/fixtures/site');
const { createDownloader } = require('../../services/downloader/src/server');
const { createController } = require('../../services/controller');
const { createParser } = require('../../services/parser');
const { createAggregator } = require('../../services/aggregator');
const { createStorage } = require('../../services/storage');

const CHROME_PATH = process.env.CHROME_PATH || '';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const L = '127.0.0.1';

async function startPipeline(site, { extraDownloaders = [], render = 'auto' } = {}) {
  const storage = await createStorage({ port: 0, host: L, dataDir: null, quiet: true }).start();
  const parserPort = await freePort();
  const aggPort = await freePort();
  const ctlPort = await freePort();
  const dl = [];
  for (let i = 0; i < 2; i++) {
    dl.push(await createDownloader({ id: 'N5-' + (i + 1), port: 0, host: L, parserUrl: `http://${L}:${parserPort}/parse`, render, chromePath: CHROME_PATH, quiet: true, navTimeoutMs: 15000 }).start());
  }
  const downloaders = [...dl.map((d) => `http://${L}:${d.cfg.port}`), ...extraDownloaders];
  const controller = await createController({ port: ctlPort, host: L, downloaders: downloaders.join(','), tickMs: 30, hostDelayMs: 0, quiet: true }).start();
  const parser = await createParser({ port: parserPort, host: L, aggregatorUrl: `http://${L}:${aggPort}/add`, quiet: true }).start();
  const aggregator = await createAggregator({
    port: aggPort, host: L, domains: L, seeds: site.base + '/', maxUrls: 100,
    controllerUrl: `http://${L}:${ctlPort}/add`, storageUrl: `http://${L}:${storage.cfg.port}/add`, quiet: true,
  }).start();

  const stats = async () => (await fetch(`http://${L}:${storage.cfg.port}/stats`)).json();
  return {
    storage, controller, parser, aggregator, dl, stats,
    async records(c) {
      return (await (await fetch(`http://${L}:${storage.cfg.port}/records?collection=${c}&limit=1000`)).json()).records;
    },
    /** Chờ đến khi hệ thống lặng: hàng đợi rỗng, các N5 rảnh, số bản ghi không đổi trong 1,2 s. */
    async settle(timeoutMs = 90000) {
      const end = Date.now() + timeoutMs;
      let last = '';
      let stableSince = Date.now();
      while (Date.now() < end) {
        const s = JSON.stringify(await stats());
        const busy = controller.queue.length > 0 || dl.some((d) => d.ping().active + d.ping().queued > 0);
        if (s !== last || busy) {
          last = s;
          stableSince = Date.now();
        } else if (Date.now() - stableSince > 1200) return JSON.parse(s);
        await sleep(100);
      }
      throw new Error('hệ thống không lặng sau ' + timeoutMs + 'ms');
    },
    async stop() {
      await controller.stop();
      for (const d of dl) await d.stop();
      await parser.stop();
      await aggregator.stop();
      await storage.stop();
    },
  };
}

function freePort() {
  return new Promise((resolve) => {
    const s = require('net').createServer();
    s.listen(0, L, () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

let site;
test.before(async () => {
  site = await createSite().start();
});
test.after(() => site.stop());

test('E01 toàn hệ thống: từ URL mầm thu thập đủ trang trong miền, lưu đúng cấu trúc html/other/graph', async () => {
  const p = await startPipeline(site);
  try {
    const s = await p.settle();
    // Trang đến được từ "/": /, /a, /b (html); /doc.pdf, /notes.txt (other). /js không có liên kết tới; trang ngoài miền bị lọc.
    assert.equal(s.html, 3, 'số trang html');
    assert.equal(s.other, 2, 'số tài liệu other');
    assert.equal(s.graph, 8, 'số cạnh đồ thị');

    const html = await p.records('html');
    const home = html.find((r) => r.url === site.base + '/');
    assert.equal(home.title, 'Trang chủ Bách Khoa');
    assert.equal(home.intro, 'Cổng thông tin trường');
    assert.match(home.content, /Đại học Bách khoa Hà Nội/);
    assert.deepEqual(html.map((r) => r.url).sort(), ['/', '/a', '/b'].map((x) => site.base + x).sort());

    const other = await p.records('other');
    assert.deepEqual(other.map((r) => [r.url, r.format]).sort(), [[site.base + '/doc.pdf', 'pdf'], [site.base + '/notes.txt', 'txt']]);
    assert.match(other.find((r) => r.format === 'txt').content, /lịch thi học kỳ 1/);

    const graph = await p.records('graph');
    const edge = graph.find((g) => g.src === site.base + '/' && g.dest === site.base + '/a');
    assert.ok(edge, 'có cạnh / -> /a');
    assert.ok(graph.some((g) => g.dest === 'http://example.invalid/ngoai-mien'), 'cạnh ra ngoài miền vẫn ghi vào đồ thị');

    // Không trang nào bị tải lại hai lần; trang ngoài miền không được tải
    for (const path of ['/', '/a', '/b', '/doc.pdf', '/notes.txt']) assert.equal(site.hits[path], 1, 'tải 1 lần: ' + path);
    // Cân bằng tải xoay vòng: cả hai bản Tải về đều nhận việc
    const per = Object.values(p.controller.perDownloader).map((x) => x.dispatched);
    assert.equal(per.reduce((a, b) => a + b, 0), 5);
    assert.ok(per.every((n) => n > 0), 'cả hai N5 đều được giao việc: ' + per.join(','));
  } finally {
    await p.stop();
  }
});

test('E02 một bản Tải về chết: Điều khiển bỏ qua, hệ thống vẫn thu thập đủ', async () => {
  const p = await startPipeline(site, { extraDownloaders: [`http://${L}:9`] });
  try {
    const s = await p.settle();
    assert.equal(s.html, 3);
    assert.equal(s.other, 2);
    assert.equal(p.controller.perDownloader[`http://${L}:9`].up, false);
  } finally {
    await p.stop();
  }
});

test('E03 văn bản liên kết là câu hoàn chỉnh chứa thẻ <a>', async () => {
  const p = await startPipeline(site);
  try {
    await p.settle();
    const graph = await p.records('graph');
    const e = graph.find((g) => g.src === site.base + '/' && g.dest === site.base + '/a');
    assert.ok(['Xem thông báo tuyển sinh mới nhất.', 'Thông báo mục 2'].includes(e.intro), e.intro);
    const pdf = graph.find((g) => g.dest === site.base + '/doc.pdf');
    assert.equal(pdf.intro, 'Quy chế đào tạo (PDF)');
  } finally {
    await p.stop();
  }
});
