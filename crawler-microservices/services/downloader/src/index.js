#!/usr/bin/env node
'use strict';
// Điểm chạy mô-đun Tải về (N5): node src/index.js   (cấu hình bằng biến môi trường, xem README.md)

const { createDownloader } = require('./server');

const d = createDownloader();
d.start().catch((e) => {
  console.error('Không khởi động được:', e.message);
  process.exit(1);
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => {
    await d.stop().catch(() => {});
    process.exit(0);
  });
}
