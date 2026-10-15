package vn.hust.ir.extract;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import vn.hust.ir.crawler.ContentExtractor;
import vn.hust.ir.extract.PageModel.Block;

/**
 * Đánh giá thuật toán tìm khối nội dung trên tập vàng (nhãn mức khối, tính theo số từ) và metadata (tiêu đề/ngày/tác giả).
 * Tệp vàng TSV: {@code url, nblocks, khoảng khối nội dung (vd. 2-9,12), tiêu đề, ngày (yyyy-MM-dd hoặc -), tác giả (hoặc -)}.
 */
public final class BlockEval {
    static final class Gold {
        String url;
        int nblocks;
        Set<Integer> content = new HashSet<>();
        String title, date, author;
    }

    static List<Gold> loadGold(Path f) throws Exception {
        List<Gold> out = new ArrayList<>();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] c = l.split("\t", -1);
            Gold g = new Gold();
            g.url = c[0].trim();
            g.nblocks = Integer.parseInt(c[1].trim());
            String rg = c[2].trim();
            if (!rg.equals("-") && !rg.isEmpty())
                for (String part : rg.split(",")) {
                    String[] ab = part.trim().split("-");
                    int a = Integer.parseInt(ab[0].trim()), b = ab.length > 1 ? Integer.parseInt(ab[1].trim()) : a;
                    for (int i = a; i <= b; i++) g.content.add(i);
                }
            g.title = c.length > 3 ? c[3].trim() : "-";
            g.date = c.length > 4 ? c[4].trim() : "-";
            g.author = c.length > 5 ? c[5].trim() : "-";
            out.add(g);
        }
        return out;
    }

    /** Một cấu hình đánh giá: tên + hàm gán nhãn content cho mô hình trang (host, mô hình, thân HTML thô). */
    interface Config {
        String name();

        void apply(PageModel m, String host, RawStore.Entry e, byte[] body) throws Exception;
    }

    static final class Counts {
        double tp, pred, gold;
        double sumP, sumR, sumF;
        int pages;

        void add(double tp, double pred, double gold) {
            this.tp += tp;
            this.pred += pred;
            this.gold += gold;
            double p = pred == 0 ? (gold == 0 ? 1 : 0) : tp / pred;
            double r = gold == 0 ? (pred == 0 ? 1 : 0) : tp / gold;
            sumP += p;
            sumR += r;
            sumF += (p + r) == 0 ? 0 : 2 * p * r / (p + r);
            pages++;
        }

        double microP() { return pred == 0 ? 0 : tp / pred; }
        double microR() { return gold == 0 ? 0 : tp / gold; }
        double microF() { double p = microP(), r = microR(); return p + r == 0 ? 0 : 2 * p * r / (p + r); }
    }

    private static String norm(String s) {
        return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Cấu hình dùng thuật toán đã chọn cùng bộ lọc khuôn mẫu theo host (df tính từ toàn bộ trang của host). */
    static List<Config> configs(RawStore store, CssRules rules, Map<String, Map<Long, Integer>> dfByHost,
                                Map<String, Integer> pagesByHost, Extractor.Strategy base,
                                Map<String, Extractor.HostStats> statsByHost, LogReg model) {
        List<Config> out = new ArrayList<>();
        out.add(cfg("A:baseline(jsoup link-density)", (m, h, e, body) -> {
            ContentExtractor.Result r = new ContentExtractor().extractHtml(body, e.charset, e.finalUrl);
            String t = norm(r.text);
            for (Block b : m.blocks) b.content = t.contains(norm(b.text));
        }));
        out.add(cfg("B1:bte", (m, h, e, b) -> new BlockFinder.Bte(false).apply(m)));
        out.add(cfg("B2:bte-link", (m, h, e, b) -> new BlockFinder.Bte(true).apply(m)));
        out.add(cfg("C1:rules", (m, h, e, b) -> new BlockFinder.Rules(false, 2, 0.5).apply(m)));
        out.add(cfg("C2:rules-largest", (m, h, e, b) -> new BlockFinder.Rules(true, base.maxGap, base.keepRatio).apply(m)));
        out.add(cfg("E:css(host rules; else rules-largest)", (m, h, e, b) -> {
            BlockFinder.Css c = rules.forHost(h);
            if (c != null && c.matched(m)) c.apply(m);
            else new BlockFinder.Rules(true, base.maxGap, base.keepRatio).apply(m);
        }));
        for (String prim : new String[]{"B1:bte", "B2:bte-link", "C1:rules", "C2:rules-largest", "E:css"}) {
            final String pn = prim;
            out.add(cfg("D:" + prim + "+template", (m, h, e, b) -> {
                switch (pn) {
                    case "B1:bte": new BlockFinder.Bte(false).apply(m); break;
                    case "B2:bte-link": new BlockFinder.Bte(true).apply(m); break;
                    case "C1:rules": new BlockFinder.Rules(false, 2, 0.5).apply(m); break;
                    case "C2:rules-largest": new BlockFinder.Rules(true, base.maxGap, base.keepRatio).apply(m); break;
                    default: {
                        BlockFinder.Css c = rules.forHost(h);
                        if (c != null && c.matched(m)) c.apply(m);
                        else new BlockFinder.Rules(true, base.maxGap, base.keepRatio).apply(m);
                    }
                }
                Map<Long, Integer> df = dfByHost.get(h);
                if (df != null) {
                    boolean[] before = new boolean[m.blocks.size()];
                    for (int i = 0; i < before.length; i++) before[i] = m.blocks.get(i).content;
                    new BlockFinder.Template(df, pagesByHost.get(h), base.templateRatio, base.templateMinDf).apply(m);
                    if (m.contentWords() == 0) for (int i = 0; i < before.length; i++) m.blocks.get(i).content = before[i];
                }
            }));
        }
        if (model != null) {
            out.add(cfg("L:learned (logistic regression)", (m, h, e, b) ->
                    new BlockFinder.Learned(model, statsByHost.get(h)).apply(m)));
            out.add(cfg("D:L:learned+template", (m, h, e, b) -> {
                new BlockFinder.Learned(model, statsByHost.get(h)).apply(m);
                Map<Long, Integer> df = dfByHost.get(h);
                if (df != null) {
                    boolean[] before = new boolean[m.blocks.size()];
                    for (int i = 0; i < before.length; i++) before[i] = m.blocks.get(i).content;
                    new BlockFinder.Template(df, pagesByHost.get(h), base.templateRatio, base.templateMinDf).apply(m);
                    if (m.contentWords() == 0) for (int i = 0; i < before.length; i++) m.blocks.get(i).content = before[i];
                }
            }));
        }
        return out;
    }

    private static Config cfg(String name, Apply a) {
        return new Config() {
            public String name() { return name; }
            public void apply(PageModel m, String host, RawStore.Entry e, byte[] body) throws Exception { a.run(m, host, e, body); }
        };
    }

    interface Apply {
        void run(PageModel m, String host, RawStore.Entry e, byte[] body) throws Exception;
    }

    public static void run(RawStore store, Path goldFile, Path report, CssRules rules, Extractor.Strategy base) throws Exception {
        List<Gold> gold = loadGold(goldFile);
        Map<String, RawStore.Entry> byUrl = new HashMap<>();
        for (RawStore.Entry e : store.all()) byUrl.put(e.url, e);
        List<Config> cs = prepare(store, gold, byUrl, rules, base);
        Map<String, Counts> res = new LinkedHashMap<>();
        for (Config c : cs) res.put(c.name(), new Counts());
        int missing = 0, drift = 0;
        Map<String, Map<String, double[]>> perPage = new LinkedHashMap<>();
        Map<String, Map<String, Counts>> perHost = new HashMap<>();
        for (Gold g : gold) {
            RawStore.Entry e = byUrl.get(g.url);
            if (e == null || e.file == null) { missing++; continue; }
            byte[] body = store.read(e);
            for (Config c : cs) {
                PageModel m = Pages.model(store, e);
                if (m.blocks.size() != g.nblocks) {
                    drift++;
                    break;
                }
                c.apply(m, e.host, e, body);
                double tp = 0, pred = 0, goldW = 0;
                for (Block b : m.blocks) {
                    boolean gc = g.content.contains(b.id);
                    if (gc) goldW += b.words;
                    if (b.content) {
                        pred += b.words;
                        if (gc) tp += b.words;
                    }
                }
                res.get(c.name()).add(tp, pred, goldW);
                perPage.computeIfAbsent(g.url, k -> new LinkedHashMap<>()).put(c.name(), new double[]{tp, pred, goldW});
                perHost.computeIfAbsent(c.name(), k -> new HashMap<>()).computeIfAbsent(e.host, k -> new Counts()).add(tp, pred, goldW);
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(report.toString().replace(".md", "_per_page.csv")), StandardCharsets.UTF_8)) {
            w.write("url,goldWords,config,tp,pred\n");
            for (Map.Entry<String, Map<String, double[]>> pe : perPage.entrySet())
                for (Map.Entry<String, double[]> ce : pe.getValue().entrySet())
                    w.write(pe.getKey().replace(",", "%2C") + "," + (int) ce.getValue()[2] + "," + ce.getKey() + ","
                            + (int) ce.getValue()[0] + "," + (int) ce.getValue()[1] + "\n");
        }
        try (BufferedWriter w = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            w.write("# Đánh giá thuật toán tìm khối nội dung\n\nTập vàng: `" + goldFile + "` (" + gold.size() + " trang"
                    + (missing > 0 ? ", thiếu " + missing : "") + (drift > 0 ? ", lệch phân khối " + drift : "") + ").\n");
            w.write("Đơn vị tính: số từ. Macro = trung bình theo trang; Micro = gộp mọi trang.\n\n");
            w.write("| Cấu hình | P (macro) | R (macro) | F1 (macro) | P (micro) | R (micro) | F1 (micro) |\n|---|---|---|---|---|---|---|\n");
            for (Map.Entry<String, Counts> en : res.entrySet()) {
                Counts c = en.getValue();
                if (c.pages == 0) continue;
                w.write(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |%n", en.getKey(),
                        c.sumP / c.pages, c.sumR / c.pages, c.sumF / c.pages, c.microP(), c.microR(), c.microF()));
            }
        }
        System.out.println(Files.readString(report, StandardCharsets.UTF_8));
    }

    /** Các cấu hình cần chấm cho tập vàng: thống kê khuôn mẫu theo host (chỉ các host có trong tập vàng) + bộ phân loại đã huấn luyện. */
    private static List<Config> prepare(RawStore store, List<Gold> gold, Map<String, RawStore.Entry> byUrl, CssRules rules,
                                        Extractor.Strategy base) throws Exception {
        // df theo host (chỉ cho các host có trong tập vàng)
        Set<String> hosts = new HashSet<>();
        for (Gold g : gold) {
            RawStore.Entry e = byUrl.get(g.url);
            if (e != null) hosts.add(e.host);
        }
        Map<String, Map<Long, Integer>> dfByHost = new HashMap<>();
        Map<String, Integer> pagesByHost = new HashMap<>();
        Map<String, Extractor.HostStats> statsByHost = new HashMap<>();
        Extractor ex = new Extractor(store, base, rules);
        for (String h : hosts) {
            List<RawStore.Entry> pages = new ArrayList<>();
            for (RawStore.Entry e : store.all())
                if (e.host.equals(h) && e.status == 200 && "HTML".equals(e.kind) && e.file != null) pages.add(e);
            if (pages.size() >= 3) {
                Extractor.HostStats hs = ex.hostStats(pages, 6);
                statsByHost.put(h, hs);
                dfByHost.put(h, hs.df);
                pagesByHost.put(h, hs.pages);
            }
        }

        String mp = System.getProperty("block.model");
        LogReg model = mp != null ? LogReg.load(Paths.get(mp)) : LogReg.loadResource("/extract/block_model.json");
        return configs(store, rules, dfByHost, pagesByHost, base, statsByHost, model);
    }

    /** Cấu hình đưa vào hình "dải khối" của báo cáo trực quan. */
    private static final List<String> STRIP_CONFIGS = List.of("A:baseline(jsoup link-density)", "B1:bte",
            "L:learned (logistic regression)", "D:L:learned+template");

    /**
     * Xuất nhãn từng khối của mọi trang trong tập vàng (nhãn vàng + nhãn của vài cấu hình) ra JSON cho báo cáo trực quan:
     * {@code [{url, host, blocks:[{id, words, link, tag, text, gold, pred:{cấu hình: 0/1}}]}]}.
     */
    public static void strips(RawStore store, Path goldFile, Path out, CssRules rules, Extractor.Strategy base) throws Exception {
        List<Gold> gold = loadGold(goldFile);
        Map<String, RawStore.Entry> byUrl = new HashMap<>();
        for (RawStore.Entry e : store.all()) byUrl.put(e.url, e);
        List<Config> cs = new ArrayList<>();
        for (Config c : prepare(store, gold, byUrl, rules, base)) if (STRIP_CONFIGS.contains(c.name())) cs.add(c);
        List<Map<String, Object>> pages = new ArrayList<>();
        for (Gold g : gold) {
            RawStore.Entry e = byUrl.get(g.url);
            if (e == null || e.file == null) continue;
            byte[] body = store.read(e);
            List<Map<String, Object>> blocks = null;
            for (Config c : cs) {
                PageModel m = Pages.model(store, e);
                if (m.blocks.size() != g.nblocks) break;
                c.apply(m, e.host, e, body);
                if (blocks == null) {
                    blocks = new ArrayList<>();
                    for (Block b : m.blocks) {
                        Map<String, Object> x = new LinkedHashMap<>();
                        String t = b.text.replaceAll("\\s+", " ").strip();
                        x.put("id", b.id);
                        x.put("words", b.words);
                        x.put("link", Math.round(b.linkDensity() * 100) / 100.0);
                        x.put("tag", b.container == null ? "?" : b.container.normalName());
                        x.put("text", t.length() > 90 ? t.substring(0, 90) + "…" : t);
                        x.put("gold", g.content.contains(b.id) ? 1 : 0);
                        x.put("pred", new LinkedHashMap<String, Integer>());
                        blocks.add(x);
                    }
                }
                for (int i = 0; i < m.blocks.size(); i++) {
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> pred = (Map<String, Integer>) blocks.get(i).get("pred");
                    pred.put(c.name(), m.blocks.get(i).content ? 1 : 0);
                }
            }
            if (blocks == null) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("url", g.url);
            p.put("host", e.host);
            p.put("blocks", blocks);
            pages.add(p);
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(out.toFile(), pages);
        System.out.println(pages.size() + " trang -> " + out);
    }

    /** Đánh giá metadata: so khớp với nhãn vàng. Dùng chiến lược mặc định. */
    public static void runMeta(RawStore store, Path goldFile, Path report, CssRules rules, Extractor.Strategy base) throws Exception {
        List<Gold> gold = loadGold(goldFile);
        Map<String, RawStore.Entry> byUrl = new HashMap<>();
        for (RawStore.Entry e : store.all()) byUrl.put(e.url, e);
        Extractor ex = new Extractor(store, base, rules);
        Map<String, Extractor.HostStats> stats = new HashMap<>();
        for (Gold g : gold) {
            RawStore.Entry e = byUrl.get(g.url);
            if (e == null || stats.containsKey(e.host)) continue;
            List<RawStore.Entry> pages = new ArrayList<>();
            for (RawStore.Entry x : store.all())
                if (x.host.equals(e.host) && x.status == 200 && "HTML".equals(x.kind) && x.file != null) pages.add(x);
            stats.put(e.host, pages.size() >= 3 ? ex.hostStats(pages, 6) : null);
        }
        int n = 0;
        String[] names = {"title", "publishedAt", "author"};
        int[] evald = new int[3], ok = new int[3], goldPresent = new int[3], predPresent = new int[3], okWhenGold = new int[3], okWhenPred = new int[3];
        List<String> errs = new ArrayList<>();
        Map<String, int[]> bySource = new LinkedHashMap<>();
        for (Gold g : gold) {
            RawStore.Entry e = byUrl.get(g.url);
            if (e == null || e.file == null) continue;
            Extractor.Output o = ex.process(e, stats.get(e.host));
            n++;
            String[] gv = {g.title, g.date, g.author};
            for (int i = 0; i < 3; i++) {
                if ("?".equals(gv[i])) continue;
                evald[i]++;
                @SuppressWarnings("unchecked")
                Map<String, Object> f = (Map<String, Object>) o.page.get(names[i]);
                String pv = f == null ? null : String.valueOf(f.get("value"));
                boolean gp = gv[i] != null && !gv[i].equals("-") && !gv[i].isEmpty();
                if (gp) goldPresent[i]++;
                if (pv != null) predPresent[i]++;
                boolean match;
                if (!gp) match = pv == null;
                else if (pv == null) match = false;
                else if (i == 1) match = pv.equals(gv[i]);
                else match = norm(pv).equals(norm(gv[i])) || (i == 0 && norm(gv[i]).length() > 8 && norm(pv).contains(norm(gv[i])));
                if (match) {
                    ok[i]++;
                    if (gp) okWhenGold[i]++;
                    if (pv != null) okWhenPred[i]++;
                } else errs.add(names[i] + " | " + g.url + " | gold=" + gv[i] + " | pred=" + pv + (f == null ? "" : " [" + f.get("source") + "]"));
                if (f != null) {
                    int[] c = bySource.computeIfAbsent(names[i] + ":" + f.get("source"), k -> new int[2]);
                    c[0]++;
                    if (match) c[1]++;
                }
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            w.write("# Đánh giá metadata (" + n + " trang; tập vàng `" + goldFile + "`)\n\n");
            w.write("Chính xác = dự đoán đúng (kể cả \"không có\" khi nhãn vàng không có); Precision = đúng / số trường được điền; "
                    + "Recall = đúng / số trường có nhãn vàng.\n\n");
            w.write("| Trường | Chính xác | Precision | Recall | Có nhãn vàng | Có dự đoán |\n|---|---|---|---|---|---|\n");
            for (int i = 0; i < 3; i++)
                w.write(String.format(Locale.ROOT, "| %s | %.3f (%d/%d) | %.3f | %.3f | %d | %d |%n", names[i],
                        evald[i] == 0 ? 0 : (double) ok[i] / evald[i], ok[i], evald[i],
                        predPresent[i] == 0 ? 0 : (double) okWhenPred[i] / predPresent[i],
                        goldPresent[i] == 0 ? 0 : (double) okWhenGold[i] / goldPresent[i], goldPresent[i], predPresent[i]));
            w.write("\n## Theo nguồn\n\n| Nguồn | Số trang | Đúng |\n|---|---|---|\n");
            for (Map.Entry<String, int[]> en : bySource.entrySet())
                w.write(String.format(Locale.ROOT, "| %s | %d | %d |%n", en.getKey(), en.getValue()[0], en.getValue()[1]));
            w.write("\n## Sai\n\n");
            for (String s : errs) w.write("- " + s + "\n");
        }
        System.out.println(Files.readString(report, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unused")
    private static void each(List<Gold> l, Consumer<Gold> c) {
        l.forEach(c);
    }
}
