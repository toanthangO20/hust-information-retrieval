package vn.hust.ir.extract;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.hust.ir.crawler.UrlNormalizer;

/** P2–P5 — Bóc tách toàn bộ kho thô thành pages.jsonl (trang/tài liệu) và links.jsonl (đồ thị liên kết). */
public final class Extractor {
    /** Cấu hình thuật toán tìm khối. */
    public static final class Strategy {
        public String primary = "learned";          // learned | rules | rules-largest | bte | bte-link
        public boolean css = false;                 // dùng quy tắc CSS theo host nếu khớp
        public boolean template = true;             // lọc thêm khối khuôn mẫu theo host (tăng precision; chọn theo kết quả dev)
        public double templateRatio = 0.2;
        public int templateMinDf = 5;
        public double keepRatio = 0.5;
        public int maxGap = 2;

        public BlockFinder primaryFinder() {
            switch (primary) {
                case "bte": return new BlockFinder.Bte(false);
                case "bte-link": return new BlockFinder.Bte(true);
                case "rules": return new BlockFinder.Rules(false, maxGap, keepRatio);
                default: return new BlockFinder.Rules(true, maxGap, keepRatio);   // rules-largest (và "learned" khi thiếu mô hình)
            }
        }

        @Override
        public String toString() {
            return (css ? "css>" : "") + primary + (template ? "+template" : "");
        }
    }

    /** Thống kê theo host: tần suất khối (khuôn mẫu) và các giá trị metadata lặp lại ở nhiều trang (hằng số của site). */
    public static final class HostStats implements MetadataExtractor.Constants {
        public final Map<Long, Integer> df = new ConcurrentHashMap<>();
        final Map<String, Integer> titles = new ConcurrentHashMap<>();
        final Map<String, Integer> authors = new ConcurrentHashMap<>();
        final Map<String, Integer> h1s = new ConcurrentHashMap<>();
        public int pages;
        private final double ratio = 0.02;
        private final int minCount = 3;

        /** Giá trị lặp lại ở ít nhất 3 trang và 2% số trang của host => hằng số của site, không mô tả riêng một trang. */
        private boolean constant(Map<String, Integer> m, String v) {
            if (v == null || v.isBlank()) return false;
            Integer c = m.get(v.trim());
            return c != null && c >= Math.max(minCount, ratio * pages);
        }

        @Override
        public boolean title(String v) { return constant(titles, v); }

        @Override
        public boolean author(String v) { return constant(authors, v); }

        @Override
        public boolean h1(String v) { return constant(h1s, v); }
    }

    public static final class Output {
        public Map<String, Object> page = new LinkedHashMap<>();
        public List<LinkExtractor.Edge> edges = new ArrayList<>();
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RawStore store;
    private final Strategy strategy;
    private final CssRules cssRules;
    private final LogReg model;

    public Extractor(RawStore store, Strategy strategy, CssRules cssRules) {
        this.store = store;
        LogReg lr = null;
        try {
            String mp = System.getProperty("block.model");
            lr = mp != null ? LogReg.load(java.nio.file.Paths.get(mp)) : LogReg.loadResource("/extract/block_model.json");
        } catch (Exception ignored) { }
        this.model = lr;
        this.strategy = strategy;
        this.cssRules = cssRules;
    }

    /** Nội dung trả về dưới dạng HTML hay tài liệu: kiểm tra chữ ký tệp vì nhiều URL .aspx thực chất trả PDF. */
    static boolean looksBinaryDoc(byte[] body) {
        if (body.length < 5) return false;
        String head = new String(body, 0, 5, StandardCharsets.ISO_8859_1);
        return head.startsWith("%PDF") || head.startsWith("PK\u0003\u0004") || (body[0] == (byte) 0xD0 && body[1] == (byte) 0xCF);
    }

    // ----------------------------------------------------------- một trang

