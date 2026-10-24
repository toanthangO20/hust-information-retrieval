#!/usr/bin/env node
'use strict';
// Chạy toàn bộ 6 mô-đun trên một máy (mỗi mô-đun một tiến trình, đúng cổng trong thiết kế).
//   node scripts/start-all.js [--seeds url1,url2] [--max-urls 50] [--domains hust.edu.vn] [--render auto|chrome|http] [--mongo mongodb://localhost:27017]
// Ctrl+C để dừng tất cả.

const { spawn } = require('child_process');
const path = require('path');

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, arr) => (a.startsWith('--') ? [...acc, [a.slice(2), arr[i + 1]]] : acc), []),
);
const root = path.join(__dirname, '..');
const seeds = args.seeds || 'https://hust.edu.vn/';
const H = '127.0.0.1';

const services = [
  { name: 'luu-tru  :2200', file: 'services/storage/index.js', env: { PORT: 2200, MONGO_URI: args.mongo || process.env.MONGO_URI || '' } },
  { name: 'doc      :6600', file: 'services/parser/index.js', env: { PORT: 6600, AGGREGATOR_URL: `http://${H}:3300/add` } },
  { name: 'tai-ve-1 :8800', file: 'services/downloader/src/index.js', env: { PORT: 8800, DOWNLOADER_ID: 'N5-1', PARSER_URL: `http://${H}:6600/parse`, RENDER: args.render || process.env.RENDER || 'auto' } },
  { name: 'tai-ve-2 :8801', file: 'services/downloader/src/index.js', env: { PORT: 8801, DOWNLOADER_ID: 'N5-2', PARSER_URL: `http://${H}:6600/parse`, RENDER: args.render || process.env.RENDER || 'auto' } },
  { name: 'dieu-khien:5200', file: 'services/controller/index.js', env: { PORT: 5200, DOWNLOADERS: `http://${H}:8800,http://${H}:8801` } },
  { name: 'tong-hop :3300', file: 'services/aggregator/index.js', env: { PORT: 3300, SEEDS: seeds, MAX_URLS: args['max-urls'] || 50, ALLOWED_DOMAINS: args.domains || 'hust.edu.vn', CONTROLLER_URL: `http://${H}:5200/add`, STORAGE_URL: `http://${H}:2200/add` } },
];

const children = [];
for (const s of services) {
  const child = spawn(process.execPath, [path.join(root, s.file)], { env: { ...process.env, ...Object.fromEntries(Object.entries(s.env).map(([k, v]) => [k, String(v)])) }, stdio: ['ignore', 'pipe', 'pipe'] });
  const prefix = `[${s.name}] `;
  const pipe = (stream, out) => {
    let buf = '';
    stream.on('data', (d) => {
      buf += d;
      const lines = buf.split('\n');
      buf = lines.pop();
      for (const l of lines) out.write(prefix + l + '\n');
    });
  };
  pipe(child.stdout, process.stdout);
  pipe(child.stderr, process.stderr);
  child.on('exit', (code) => console.log(prefix + 'đã dừng, mã ' + code));
  children.push(child);
}

console.log('\nĐang chạy 6 mô-đun. Xem tiến độ:');
console.log('  curl http://127.0.0.1:2200/stats     (lưu trữ)');
console.log('  curl http://127.0.0.1:8800/stats     (tải về N5)');
console.log('  curl http://127.0.0.1:5200/stats     (điều khiển)');
console.log('Ctrl+C để dừng.\n');

const stopAll = () => {
  for (const c of children) c.kill('SIGINT');
  setTimeout(() => process.exit(0), 1500);
};
process.on('SIGINT', stopAll);
process.on('SIGTERM', stopAll);
