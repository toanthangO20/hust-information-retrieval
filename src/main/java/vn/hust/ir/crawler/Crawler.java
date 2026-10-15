package vn.hust.ir.crawler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Điều phối crawl. Cùng một luồng xử lý cho cả lần crawl đầu và các lần cập nhật:
 * kho URL (Lucene) cho biết URL nào đã biết/đang chờ/đã lỗi, từ đó chỉ tải URL mới hoặc URL đã "cũ".
 */
public final class Crawler {
    private static final long DAY = 24L * 3600 * 1000;
    // [DEMO:soft-404] mẫu trang "không tìm thấy"
    private static final Pattern SOFT_404 = Pattern.compile(
            "(?i)(\\b404\\b|không tìm thấy|khong tim thay|page not found|not found|trang không tồn tại)");

    private final Config cfg;
    private final UrlStore store;
    private final Map<String, UrlRecord> records;
    private final Fetcher fetcher;
    private final RobotsCache robots;
    private final ContentExtractor extractor = new ContentExtractor();
    private Frontier frontier = new Frontier();

    private final Map<String, String> hashOwner = new HashMap<>();          // contentHash -> url "gốc"
    private final Map<String, Long> simhashes = new HashMap<>();            // url hợp lệ -> simhash
    private final Map<String, AtomicInteger> hostFetched = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hostFailures = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hostOk = new ConcurrentHashMap<>();
    private final Map<String, Long> hostBackoff = new ConcurrentHashMap<>();
    private final Map<String, Integer> retries = new ConcurrentHashMap<>();
    private final Set<String> deadHosts = ConcurrentHashMap.newKeySet();

    private final AtomicInteger fetchedTotal = new AtomicInteger();
    private final AtomicInteger validNow = new AtomicInteger();
    private final AtomicInteger invalidNow = new AtomicInteger();
    private final AtomicInteger discovered = new AtomicInteger();
    private final AtomicInteger throttled = new AtomicInteger();
    public long startedAt = System.currentTimeMillis();

    public Crawler(Config cfg, UrlStore store, Map<String, UrlRecord> records) {
        this.cfg = cfg;
        this.store = store;
        this.records = records;
        this.fetcher = new Fetcher(cfg);
        this.robots = new RobotsCache(fetcher);
        this.globalDelay = cfg.globalDelayMs;
        for (UrlRecord r : records.values()) {
            if (r.state == UrlRecord.VALID) {
                hashOwner.putIfAbsent(r.contentHash, r.url);
                simhashes.put(r.url, r.simhash);
            }
        }
    }

    public Map<String, UrlRecord> records() {
        return records;
    }

    public void run() throws Exception {
        seedFrontier();
        log("Bắt đầu: %d bản ghi đã biết, %d URL trong hàng đợi, %d luồng", records.size(), frontier.size(), cfg.threads);
        runWorkers();

        // Lượt bổ sung: URL bị loại vì trùng với một URL mà chính lượt này đã làm đổi nội dung (hoặc không còn hợp lệ)
        // có thể đã bị xét theo trạng thái cũ của URL gốc, nên phải xét lại ngay thay vì chờ lần cập nhật sau.
        List<String> retry = new ArrayList<>();
        for (UrlRecord r : records.values()) {
            if (r.state != UrlRecord.INVALID || !r.reason.startsWith("duplicate:")) continue;
            UrlRecord owner = records.get(r.reason.substring("duplicate:".length()));
            if (owner != null && owner.lastCrawled >= startedAt
                    && (owner.state != UrlRecord.VALID || !owner.contentHash.equals(r.contentHash))) retry.add(r.url);
        }
        if (!retry.isEmpty() && fetchedTotal.get() < cfg.maxPages) {
            frontier = new Frontier();
            for (String u : retry) enqueue(u, 0);
            log("Xét lại %d URL từng bị coi là trùng với URL đã thay đổi", retry.size());
            runWorkers();
        }
        store.commit();
        log("Kết thúc: đã tải %d trang (hợp lệ %d, không hợp lệ %d), phát hiện %d URL mới",
                fetchedTotal.get(), validNow.get(), invalidNow.get(), discovered.get());
    }