    public Output process(RawStore.Entry e, HostStats hs) throws Exception {
        Output o = new Output();
        Map<String, Object> p = o.page;
        byte[] body = store.read(e);
        boolean html = "HTML".equals(e.kind) && body != null && !looksBinaryDoc(body);
        p.put("_id", RawStore.sha1(e.url));
        p.put("url", e.url);
        p.put("host", e.host);
        p.put("finalUrl", e.finalUrl);
        p.put("httpStatus", e.status);
        p.put("contentType", e.contentType);
        String ct = e.contentType;
        if (!html && "HTML".equals(e.kind) && body != null) ct = sniffType(body);
        p.put("docType", html ? "html" : DocExtractor.docType(ct, e.url));
        p.put("kind", html ? "html" : "document");
        p.put("fetchedAt", e.fetchedAt);
        p.put("contentHash", e.bodySha1);
        if (e.lastModified != null) p.put("lastModified", e.lastModified);
        if (body == null) return o;

        if (html) {
            Document d = Pages.parse(store, e);
            MetadataExtractor.Result md = MetadataExtractor.structured(d);
            Element canon = d.selectFirst("link[rel=canonical]");
            if (canon != null) {
                String c = UrlNormalizer.normalize(d.baseUri(), canon.attr("href"));
                if (c != null) p.put("canonical", c);
            }
            PageModel m = new PageModel(d);
            String algo = applyFinder(m, e.host, hs, hs == null || !strategy.template ? null : template(hs));
            MetadataExtractor.fromContent(md, m, e.lastModified, e.fetchedAt, hs);
            put(p, "title", md.title);
            put(p, "publishedAt", md.date);
            put(p, "author", md.author);
            put(p, "sourceRef", md.sourceRef);
            if (md.lang != null && !md.lang.isEmpty()) p.put("lang", md.lang);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("text", m.contentText());
            content.put("words", m.contentWords());
            content.put("algo", algo);
            content.put("blocks", m.blocks.size());
            content.put("contentBlocks", (int) m.blocks.stream().filter(b -> b.content).count());
            content.put("pageWords", m.totalWords());
            content.put("root", PageModel.describe(m.contentRoot()));
            p.put("content", content);
            o.edges = LinkExtractor.extract(m, d.baseUri(), e.url);
            p.put("outlinks", o.edges.size());
        } else {
            DocExtractor.Result r = DocExtractor.extract(body, ct, e.url);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("text", r.text);
            content.put("words", r.text.isEmpty() ? 0 : r.text.split("\\s+").length);
            content.put("algo", "tika");
            p.put("content", content);
            if (!r.title.isEmpty()) p.put("title", field(r.title, "tika:title", 0.7));
            if (!r.author.isEmpty()) p.put("author", field(r.author, "tika:creator", 0.7));
            if (!r.created.isEmpty()) p.put("publishedAt", field(r.created, "tika:created", 0.7));
            if (r.pages > 0) p.put("pages", r.pages);
            if (r.error != null) p.put("parseError", r.error);
        }
        return o;
    }

    private static String sniffType(byte[] body) {
        String head = new String(body, 0, Math.min(5, body.length), StandardCharsets.ISO_8859_1);
        return head.startsWith("%PDF") ? "application/pdf" : "application/octet-stream";
    }

    private BlockFinder.Template template(HostStats hs) {
        return new BlockFinder.Template(hs.df, hs.pages, strategy.templateRatio, strategy.templateMinDf);
    }

    private String applyFinder(PageModel m, String host, HostStats hs, BlockFinder.Template tpl) {
        String algo;
        BlockFinder.Css css = strategy.css ? cssRules.forHost(host) : null;
        if (css != null && css.matched(m)) {
            css.apply(m);
            algo = "css";
        } else if ("learned".equals(strategy.primary) && model != null) {
            new BlockFinder.Learned(model, hs).apply(m);
            algo = "learned";
        } else {
            BlockFinder f = strategy.primaryFinder();
            f.apply(m);
            algo = f.name();
        }
        if (tpl != null) {
            boolean[] before = new boolean[m.blocks.size()];
            for (int i = 0; i < before.length; i++) before[i] = m.blocks.get(i).content;
            tpl.apply(m);
            if (m.contentWords() == 0) {            // an toàn: không để trang trống chỉ vì mọi khối đều lặp
                for (int i = 0; i < before.length; i++) m.blocks.get(i).content = before[i];
            } else {
                algo += "+tpl";
            }
        }
        return algo;
    }

    private static void put(Map<String, Object> p, String k, MetadataExtractor.Field f) {
        if (f != null && f.value != null && !f.value.isBlank()) p.put(k, f.toMap());
    }

    private static Map<String, Object> field(String v, String s, double c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v);
        m.put("source", s);
        m.put("conf", c);
        return m;
    }

    // ----------------------------------------------------------- toàn bộ kho

