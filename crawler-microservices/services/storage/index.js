#!/usr/bin/env node
'use strict';
// Mô-đun Lưu trữ (N10) — bản tham chiếu để chạy thử toàn hệ thống.
//   POST /add {url, format, title, content, intro, out-links}
//   GET  /stats, GET /records?collection=html|other|graph&limit=20
// Lưu theo cấu trúc trong thiết kế: collection html, other, graph.
// Có MONGO_URI -> MongoDB; không có -> lưu tệp JSON trong data/storage (để chạy thử không cần Mongo).

const fs = require('fs');
const path = require('path');
const { log, createJsonServer, listen, close, env } = require('../common/http');

class FileBackend {
  constructor(dir) {
    this.dir = dir;
    this.cols = { html: new Map(), other: new Map(), graph: new Map() };
    this.timer = null;
  }
  async init() {
    if (!this.dir) return;
    fs.mkdirSync(this.dir, { recursive: true });
    for (const c of Object.keys(this.cols)) {
      const f = path.join(this.dir, c + '.json');
      if (fs.existsSync(f)) for (const d of JSON.parse(fs.readFileSync(f, 'utf8'))) this.cols[c].set(this.key(c, d), d);
    }
  }
  key(c, d) {
    return c === 'graph' ? d.src + '\u0001' + d.dest : d.url;
  }
  async upsert(c, d) {
    this.cols[c].set(this.key(c, d), d);
    this.flushSoon();
  }
  flushSoon() {
    if (!this.dir || this.timer) return;
    this.timer = setTimeout(() => this.flush(), 300);
  }
  flush() {
    clearTimeout(this.timer);
    this.timer = null;
    if (!this.dir) return;
    for (const [c, m] of Object.entries(this.cols)) fs.writeFileSync(path.join(this.dir, c + '.json'), JSON.stringify([...m.values()], null, 1));
  }
  async count(c) {
    return this.cols[c].size;
  }
  async find(c, limit) {
    return [...this.cols[c].values()].slice(0, limit);
  }
  async close() {
    this.flush();
  }
}

class MongoBackend {
  constructor(uri, dbName) {
    this.uri = uri;
    this.dbName = dbName;
  }
  async init() {
    const { MongoClient } = require('mongodb');
    this.client = new MongoClient(this.uri, { serverSelectionTimeoutMS: 5000 });
    await this.client.connect();
    this.db = this.client.db(this.dbName);
    await this.db.collection('html').createIndex({ url: 1 }, { unique: true });
    await this.db.collection('other').createIndex({ url: 1 }, { unique: true });
    await this.db.collection('graph').createIndex({ src: 1, dest: 1 }, { unique: true });
  }
  async upsert(c, d) {
    const filter = c === 'graph' ? { src: d.src, dest: d.dest } : { url: d.url };
    await this.db.collection(c).replaceOne(filter, d, { upsert: true });
  }
  async count(c) {
    return this.db.collection(c).countDocuments();
  }
  async find(c, limit) {
    return this.db.collection(c).find({}, { projection: { _id: 0 } }).limit(limit).toArray();
  }
  async close() {
    await this.client?.close();
  }
}

function createStorage(o = {}) {
  const cfg = {
    port: Number(o.port ?? env('PORT', 2200)),
    host: o.host ?? env('HOST', '0.0.0.0'),
    mongoUri: o.mongoUri ?? env('MONGO_URI', ''),
    mongoDb: o.mongoDb ?? env('MONGO_DB', 'hust_crawl'),
    dataDir: o.dataDir !== undefined ? o.dataDir : env('DATA_DIR', path.join(__dirname, '..', '..', 'data', 'storage')),
    name: 'lưu trữ',
    quiet: o.quiet,
  };
  const L = (...a) => !cfg.quiet && log(cfg.name, ...a);
  const db = cfg.mongoUri ? new MongoBackend(cfg.mongoUri, cfg.mongoDb) : new FileBackend(cfg.dataDir);

  const routes = {
    'POST /add': async (r) => {
      if (!r || typeof r.url !== 'string') return [400, { status: 'lỗi', error: 'cần url' }];
      const fetchedAt = new Date().toISOString();
      if (r.format === 'html') {
        await db.upsert('html', { url: r.url, format: 'html', title: r.title || '', content: r.content || '', intro: r.intro || '', stored_at: fetchedAt });
      } else {
        await db.upsert('other', { url: r.url, format: r.format || 'other', title: r.title || '', content: r.content || '', stored_at: fetchedAt });
      }
      let edges = 0;
      for (const l of Array.isArray(r['out-links']) ? r['out-links'] : []) {
        const dest = typeof l === 'string' ? l : l && l.url;
        if (!dest) continue;
        await db.upsert('graph', { src: r.url, dest, intro: (l && l.text) || '' });
        edges++;
      }
      L(`lưu ${r.url} (${r.format}), ${edges} cạnh`);
      return [200, { status: 'ok', edges }];
    },
    'GET /stats': async () => [200, { status: 'ok', backend: cfg.mongoUri ? 'mongo' : 'file', html: await db.count('html'), other: await db.count('other'), graph: await db.count('graph') }],
    'GET /records': async (_, { query }) => {
      const c = query.get('collection') || 'html';
      if (!['html', 'other', 'graph'].includes(c)) return [400, { status: 'lỗi', error: 'collection: html|other|graph' }];
      return [200, { status: 'ok', collection: c, records: await db.find(c, Number(query.get('limit') || 20)) }];
    },
  };
  const server = createJsonServer(cfg.name, routes);
  return {
    cfg,
    server,
    db,
    async start() {
      await db.init();
      cfg.port = await listen(server, cfg.port, cfg.host);
      L(`nghe tại :${cfg.port}, backend: ${cfg.mongoUri ? 'MongoDB ' + cfg.mongoDb : 'tệp ' + (cfg.dataDir || '(bộ nhớ)')}`);
      return this;
    },
    async stop() {
      await close(server);
      await db.close();
    },
  };
}

if (require.main === module) createStorage().start();
module.exports = { createStorage };
