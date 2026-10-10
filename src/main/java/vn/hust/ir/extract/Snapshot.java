package vn.hust.ir.extract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import vn.hust.ir.crawler.Config;
import vn.hust.ir.crawler.Fetcher;
import vn.hust.ir.crawler.UrlNormalizer;

/**
 * P1 — Chụp HTML/tài liệu thô của mọi URL trong urls_valid.txt vào {@link RawStore}.
 * Mỗi host do đúng một luồng phụ trách (lịch thiệp theo host); các host chạy song song. Chạy lại được:
 * URL đã có status 200 (hoặc 4xx cố định) bị bỏ qua.
 */
public final class Snapshot {
    private final Config cfg;
    private final RawStore store;
    private final Fetcher fetcher;
    private final Map<String, Long> nextAllowed = new ConcurrentHashMap<>();
    private final Object globalLock = new Object();
    private long globalNext = 0;
    private final AtomicInteger done = new AtomicInteger(), ok = new AtomicInteger(), failed = new AtomicInteger();
    private final long globalDelayMs;
    public int perHostWorkers = 4;

    public Snapshot(Config cfg, RawStore store, long globalDelayMs) {
        this.cfg = cfg;
        this.store = store;
        this.fetcher = new Fetcher(cfg);
        this.globalDelayMs = globalDelayMs;
    }

    public void run(Path urlsFile, int hostThreads, int limit, Set<String> onlyHosts) throws Exception {
        List<String> urls = Files.readAllLines(urlsFile);
        Map<String, List<String>> byHost = new LinkedHashMap<>();
        int total = 0;
        for (String u : urls) {
            u = u.trim();
            if (u.isEmpty()) continue;
            String h = UrlNormalizer.host(u);
            if (!onlyHosts.isEmpty() && !onlyHosts.contains(h)) continue;
            RawStore.Entry e = store.get(u);
            if (e != null && isFinal(e)) continue;
            byHost.computeIfAbsent(h, k -> new ArrayList<>()).add(u);
            total++;
            if (limit > 0 && total >= limit) break;
        }
        System.out.println("Cần tải: " + total + " URL / " + byHost.size() + " host");
        final int totalF = total;
        ExecutorService pool = Executors.newFixedThreadPool(hostThreads);
        List<Map.Entry<String, List<String>>> order = new ArrayList<>(byHost.entrySet());
        order.sort((a, b) -> b.getValue().size() - a.getValue().size());   // host lớn nhất chạy trước
        Thread progress = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(30_000);
                    System.out.printf("  %d/%d  (ok=%d, lỗi=%d)%n", done.get(), totalF, ok.get(), failed.get());
                }
            } catch (InterruptedException ignored) { }
        });
        progress.setDaemon(true);
        progress.start();
        // host lớn chia cho nhiều luồng; khoảng cách giữa các request tới cùng host vẫn do throttle() bảo đảm
        for (Map.Entry<String, List<String>> en : order) {
            int workers = Math.max(1, Math.min(perHostWorkers, en.getValue().size() / 100 + 1));
            for (int w = 0; w < workers; w++) {
                final int wi = w, wn = workers;
                pool.submit(() -> {
                    List<String> l = en.getValue();
                    for (int i = wi; i < l.size(); i += wn) {
                        String u = l.get(i);
                        try {
                            fetchOne(u, en.getKey());
                        } catch (Throwable t) {
                            failed.incrementAndGet();
                            System.err.println("LỖI " + u + ": " + t);
                        }
                        done.incrementAndGet();
                    }
                });
            }
        }
        pool.shutdown();
        pool.awaitTermination(24, TimeUnit.HOURS);
        System.out.printf("Xong: %d URL (ok=%d, lỗi=%d)%n", done.get(), ok.get(), failed.get());
    }

    private static boolean isFinal(RawStore.Entry e) {
        return e.status == 200 || (e.status >= 400 && e.status < 500 && e.status != 429);
    }

    private void fetchOne(String url, String host) throws Exception {
        RawStore.Entry e = new RawStore.Entry();
        e.url = url;
        e.host = host;
        String cur = url;
        Fetcher.Result r = null;
        for (int hop = 0; hop < 6; hop++) {
            r = fetchWithRetry(cur);
            if (r.status >= 300 && r.status < 400 && r.location != null) {
                String next = UrlNormalizer.normalize(cur, r.location);
                if (next == null || !cfg.allowedHost(UrlNormalizer.host(next)) || next.equals(cur)) break;
                e.redirects.add(next);
                cur = next;
                continue;
            }
            break;
        }
        e.finalUrl = cur;
        e.status = r.status;
        e.contentType = r.contentType;
        e.charset = r.charset;
        e.kind = r.kind.name();
        e.lastModified = r.lastModified;
        e.error = r.error;
        e.fetchedAt = System.currentTimeMillis();
        store.put(e, r.status == 200 ? r.body : null);
        if (r.status == 200 && r.body != null) ok.incrementAndGet();
        else failed.incrementAndGet();
    }

    private Fetcher.Result fetchWithRetry(String url) throws InterruptedException {
        String host = UrlNormalizer.host(url);
        long hostDelay = cfg.hostDelayMs.getOrDefault(host, cfg.delayMs);
        Fetcher.Result r = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            throttle(host, hostDelay);
            r = fetcher.get(url);
            boolean transientFail = r.error != null || r.status == 429 || r.status >= 500 || r.status == 421;
            if (!transientFail) return r;
            long wait = r.retryAfterMs > 0 ? Math.min(r.retryAfterMs, 60_000) : (2000L << attempt);
            synchronized (nextAllowed) {
                nextAllowed.merge(host, System.currentTimeMillis() + wait, Math::max);
            }
        }
        return r;
    }

    private void throttle(String host, long delay) throws InterruptedException {
        long wait;
        synchronized (nextAllowed) {
            long now = System.currentTimeMillis();
            long t = Math.max(now, nextAllowed.getOrDefault(host, 0L));
            nextAllowed.put(host, t + delay);
            wait = t - now;
        }
        if (wait > 0) Thread.sleep(wait);
        long gw;
        synchronized (globalLock) {
            long now = System.currentTimeMillis();
            long t = Math.max(now, globalNext);
            globalNext = t + globalDelayMs;
            gw = t - now;
        }
        if (gw > 0) Thread.sleep(gw);
    }
}