    /** Thống kê theo host từ các trang HTML: tần suất khối (khuôn mẫu), tiêu đề/h1/tác giả lặp lại. */
    public HostStats hostStats(List<RawStore.Entry> pages, int threads) throws Exception {
        HostStats hs = new HostStats();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        final AtomicInteger n = new AtomicInteger();
        for (RawStore.Entry e : pages) {
            fs.add(pool.submit(() -> {
                try {
                    byte[] body = store.read(e);
                    if (body == null || looksBinaryDoc(body)) return;
                    Document d = Pages.parse(store, e);
                    MetadataExtractor.Result md = MetadataExtractor.structured(d);
                    count(hs.titles, md.htmlTitle);
                    count(hs.titles, md.ogTitle);
                    count(hs.h1s, md.headline);
                    if (md.author != null) count(hs.authors, md.author.value);
                    PageModel m = new PageModel(d);
                    Set<Long> seen = new HashSet<>();
                    for (PageModel.Block b : m.blocks)
                        seen.add(PageModel.hash64(PageModel.normalizeForHash(b.text)));
                    for (long h : seen) hs.df.merge(h, 1, Integer::sum);
                    n.incrementAndGet();
                } catch (Exception ignored) { }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        hs.pages = Math.max(1, n.get());
        return hs;
    }

    private static void count(Map<String, Integer> m, String v) {
        if (v != null && !v.isBlank()) m.merge(v.trim(), 1, Integer::sum);
    }

    public void run(Path outDir, int threads, Set<String> onlyHosts) throws Exception {
        Files.createDirectories(outDir);
        Map<String, List<RawStore.Entry>> byHost = new LinkedHashMap<>();
        for (RawStore.Entry e : store.all())
            if (e.status == 200 && e.file != null && (onlyHosts.isEmpty() || onlyHosts.contains(e.host)))
                byHost.computeIfAbsent(e.host, h -> new ArrayList<>()).add(e);
        System.out.println("Chiến lược: " + strategy + "; " + byHost.size() + " host");
        AtomicInteger n = new AtomicInteger(), err = new AtomicInteger();
        Map<String, Integer> algoCount = new ConcurrentHashMap<>();
        try (BufferedWriter pw = Files.newBufferedWriter(outDir.resolve("pages.jsonl"), StandardCharsets.UTF_8);
             BufferedWriter lw = Files.newBufferedWriter(outDir.resolve("links.jsonl"), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, List<RawStore.Entry>> en : byHost.entrySet()) {
                List<RawStore.Entry> pages = en.getValue();
                List<RawStore.Entry> htmlPages = new ArrayList<>();
                for (RawStore.Entry e : pages) if ("HTML".equals(e.kind)) htmlPages.add(e);
                HostStats hs = htmlPages.size() >= 3 ? hostStats(htmlPages, threads) : null;
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                List<Future<Output>> fs = new ArrayList<>();
                for (RawStore.Entry e : pages) fs.add(pool.submit(() -> process(e, hs)));
                for (int i = 0; i < pages.size(); i++) {
                    try {
                        Output o = fs.get(i).get();
                        Map<String, Object> content = castMap(o.page.get("content"));
                        if (content != null) algoCount.merge(String.valueOf(content.get("algo")), 1, Integer::sum);
                        pw.write(JSON.writeValueAsString(o.page));
                        pw.newLine();
                        String src = (String) o.page.get("url");
                        for (LinkExtractor.Edge ed : o.edges) {
                            Map<String, Object> l = new LinkedHashMap<>();
                            l.put("src", src);
                            l.put("dst", ed.dst);
                            l.put("kind", ed.kind);
                            l.put("anchor", ed.anchor);
                            if (!ed.alt.isEmpty()) l.put("alt", ed.alt);
                            if (!ed.title.isEmpty()) l.put("title", ed.title);
                            if (!ed.caption.isEmpty()) l.put("caption", ed.caption);
                            l.put("inContent", ed.inContent);
                            if (ed.nofollow) l.put("nofollow", true);
                            if (ed.count > 1) l.put("count", ed.count);
                            lw.write(JSON.writeValueAsString(l));
                            lw.newLine();
                        }
                    } catch (Exception ex) {
                        err.incrementAndGet();
                        System.err.println("LỖI " + pages.get(i).url + ": " + ex);
                    }
                    n.incrementAndGet();
                }
                pool.shutdown();
                pool.awaitTermination(1, TimeUnit.HOURS);
                System.out.printf("  %-34s %6d trang (tổng %d)%n", en.getKey(), pages.size(), n.get());
            }
        }
        System.out.println("Xong: " + n.get() + " trang, lỗi " + err.get() + "; thuật toán: " + algoCount);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