    private void runWorkers() throws Exception {
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads; i++) {
            Thread t = new Thread(this::workerLoop, "crawler-" + i);
            t.start();
            workers.add(t);
        }
        long lastLog = System.currentTimeMillis();
        for (Thread t : workers) {
            while (t.isAlive()) {
                t.join(5000);
                if (System.currentTimeMillis() - lastLog > 15000) {
                    lastLog = System.currentTimeMillis();
                    log("đã tải %d | hợp lệ %d | không hợp lệ %d | URL mới phát hiện %d | hàng đợi %d | bị giới hạn tốc độ (429) %d",
                            fetchedTotal.get(), validNow.get(), invalidNow.get(), discovered.get(), frontier.size(), throttled.get());
                    store.commit();
                }
            }
        }
    }

    // ------------------------------------------------------------------ nạp hàng đợi

    private void seedFrontier() {
        long now = System.currentTimeMillis();
        Set<String> hosts = new HashSet<>();

        // 1) seed: luôn tải lại (ưu tiên cao nhất) vì đây là các điểm vào của từng host
        for (String s : cfg.seeds) {
            String n = UrlNormalizer.normalize(null, s.trim());
            if (n == null || !cfg.allowedHost(UrlNormalizer.host(n))) continue;
            hosts.add(UrlNormalizer.host(n));
            UrlRecord r = records.get(n);
            if (r == null) r = register(n, 0, false);
            if (r != null) enqueue(n, 0);
        }
        for (UrlRecord r : records.values()) hosts.add(r.host);

        // 2) trang "hub" (nhiều liên kết ra, ví dụ trang chủ/danh mục/tin mới): tải lại thường xuyên để thấy URL mới
        List<UrlRecord> hubs = new ArrayList<>();
        for (UrlRecord r : records.values())
            if (r.state != UrlRecord.PENDING && r.outlinks >= cfg.hubMinLinks && r.lastCrawled > 0
                    && now - r.lastCrawled > cfg.hubRevisitHours * 3600_000L) hubs.add(r);
        hubs.sort(Comparator.comparingInt((UrlRecord r) -> -r.outlinks));
        for (UrlRecord r : hubs.subList(0, Math.min(cfg.hubLimit, hubs.size()))) enqueue(r.url, 0);

        // 3) sitemap: URL mới, hoặc lastmod mới hơn lần tải gần nhất
        if (cfg.useSitemaps) {
            SitemapReader sr = new SitemapReader(fetcher, cfg.delayMs);
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, cfg.threads / 2));
            for (String host : new ArrayList<>(hosts)) {
                if (!cfg.allowedHost(host)) continue;
                if (!cfg.onlyHosts.isEmpty() && !cfg.onlyHosts.contains(host)) continue;
                pool.submit(() -> scanSitemaps(host, sr));
            }
            pool.shutdown();
            try {
                pool.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 4) các URL còn dang dở hoặc đã "cũ" theo chu kỳ tải lại
        for (UrlRecord r : records.values()) {
            if (!cfg.allowedHost(r.host)) continue;
            if (r.state == UrlRecord.PENDING) {
                enqueue(r.url, r.priority);
            } else if (isStale(r, now)) {
                enqueue(r.url, rechecked(r) ? 0 : 2);
            }
        }
    }

    /** Đọc sitemap của một host: đăng ký URL mới, xếp lại hàng đợi URL có lastmod mới hơn lần tải gần nhất. */
    private void scanSitemaps(String host, SitemapReader sr) {
        try {
            {
                Set<String> maps = new HashSet<>();
                maps.add("https://" + host + "/sitemap.xml");
                try {
                    maps.addAll(robots.forHost("https", host).sitemaps);
                } catch (Exception ignored) { }
                int added = 0, changed = 0;
                for (String m : maps) {
                    for (SitemapReader.Entry e : sr.read(m)) {
                        String n = UrlNormalizer.normalize(null, e.url);
                        if (n == null || !cfg.allowedHost(UrlNormalizer.host(n)) || !UrlNormalizer.crawlable(n)) continue;
                        UrlRecord r = records.get(n);
                        if (r == null) {
                            r = register(n, 1, true);
                            if (r != null) {
                                r.lastmod = e.lastmod;
                                added++;
                            }
                        } else if (e.lastmod > r.lastmod) {
                            r.lastmod = e.lastmod;
                            if (r.state != UrlRecord.PENDING && e.lastmod > r.lastCrawled) {
                                enqueue(n, 1);
                                changed++;
                            }
                        }
                    }
                }
                if (added + changed > 0) log("sitemap %s: %d URL mới, %d URL thay đổi", host, added, changed);
            }
        } catch (RuntimeException e) {
            log("lỗi đọc sitemap %s: %s", host, e);
        }
    }

    /** Đưa URL vào hàng đợi, trừ khi bị giới hạn bởi --only-hosts (URL vẫn được lưu là PENDING cho lần sau). */
    private void enqueue(String url, int priority) {
        if (!cfg.onlyHosts.isEmpty() && !cfg.onlyHosts.contains(UrlNormalizer.host(url))) return;
        frontier.add(url, priority);
    }

    private boolean rechecked(UrlRecord r) {
        for (String rr : cfg.recheckReasons) if (!rr.isEmpty() && r.reason.startsWith(rr)) return true;
        return false;
    }

    private boolean isStale(UrlRecord r, long now) {
        if (rechecked(r)) return true;
        if (r.reason.equals("robots-disallow") || r.reason.startsWith("redirect-out")) return false;
        long age = now - r.lastCrawled;
        if (r.isTransientFailure()) return age > DAY;
        return age > cfg.revisitDays * DAY;
    }

    /** Đăng ký URL mới (URL-seen test); trả về null nếu đã có. */
    private UrlRecord register(String url, int priority, boolean enqueue) {
        UrlRecord r = new UrlRecord(url, UrlNormalizer.host(url), priority);
        if (records.putIfAbsent(url, r) != null) return null;
        store.save(r, null);
        discovered.incrementAndGet();
        if (enqueue) enqueue(url, priority);
        return r;
    }

    private boolean discover(String url) {
        if (!cfg.allowedHost(UrlNormalizer.host(url)) || !UrlNormalizer.crawlable(url)) return false;
        int prio = url.contains("?") ? 2 : 1;
        return register(url, prio, true) != null;
    }

    // ------------------------------------------------------------------ vòng lặp worker

    private void workerLoop() {
        try {
            Frontier.Task t;
            while ((t = frontier.take()) != null) {
                long delay = hostDelay(t.host);
                try {
                    if (!process(t)) delay = 0;      // bỏ qua mà không gửi request thì không cần chờ
                } catch (Throwable e) {
                    UrlRecord r = records.get(t.url);
                    if (r != null) finish(r, UrlRecord.INVALID, 0, "error:" + e.getClass().getSimpleName(), null);
                } finally {
                    frontier.release(t, delay);
                }
                if (fetchedTotal.get() >= cfg.maxPages) frontier.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final Object slotLock = new Object();
    private long nextSlot;
    private final long globalDelay;

    /** Giới hạn tốc độ toàn cục: server của trường trả 429 khi tổng tải vượt ngưỡng dù mỗi host đã được giãn cách. */
    private void awaitGlobalSlot() {
        long wait;
        synchronized (slotLock) {
            long now = System.currentTimeMillis();
            long at = Math.max(now, nextSlot);
            nextSlot = at + globalDelay;
            wait = at - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private long hostDelay(String host) {
        long d = Math.max(cfg.delayMs, cfg.hostDelayMs.getOrDefault(host, 0L));
        try {
            long cd = robots.forHost("https", host).crawlDelayMs;
            d = Math.max(d, Math.min(cd, 10_000));
        } catch (Exception ignored) { }
        return Math.max(d, hostBackoff.getOrDefault(host, 0L));
    }

    /** @return true nếu đã gửi request tới server. */
    private boolean process(Frontier.Task t) {
        UrlRecord rec = records.get(t.url);
        if (rec == null || deadHosts.contains(t.host)) return false;
        if (hostFetched.computeIfAbsent(t.host, h -> new AtomicInteger()).get() >= cfg.maxPerHost) return false;
        if (!robots.allowed(t.url)) {
            finish(rec, UrlRecord.INVALID, 0, "robots-disallow", null);
            return false;
        }
        hostFetched.get(t.host).incrementAndGet();
        fetchedTotal.incrementAndGet();
        rec.lastCrawled = System.currentTimeMillis();

        awaitGlobalSlot();
        Fetcher.Result r = fetcher.get(t.url);
        if (r.error != null) {
            int f = hostFailures.computeIfAbsent(t.host, h -> new AtomicInteger()).incrementAndGet();
            int ok = hostOk.computeIfAbsent(t.host, h -> new AtomicInteger()).get();
            if (ok == 0 && f >= 5) {
                deadHosts.add(t.host);
                log("host bị loại (liên tục lỗi kết nối): %s", t.host);
            }
            finish(rec, UrlRecord.INVALID, 0, "error:" + r.error, null);
            return true;
        }
        hostOk.computeIfAbsent(t.host, h -> new AtomicInteger()).incrementAndGet();
        hostFailures.computeIfAbsent(t.host, h -> new AtomicInteger()).set(0);
        rec.contentType = r.contentType.split(";")[0];

        int s = r.status;
        // [DEMO:backoff-429] AIMD theo host khi 429/503
        if (s == 429 || s == 503 || s == 421) {
            // lỗi tạm thời; thử lại tối đa 3 lần trong cùng lần chạy
            if (s != 421) {
                // 429/503 - AIMD theo từng host: nhân đôi khoảng chờ (tối thiểu bằng Retry-After, tối đa 10s), giảm một nửa sau mỗi lần thành công
                throttled.incrementAndGet();
                long cur = Math.max(cfg.delayMs, hostBackoff.getOrDefault(t.host, 0L));
                hostBackoff.put(t.host, Math.min(10_000, Math.max(cur * 2, Math.min(r.retryAfterMs, 10_000))));
            }
            if (retries.merge(t.url, 1, Integer::sum) <= 3) {
                enqueue(t.url, rec.priority);
                return true;
            }
        } else if (s == 200) {
            // giảm dần khoảng chờ khi host không còn chặn
            hostBackoff.computeIfPresent(t.host, (k, v) -> v / 2 <= cfg.delayMs ? null : v / 2);
        }
        if (s >= 300 && s < 400) {
            String next = r.location == null ? null : UrlNormalizer.normalize(t.url, r.location);
            if (next == null || !cfg.allowedHost(UrlNormalizer.host(next)) || !UrlNormalizer.crawlable(next)) {
                finish(rec, UrlRecord.INVALID, s, "redirect-out-of-scope", null);
            } else if (next.equals(t.url)) {
                finish(rec, UrlRecord.INVALID, s, "redirect-loop", null);
            } else {
                finish(rec, UrlRecord.INVALID, s, "redirect:" + next, null);
                discover(next);
            }
            return true;
        }
        if (s != 200) {
            finish(rec, UrlRecord.INVALID, s, "http-" + s, null);
            return true;
        }
        if (r.kind == Fetcher.Kind.OTHER || r.body == null) {
            finish(rec, UrlRecord.INVALID, s, "unsupported-type", null);
            return true;
        }

        ContentExtractor.Result ex;
        try {
            ex = r.kind == Fetcher.Kind.HTML
                    ? extractor.extractHtml(r.body, r.charset, t.url)
                    : extractor.extractDocument(r.body, r.contentType, t.url);
        } catch (Throwable e) {
            finish(rec, UrlRecord.INVALID, s, "parse-error", null);
            return true;
        }
        rec.title = ex.title;
        rec.outlinks = ex.links.size();
        if (!ex.nofollow) for (String l : ex.links) discover(l);

        evaluate(rec, ex, s);
        return true;
    }

    /** Áp dụng các tiêu chí lọc: có nội dung, không phải soft-404, không trùng lặp. */
    private void evaluate(UrlRecord rec, ContentExtractor.Result ex, int status) {
        String text = ex.text;
        String[] tok = SimHash.tokens(text);
        rec.textLen = text.length();

        if (ex.noindex) { finish(rec, UrlRecord.INVALID, status, "noindex", null); return; }
        // [DEMO:min-content] ngưỡng "có nội dung"
        if (text.length() < cfg.minChars || tok.length < cfg.minWords) {
            finish(rec, UrlRecord.INVALID, status, "no-content", null);
            return;
        }
        if (text.length() < 1000 && (SOFT_404.matcher(rec.title).find()
                || SOFT_404.matcher(text.substring(0, Math.min(300, text.length()))).find())) {
            finish(rec, UrlRecord.INVALID, status, "soft-404", null);
            return;
        }
        if (ex.canonical != null && !UrlNormalizer.schemeless(ex.canonical).equals(UrlNormalizer.schemeless(rec.url))) {
            UrlRecord target = records.get(ex.canonical);
            String path = URI.create(ex.canonical).getRawPath();
            if (target != null && target.state == UrlRecord.VALID && path != null && path.length() > 1
                    && cfg.allowedHost(target.host)) {
                finish(rec, UrlRecord.INVALID, status, "canonical-dup:" + ex.canonical, null);
                return;
            }
        }

        // [DEMO:dup-sha1] trùng chính xác
        String hash = sha1(text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
        long sim = SimHash.compute(tok);
        String nearDup = "";
        synchronized (hashOwner) {
            String owner = hashOwner.get(hash);
            UrlRecord ownerRec = owner == null ? null : records.get(owner);
            if (owner != null && !owner.equals(rec.url) && ownerRec != null && ownerRec.state == UrlRecord.VALID) {
                rec.contentHash = hash;
                finish(rec, UrlRecord.INVALID, status, "duplicate:" + owner, null);
                return;
            }
            // [DEMO:dup-simhash] trùng gần đúng, quét tuyến tính (hạn chế)
            for (Map.Entry<String, Long> e : simhashes.entrySet()) {
                if (!e.getKey().equals(rec.url) && SimHash.hamming(sim, e.getValue()) <= 3) {
                    nearDup = e.getKey();
                    break;
                }
            }
            // [DEMO:drop-near-dup] chỉ loại khi bật --drop-near-dup
            if (!nearDup.isEmpty() && cfg.dropNearDup) {
                rec.contentHash = hash;
                finish(rec, UrlRecord.INVALID, status, "near-duplicate:" + nearDup, null);
                return;
            }
            if (!rec.contentHash.equals(hash) && hashOwner.get(rec.contentHash) != null
                    && hashOwner.get(rec.contentHash).equals(rec.url)) hashOwner.remove(rec.contentHash);
            hashOwner.put(hash, rec.url);
            simhashes.put(rec.url, sim);
        }
        if (!rec.contentHash.equals(hash)) rec.lastChanged = System.currentTimeMillis();
        rec.contentHash = hash;
        rec.simhash = sim;
        rec.nearDup = nearDup;
        finish(rec, UrlRecord.VALID, status, "", text);
    }

    private void finish(UrlRecord rec, int state, int http, String reason, String text) {
        if (rec.state == UrlRecord.VALID && state != UrlRecord.VALID) {
            synchronized (hashOwner) {
                simhashes.remove(rec.url);
                if (rec.url.equals(hashOwner.get(rec.contentHash))) hashOwner.remove(rec.contentHash);
            }
        }
        rec.state = state;
        rec.httpStatus = http;
        rec.reason = reason;
        if (state == UrlRecord.VALID) validNow.incrementAndGet();
        else invalidNow.incrementAndGet();
        store.save(rec, text);
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static synchronized void log(String fmt, Object... a) {
        System.out.println(java.time.LocalTime.now().withNano(0) + "  " + String.format(fmt, a));
    }
}
