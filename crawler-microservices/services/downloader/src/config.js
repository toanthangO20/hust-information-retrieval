'use strict';
// Cấu hình mô-đun Tải về (N5): đọc từ biến môi trường, có thể ghi đè khi gọi start(overrides).

function env(name, def) {
  const v = process.env[name];
  return v === undefined || v === '' ? def : v;
}

function loadConfig(overrides = {}) {
  const c = {
    // Định danh và cổng
    id: env('DOWNLOADER_ID', 'N5-' + require('os').hostname()),
    host: env('HOST', '0.0.0.0'),
    port: Number(env('PORT', 8800)),
    // Địa chỉ mô-đun Đọc nội dung (N1) để đẩy kết quả
    parserUrl: env('PARSER_URL', 'http://127.0.0.1:6600/parse'),
    // auto: dùng Chrome headless, lỗi khởi động thì lùi về HTTP thuần; chrome: bắt buộc Chrome; http: chỉ HTTP
    render: env('RENDER', 'auto'),
    chromePath: env('CHROME_PATH', ''),
    waitUntil: env('WAIT_UNTIL', 'networkidle2'),
    blockResources: env('BLOCK_RESOURCES', '1') !== '0', // chặn ảnh/phông/video khi render để tải nhanh hơn
    // Giới hạn
    concurrency: Number(env('CONCURRENCY', 2)),
    queueMax: Number(env('QUEUE_MAX', 20)),
    navTimeoutMs: Number(env('NAV_TIMEOUT_MS', 30000)),
    maxBytes: Number(env('MAX_BYTES', 25 * 1024 * 1024)),
    userAgent: env('USER_AGENT', 'HUST-IR-N5-Downloader/1.0 (+student project; Puppeteer)'),
    // Đẩy sang Đọc nội dung
    forwardRetries: Number(env('FORWARD_RETRIES', 3)),
    forwardTimeoutMs: Number(env('FORWARD_TIMEOUT_MS', 15000)),
    forwardBackoffMs: Number(env('FORWARD_BACKOFF_MS', 500)),
    forwardErrors: env('FORWARD_ERRORS', '0') === '1', // có đẩy cả trang lỗi (4xx/5xx) sang Đọc nội dung không
    historySize: Number(env('HISTORY_SIZE', 100)),
  };
  return Object.assign(c, overrides);
}

module.exports = { loadConfig };
