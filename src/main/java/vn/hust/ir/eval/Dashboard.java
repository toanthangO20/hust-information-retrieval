package vn.hust.ir.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import vn.hust.ir.eval.EvalIo.Topic;

/**
 * Sinh dữ liệu cho báo cáo trực quan ({@code docs/data/results.js} + {@code results.json}) trực tiếp từ đầu ra của các bước:
 * {@code output/} (crawl), {@code dataset/ver1/manifest.json}, {@code data/extract/v1} (đồ thị), {@code eval/extract} (bóc tách),
 * {@code eval/runs} + {@code eval/qrels} (truy hồi, tính lại độ đo và kiểm định), {@code eval/report/latency.tsv} (độ trễ).
 * Mọi con số trong {@code docs/BAO_CAO_TRUC_QUAN.html} lấy từ đây, nên chạy lại lệnh này sau mỗi lần đánh giá là báo cáo khớp số liệu.
 * Nguồn nào vắng mặt (ví dụ {@code data/} không có trên máy khác) thì giữ nguyên phần tương ứng của lần sinh trước.
 */
public final class Dashboard {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int PERM_ROUNDS = 20000, BOOT_ROUNDS = 10000;

    /** Hệ thống chính trong báo cáo: tệp run, mã mô hình, nhãn, chương slide. */
    static final class Sys {
        final String run, code, label, slide;

        Sys(String run, String code, String label, String slide) {
            this.run = run;
            this.code = code;
            this.label = label;
            this.slide = slide;
        }
    }

    static final String BASELINE = "bm25";
    static final List<Sys> SYSTEMS = List.of(
            new Sys("current", "W", "Công cụ web hiện tại", "Ch07"),
            new Sys("vsm", "M1", "VSM tf-idf (lnc.ltc)", "Ch04"),
            new Sys("bm25", "M2", "BM25 đa trường", "Ch07"),
            new Sys("prf_docs-5_terms-20_lambda-0.5", "M3", "BM25 + PRF (RM3)", "Ch06"),
            new Sys("dense", "D", "Dense (e5-small)", "ngoài slide"),
            new Sys("hybrid_w-2", "M4", "Hybrid BM25 + dense (RRF)", "ngoài slide"),
            new Sys("drocchio_m-5_alpha-0.5_wo-0.5", "M5", "Rocchio trên vec-tơ dày", "Ch06 mở rộng"),
            new Sys("ce_depth-30_len-320", "M6", "Cross-encoder xếp lại", "ngoài slide"));

    /** Lưới tham số đã thử trên dev: họ mô hình, tệp run, nhãn, trạng thái (chosen = cấu hình chốt, late = thử thêm sau khi chốt). */
    static final String[][] TUNING = {
            {"M2 BM25", "bm25", "k1=1,2 · b=0,75 (mặc định)", "chosen"},
            {"M2 BM25", "bm25_k1-0.9_b-0.5", "k1=0,9 · b=0,5", ""},
            {"M2 BM25", "bm25_k1-0.9_b-0.9", "k1=0,9 · b=0,9", ""},
            {"M2 BM25", "bm25_k1-1.6_b-0.5", "k1=1,6 · b=0,5", ""},
            {"M2 BM25", "bm25_k1-1.6_b-0.9", "k1=1,6 · b=0,9", ""},
            {"M3 PRF", "prf", "10 tài liệu · 20 từ · λ=0,5", ""},
            {"M3 PRF", "prf_docs-10_terms-20_lambda-0.3", "10 tài liệu · 20 từ · λ=0,3", ""},
            {"M3 PRF", "prf_docs-10_terms-30_lambda-0.5", "10 tài liệu · 30 từ · λ=0,5", ""},
            {"M3 PRF", "prf_docs-5_terms-10_lambda-0.3", "5 tài liệu · 10 từ · λ=0,3", ""},
            {"M3 PRF", "prf_docs-5_terms-20_lambda-0.5", "5 tài liệu · 20 từ · λ=0,5", "chosen"},
            {"M4 Hybrid", "hybrid_w-0.5", "w=0,5", ""},
            {"M4 Hybrid", "hybrid", "w=1", ""},
            {"M4 Hybrid", "hybrid_w-2", "w=2", "chosen"},
            {"M4 Hybrid", "hybrid_w-3", "w=3", "late"},
            {"M4 Hybrid", "hybrid_w-4", "w=4", "late"},
            {"M5 Rocchio dày", "drocchio_m-5_alpha-0.5", "m=5 · α=0,5", ""},
            {"M5 Rocchio dày", "drocchio_m-5_alpha-0.7", "m=5 · α=0,7", ""},
            {"M5 Rocchio dày", "drocchio_m-10_alpha-0.5", "m=10 · α=0,5", ""},
            {"M5 Rocchio dày", "drocchio_m-5_alpha-0.5_lex-prf", "m=5 · α=0,5 · nhánh từ khoá PRF", ""},
            {"M5 Rocchio dày", "drocchio_m-5_alpha-0.5_wo-0.5", "m=5 · α=0,5 · giữ dense(q) w=0,5", "chosen"},
            {"M6 Cross-encoder", "ce_depth-30_len-320", "top-30 · chỉ điểm CE", "chosen"},
            {"M6 Cross-encoder", "ce_depth-30_len-320_blend-0.5", "top-30 · trộn hạng 0,5", ""},
            {"M6 Cross-encoder", "ce_depth-30_len-320_blend-1", "top-30 · trộn hạng 1", ""},
            {"M6 Cross-encoder", "ce_depth-30_len-320_blend-2", "top-30 · trộn hạng 2", ""},
    };

    /** Nhãn tiếng Việt của các cấu hình tìm khối (tên trong report_blocks_*_per_page.csv). */
    static final Map<String, String> BLOCK_LABELS = new LinkedHashMap<>();
    static {
        BLOCK_LABELS.put("A:baseline(jsoup link-density)", "Baseline jsoup (mật độ liên kết)");
        BLOCK_LABELS.put("B1:bte", "BTE (Ch10)");
        BLOCK_LABELS.put("B2:bte-link", "BTE có xét liên kết");
        BLOCK_LABELS.put("C1:rules", "Quy tắc đặc trưng nông");
        BLOCK_LABELS.put("C2:rules-largest", "Quy tắc, cụm lớn nhất");
        BLOCK_LABELS.put("E:css(host rules; else rules-largest)", "Quy tắc CSS theo host");
        BLOCK_LABELS.put("D:B1:bte+template", "BTE + lọc khuôn mẫu");
        BLOCK_LABELS.put("D:B2:bte-link+template", "BTE xét liên kết + lọc khuôn mẫu");
        BLOCK_LABELS.put("D:C1:rules+template", "Quy tắc + lọc khuôn mẫu");
        BLOCK_LABELS.put("D:C2:rules-largest+template", "Quy tắc cụm lớn nhất + lọc khuôn mẫu");
        BLOCK_LABELS.put("D:E:css+template", "CSS theo host + lọc khuôn mẫu");
        BLOCK_LABELS.put("L:learned (logistic regression)", "Phân loại học (hồi quy logistic)");
        BLOCK_LABELS.put("D:L:learned+template", "Phân loại học + lọc khuôn mẫu");
    }

    interface Source {
        Object get() throws Exception;
    }

    public static void build(Map<String, String> o) throws Exception {
        Path js = Paths.get(o.getOrDefault("out", "docs/data/results.js"));
        Path json = js.resolveSibling(js.getFileName().toString().replaceFirst("\\.js$", "") + ".json");
        JsonNode prev = Files.exists(json) ? JSON.readTree(json.toFile()) : JSON.createObjectNode();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generated", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        section(r, prev, "crawl", Dashboard::crawl);
        section(r, prev, "dataset", Dashboard::dataset);
        section(r, prev, "graph", Dashboard::graph);
        section(r, prev, "blocks", Dashboard::blocks);
        section(r, prev, "meta", Dashboard::meta);
        section(r, prev, "test", () -> retrieval("test"));
        section(r, prev, "dev", () -> retrieval("dev"));
        section(r, prev, "anchor", Dashboard::anchor);
        section(r, prev, "latency", Dashboard::latency);
        Files.createDirectories(js.toAbsolutePath().getParent());
        String body = JSON.writeValueAsString(r);
        Files.writeString(json, body, StandardCharsets.UTF_8);
        Files.writeString(js, "// Sinh tự động bởi: scripts\\eval.bat dashboard  (vn.hust.ir.eval.Dashboard). Không sửa tay.\n"
                + "window.RESULTS = " + body + ";\n", StandardCharsets.UTF_8);
        System.out.println("-> " + js + " , " + json);
    }

    private static void section(Map<String, Object> r, JsonNode prev, String key, Source s) throws Exception {
        long t0 = System.currentTimeMillis();
        try {
            r.put(key, s.get());
            System.out.printf(Locale.ROOT, "  %-8s ok (%.1f s)%n", key, (System.currentTimeMillis() - t0) / 1000.0);
        } catch (NoSuchFileException e) {
            if (prev.has(key)) {
                r.put(key, prev.get(key));
                System.out.println("  " + key + ": thiếu " + e.getMessage() + " -> giữ số liệu của lần sinh trước");
            } else System.out.println("  " + key + ": thiếu " + e.getMessage() + " -> bỏ qua");
        }
    }

    static double r4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static final Pattern HOST = Pattern.compile("^https?://([^/:?#]+)", Pattern.CASE_INSENSITIVE);

    static String host(String url) {
        Matcher m = HOST.matcher(url);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "?";
    }

    private static List<Map<String, Object>> top(Map<String, Integer> counts, String keyName) {
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(e -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put(keyName, e.getKey());
            x.put("count", e.getValue());
            out.add(x);
        });
        return out;
    }

    // ------------------------------------------------------------------ crawl

    private static Object crawl() throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        List<Map<String, Object>> runs = new ArrayList<>();
        List<String> log = Files.readAllLines(Paths.get("output/update_log.tsv"), StandardCharsets.UTF_8);
        for (String l : log.subList(1, log.size())) {
            if (l.isBlank()) continue;
            String[] f = l.split("\t");
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("time", f[0]);
            x.put("valid", Integer.parseInt(f[1]));
            x.put("invalid", Integer.parseInt(f[2]));
            x.put("pending", Integer.parseInt(f[3]));
            x.put("added", Integer.parseInt(f[4]));
            x.put("removed", Integer.parseInt(f[5]));
            runs.add(x);
        }
        c.put("runs", runs);

        Map<String, Integer> reasons = new HashMap<>();
        List<String> inv = Files.readAllLines(Paths.get("output/urls_invalid.tsv"), StandardCharsets.UTF_8);
        for (String l : inv.subList(1, inv.size())) {
            String[] f = l.split("\t");
            if (f.length < 3) continue;
            String reason = f[2].replaceFirst(":.*$", "");
            if (reason.matches("http-\\d{3}") && !reason.equals("http-404")) reason = "http-" + reason.charAt(5) + "xx";
            reasons.merge(reason, 1, Integer::sum);
        }
        c.put("reasons", top(reasons, "reason"));

        Map<String, Integer> hosts = new HashMap<>();
        int valid = 0;
        for (String u : Files.readAllLines(Paths.get("output/urls_valid.txt"), StandardCharsets.UTF_8)) {
            if (u.isBlank()) continue;
            valid++;
            hosts.merge(host(u.trim()), 1, Integer::sum);
        }
        c.put("valid", valid);
        c.put("invalid", inv.size() - 1);
        c.put("pending", runs.isEmpty() ? 0 : runs.get(runs.size() - 1).get("pending"));
        c.put("hosts", top(hosts, "host"));
        return c;
    }

    // ------------------------------------------------------------------ dataset

    private static Object dataset() throws IOException {
        JsonNode m = JSON.readTree(Files.readString(Paths.get("dataset/ver1/manifest.json"), StandardCharsets.UTF_8));
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : new String[]{"version", "documents", "hosts", "total_words", "avg_words_per_doc", "by_doc_type", "by_quality"})
            d.put(k, m.get(k));
        Path idx = Paths.get("index/ver1");
        if (Files.isDirectory(idx))
            try (FSDirectory dir = FSDirectory.open(idx); DirectoryReader rd = DirectoryReader.open(dir)) {
                d.put("indexed", rd.numDocs());
            }
        return d;
    }

    // ------------------------------------------------------------------ graph

    private static Object graph() throws IOException {
        Path base = Paths.get("data/extract/v1");
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("stats", JSON.readTree(Files.readString(base.resolve("graph_stats.json"), StandardCharsets.UTF_8)));

        // trang: độ phủ metadata, PageRank theo host, trang PageRank cao nhất
        Map<String, int[]> cov = new TreeMap<>();
        Map<String, Integer> pagesByHost = new HashMap<>();
        Map<String, Double> prByHost = new HashMap<>();
        List<JsonNode> topPr = new ArrayList<>(), topPrc = new ArrayList<>();
        try (BufferedReader rd = Files.newBufferedReader(base.resolve("pages.final.jsonl"), StandardCharsets.UTF_8)) {
            for (String l; (l = rd.readLine()) != null; ) {
                JsonNode n = JSON.readTree(l);
                if (!n.path("inDataset").asBoolean()) continue;
                String h = n.path("host").asText();
                pagesByHost.merge(h, 1, Integer::sum);
                prByHost.merge(h, n.path("pagerank").asDouble(), Double::sum);
                int[] c = cov.computeIfAbsent(n.path("kind").asText(), x -> new int[COV_FIELDS.length + 1]);
                c[0]++;
                for (int i = 0; i < COV_FIELDS.length; i++) if (n.hasNonNull(COV_FIELDS[i])) c[i + 1]++;
                ObjectNode slim = JSON.createObjectNode();
                slim.put("title", n.path("title").path("value").asText(""));
                slim.put("url", n.path("url").asText());
                slim.put("host", h);
                slim.put("kind", n.path("kind").asText());
                slim.put("pagerank", n.path("pagerank").asDouble());
                slim.put("pagerankContent", n.path("pagerankContent").asDouble());
                slim.put("inlinks", n.path("inlinks").asInt());
                slim.put("inlinksContent", n.path("inlinksContent").asInt());
                slim.put("inlinkHosts", n.path("inlinkHosts").asInt());
                keepTop(topPr, slim, "pagerank", 10);
                keepTop(topPrc, slim, "pagerankContent", 10);
            }
        }
        Map<String, Object> coverage = new LinkedHashMap<>();     // loại trang -> {pages, title, publishedAt, ...}
        for (Map.Entry<String, int[]> e : cov.entrySet()) {
            Map<String, Integer> x = new LinkedHashMap<>();
            x.put("pages", e.getValue()[0]);
            for (int i = 0; i < COV_FIELDS.length; i++) x.put(COV_FIELDS[i], e.getValue()[i + 1]);
            coverage.put(e.getKey(), x);
        }
        g.put("coverage", coverage);
        g.put("topPagerank", topPr);
        g.put("topPagerankContent", topPrc);

        // cạnh: liên kết <a> giữa các host *.hust.edu.vn khác nhau
        Map<String, Integer> pair = new HashMap<>();
        Map<String, Integer> in = new HashMap<>(), out = new HashMap<>();
        long a = 0, aContent = 0, img = 0, cross = 0;
        try (BufferedReader rd = Files.newBufferedReader(base.resolve("links.final.jsonl"), StandardCharsets.UTF_8)) {
            for (String l; (l = rd.readLine()) != null; ) {
                JsonNode n = JSON.readTree(l);
                String kind = n.path("kind").asText();
                if (kind.equals("img")) img++;
                if (!kind.equals("a")) continue;
                a++;
                if (n.path("inContent").asBoolean()) aContent++;
                String s = n.path("srcHost").asText(), t = n.path("dstHost").asText();
                if (s.equals(t) || !s.endsWith("hust.edu.vn") || !t.endsWith("hust.edu.vn")) continue;
                cross++;
                pair.merge(s + "\t" + t, 1, Integer::sum);
                out.merge(s, 1, Integer::sum);
                in.merge(t, 1, Integer::sum);
            }
        }
        Map<String, Object> ec = new LinkedHashMap<>();
        ec.put("a", a);
        ec.put("aInContent", aContent);
        ec.put("img", img);
        ec.put("crossHost", cross);
        g.put("edges", ec);

        List<String> hosts = new ArrayList<>(pagesByHost.keySet());
        hosts.removeIf(h -> !h.endsWith("hust.edu.vn"));
        hosts.sort(Comparator.comparingInt((String h) -> in.getOrDefault(h, 0) + out.getOrDefault(h, 0)).reversed());
        List<String> keep = hosts.subList(0, Math.min(30, hosts.size()));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String h : keep) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("host", h);
            x.put("pages", pagesByHost.getOrDefault(h, 0));
            x.put("pagerank", r4(prByHost.getOrDefault(h, 0.0)));
            x.put("in", in.getOrDefault(h, 0));
            x.put("out", out.getOrDefault(h, 0));
            nodes.add(x);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        pair.entrySet().stream().filter(e -> {
            String[] st = e.getKey().split("\t");
            return keep.contains(st[0]) && keep.contains(st[1]);
        }).sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(120).forEach(e -> {
            String[] st = e.getKey().split("\t");
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("s", st[0]);
            x.put("t", st[1]);
            x.put("w", e.getValue());
            links.add(x);
        });
        Map<String, Object> hg = new LinkedHashMap<>();
        hg.put("nodes", nodes);
        hg.put("links", links);
        g.put("hostGraph", hg);
        return g;
    }

    private static final String[] COV_FIELDS = {"title", "publishedAt", "author", "sourceRef"};

    private static void keepTop(List<JsonNode> l, JsonNode n, String field, int k) {
        l.add(n);
        l.sort(Comparator.comparingDouble((JsonNode x) -> x.path(field).asDouble()).reversed());
        if (l.size() > k) l.remove(l.size() - 1);
    }

    // ------------------------------------------------------------------ blocks

    /** P, R, F1 của một trang theo đúng quy ước của BlockEval (trang không có nội dung vàng và không dự đoán gì = đúng hoàn toàn). */
    static double[] pageScores(double tp, double pred, double gold) {
        double p = pred == 0 ? (gold == 0 ? 1 : 0) : tp / pred;
        double r = gold == 0 ? (pred == 0 ? 1 : 0) : tp / gold;
        return new double[]{p, r, p + r == 0 ? 0 : 2 * p * r / (p + r)};
    }

    private static Object blocks() throws IOException {
        Map<String, Object> b = new LinkedHashMap<>();
        for (String split : new String[]{"test", "dev"}) {
            Path f = Paths.get("eval/extract/report_blocks_" + split + "_per_page.csv");
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            Map<String, double[]> agg = new LinkedHashMap<>();       // sumP, sumR, sumF, n, tp, pred, gold
            Map<String, Map<String, double[]>> pages = new LinkedHashMap<>();
            for (String l : lines.subList(1, lines.size())) {
                String[] c = l.split(",");
                if (c.length < 5) continue;
                String url = c[0].replace("%2C", ","), cfg = c[2];
                double gold = Double.parseDouble(c[1]), tp = Double.parseDouble(c[3]), pred = Double.parseDouble(c[4]);
                double[] s = pageScores(tp, pred, gold);
                double[] a = agg.computeIfAbsent(cfg, k -> new double[7]);
                a[0] += s[0]; a[1] += s[1]; a[2] += s[2]; a[3]++; a[4] += tp; a[5] += pred; a[6] += gold;
                pages.computeIfAbsent(url, k -> new LinkedHashMap<>()).put(cfg, new double[]{gold, s[2]});
            }
            List<String> cfgs = new ArrayList<>(agg.keySet());
            List<Map<String, Object>> configs = new ArrayList<>();
            for (String cfg : cfgs) {
                double[] a = agg.get(cfg);
                double mp = a[5] == 0 ? 0 : a[4] / a[5], mr = a[6] == 0 ? 0 : a[4] / a[6];
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", cfg);
                x.put("label", BLOCK_LABELS.getOrDefault(cfg, cfg));
                x.put("template", cfg.startsWith("D:"));
                x.put("macroP", r4(a[0] / a[3]));
                x.put("macroR", r4(a[1] / a[3]));
                x.put("macroF", r4(a[2] / a[3]));
                x.put("microF", r4(mp + mr == 0 ? 0 : 2 * mp * mr / (mp + mr)));
                configs.add(x);
            }
            List<Map<String, Object>> perPage = new ArrayList<>();
            for (Map.Entry<String, Map<String, double[]>> e : pages.entrySet()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("url", e.getKey());
                x.put("host", host(e.getKey()));
                x.put("gold", (int) e.getValue().values().iterator().next()[0]);
                List<Double> fs = new ArrayList<>();
                for (String cfg : cfgs) fs.add(e.getValue().containsKey(cfg) ? r4(e.getValue().get(cfg)[1]) : null);
                x.put("f", fs);
                perPage.add(x);
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("pages", pages.size());
            s.put("configs", configs);
            s.put("perPage", perPage);
            if (split.equals("test")) s.put("strips", strips(Paths.get("eval/extract/strips_test.json"), cfgs, perPage));
            b.put(split, s);
        }
        return b;
    }

    /**
     * Chọn vài trang minh hoạ cho hình "dải khối" (chỉ trang có >= 80 từ nội dung vàng): 3 trang mô hình học hơn BTE nhiều nhất
     * và 1 trang mô hình học kém nhất — để báo cáo cho thấy cả trường hợp thất bại.
     */
    private static Object strips(Path f, List<String> cfgs, List<Map<String, Object>> perPage) throws IOException {
        if (!Files.exists(f)) return List.of();
        int bte = cfgs.indexOf("B1:bte"), best = cfgs.indexOf("D:L:learned+template");
        Map<String, double[]> gain = new HashMap<>();
        for (Map<String, Object> p : perPage) {
            @SuppressWarnings("unchecked")
            List<Double> fs = (List<Double>) p.get("f");
            if (fs.get(bte) != null && fs.get(best) != null && (Integer) p.get("gold") >= 80)
                gain.put((String) p.get("url"), new double[]{fs.get(best) - fs.get(bte), fs.get(best)});
        }
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode n : JSON.readTree(f.toFile())) {
            int nb = n.path("blocks").size();
            if (gain.containsKey(n.path("url").asText()) && nb >= 12 && nb <= 160) all.add(n);
        }
        List<JsonNode> pick = new ArrayList<>();
        all.sort(Comparator.comparingDouble((JsonNode n) -> gain.get(n.path("url").asText())[0]).reversed());
        for (JsonNode n : all) if (pick.size() < 3) pick.add(n);
        all.sort(Comparator.comparingDouble((JsonNode n) -> gain.get(n.path("url").asText())[1]));
        for (JsonNode n : all) if (!pick.contains(n)) { pick.add(n); break; }
        return pick;
    }

    // ------------------------------------------------------------------ metadata

    private static final Pattern META_ROW = Pattern.compile(
            "^\\| (title|publishedAt|author) \\| ([0-9.]+) \\((\\d+)/(\\d+)\\) \\| ([0-9.]+) \\| ([0-9.]+) \\| (\\d+) \\| (\\d+) \\|");

    private static Object meta() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String split : new String[]{"test", "dev"}) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String l : Files.readAllLines(Paths.get("eval/extract/report_meta_" + split + ".md"), StandardCharsets.UTF_8)) {
                Matcher m = META_ROW.matcher(l);
                if (!m.find()) continue;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("field", m.group(1));
                x.put("accuracy", Double.parseDouble(m.group(2)));
                x.put("ok", Integer.parseInt(m.group(3)));
                x.put("n", Integer.parseInt(m.group(4)));
                x.put("precision", Double.parseDouble(m.group(5)));
                x.put("recall", Double.parseDouble(m.group(6)));
                x.put("goldPresent", Integer.parseInt(m.group(7)));
                x.put("predPresent", Integer.parseInt(m.group(8)));
                rows.add(x);
            }
            out.put(split, rows);
        }
        return out;
    }

    // ------------------------------------------------------------------ retrieval

    // [DEMO:dashboard-stats] độ đo + kiểm định cho slide
    static Object retrieval(String split) throws IOException {
        List<Topic> topics = EvalIo.readTopics(Paths.get("eval/topics.tsv"), split);
        Map<String, Map<Integer, Integer>> qrels = EvalIo.readQrels(Paths.get("eval/qrels/" + split + ".tsv"));
        List<Topic> use = new ArrayList<>();
        for (Topic t : topics) if (qrels.containsKey(t.qid) && Metrics.numRelevant(qrels.get(t.qid)) > 0) use.add(t);
        List<String> qids = new ArrayList<>();
        for (Topic t : use) qids.add(t.qid);
        Path dir = Paths.get("eval/runs/" + split);

        Map<String, double[][]> pq = new LinkedHashMap<>();
        for (Path p : EvalMain.expandRuns(dir.toString()))
            pq.put(p.getFileName().toString().replaceFirst("\\.run$", ""), EvalMain.perQuery(EvalIo.readRun(p), qrels, qids));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("split", split);
        r.put("n", qids.size());
        r.put("baseline", BASELINE);
        r.put("metrics", EvalMain.METRICS);
        List<Map<String, Object>> qs = new ArrayList<>();
        for (Topic t : use) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("qid", t.qid);
            x.put("type", t.type);
            x.put("text", t.text);
            int r1 = 0, r2 = 0;
            for (int v : qrels.get(t.qid).values()) { if (v == 1) r1++; if (v == 2) r2++; }
            x.put("rel1", r1);
            x.put("rel2", r2);
            qs.add(x);
        }
        r.put("queries", qs);

        List<Sys> main = new ArrayList<>();
        for (Sys s : SYSTEMS) if (pq.containsKey(s.run)) main.add(s);
        List<Map<String, Object>> systems = new ArrayList<>();
        for (Sys s : main) {
            double[][] m = pq.get(s.run);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", s.run);
            x.put("code", s.code);
            x.put("label", s.label);
            x.put("slide", s.slide);
            Map<String, Double> mean = new LinkedHashMap<>();
            for (int c = 0; c < EvalMain.METRICS.length; c++) mean.put(EvalMain.METRICS[c], r4(Significance.mean(col(m, c))));
            x.put("mean", mean);
            List<Double> nd = new ArrayList<>();
            for (double[] row : m) nd.add(r4(row[0]));
            x.put("ndcg", nd);
            systems.add(x);
        }
        r.put("systems", systems);

        // so với baseline: hiệu số, KTC bootstrap, hoán vị + Holm trên họ các so sánh với baseline
        double[] base = col(pq.get(BASELINE), 0);
        List<Sys> others = new ArrayList<>();
        for (Sys s : main) if (!s.run.equals(BASELINE)) others.add(s);
        double[] praw = new double[others.size()];
        for (int i = 0; i < others.size(); i++)     // cùng hạt giống với eval.bat eval khi --runs liệt kê baseline trước rồi theo thứ tự SYSTEMS
            praw[i] = Significance.randomizationP(col(pq.get(others.get(i).run), 0), base, PERM_ROUNDS, 42 + i + 1);
        double[] padj = Significance.holm(praw);
        List<Map<String, Object>> vs = new ArrayList<>();
        for (int i = 0; i < others.size(); i++) {
            double[] a = col(pq.get(others.get(i).run), 0);
            double[] ci = Significance.bootstrapCi(a, base, BOOT_ROUNDS, 7);
            int win = 0, lose = 0, tie = 0;
            for (int q = 0; q < a.length; q++) {
                if (Math.abs(a[q] - base[q]) < 1e-9) tie++; else if (a[q] > base[q]) win++; else lose++;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", others.get(i).run);
            x.put("delta", r4(Significance.mean(a) - Significance.mean(base)));
            x.put("lo", r4(ci[0]));
            x.put("hi", r4(ci[1]));
            x.put("p", r4(praw[i]));
            x.put("pHolm", r4(padj[i]));
            x.put("win", win);
            x.put("lose", lose);
            x.put("tie", tie);
            vs.add(x);
        }
        r.put("vsBaseline", vs);

        // mọi cặp hệ thống chính: hoán vị + Holm trên toàn bộ các cặp
        int k = main.size();
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < k; i++) for (int j = i + 1; j < k; j++) pairs.add(new int[]{i, j});
        double[] pp = new double[pairs.size()];
        for (int t = 0; t < pairs.size(); t++)
            pp[t] = Significance.randomizationP(col(pq.get(main.get(pairs.get(t)[0]).run), 0),
                    col(pq.get(main.get(pairs.get(t)[1]).run), 0), PERM_ROUNDS, 1000 + t);
        double[] ph = Significance.holm(pp);
        Double[][] pm = new Double[k][k], dm = new Double[k][k];
        for (int t = 0; t < pairs.size(); t++) {
            int i = pairs.get(t)[0], j = pairs.get(t)[1];
            double d = Significance.mean(col(pq.get(main.get(i).run), 0)) - Significance.mean(col(pq.get(main.get(j).run), 0));
            pm[i][j] = pm[j][i] = r4(ph[t]);
            dm[i][j] = r4(d);
            dm[j][i] = r4(-d);
        }
        Map<String, Object> pw = new LinkedHashMap<>();
        pw.put("pHolm", pm);
        pw.put("delta", dm);
        r.put("pairwise", pw);

        // theo loại truy vấn
        Map<String, List<Integer>> byType = new TreeMap<>();
        for (int i = 0; i < use.size(); i++) byType.computeIfAbsent(use.get(i).type, x -> new ArrayList<>()).add(i);
        List<Map<String, Object>> types = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byType.entrySet()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("type", e.getKey());
            x.put("n", e.getValue().size());
            Map<String, Double> v = new LinkedHashMap<>();
            for (Sys s : main) {
                double sum = 0;
                for (int i : e.getValue()) sum += pq.get(s.run)[i][0];
                v.put(s.run, r4(sum / e.getValue().size()));
            }
            x.put("ndcg", v);
            types.add(x);
        }
        r.put("byType", types);

        // độ nhạy theo trọng số dense trong hybrid (w=0 ~ BM25, w→∞ ~ dense)
        List<Map<String, Object>> sweep = new ArrayList<>();
        Pattern hw = Pattern.compile("^hybrid(?:_w-([0-9.]+))?$");
        for (String name : pq.keySet()) {
            Matcher m = hw.matcher(name);
            if (!m.matches()) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("w", m.group(1) == null ? 1.0 : Double.parseDouble(m.group(1)));
            x.put("run", name);
            x.put("ndcg", r4(Significance.mean(col(pq.get(name), 0))));
            x.put("recall", r4(Significance.mean(col(pq.get(name), 4))));
            sweep.add(x);
        }
        sweep.sort(Comparator.comparingDouble(x -> (Double) x.get("w")));
        Map<String, Object> sw = new LinkedHashMap<>();
        sw.put("points", sweep);
        sw.put("bm25", r4(Significance.mean(base)));
        if (pq.containsKey("dense")) sw.put("dense", r4(Significance.mean(col(pq.get("dense"), 0))));
        r.put("sweep", sw);

        // lưới tham số đã thử
        List<Map<String, Object>> tuning = new ArrayList<>();
        for (String[] t : TUNING) {
            if (!pq.containsKey(t[1])) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("family", t[0]);
            x.put("run", t[1]);
            x.put("label", t[2]);
            x.put("status", t[3]);
            x.put("ndcg", r4(Significance.mean(col(pq.get(t[1]), 0))));
            x.put("judged", r4(Significance.mean(col(pq.get(t[1]), 5))));
            tuning.add(x);
        }
        r.put("tuning", tuning);
        return r;
    }

    /** Thí nghiệm văn bản liên kết / nội dung chính / PageRank (chỉ mục ver1x, eval/runs/test_extract), so với toàn văn. */
    static final String[][] ANCHOR_RUNS = {
            {"bm25x_ctx-full", "Toàn văn (= M2 BM25)"},
            {"bm25x_ctx-main", "Nội dung chính"},
            {"bm25x_ctx-main+anchor_aw-0.3", "Nội dung chính + văn bản neo (w=0,3)"},
            {"bm25x_ctx-main+anchor_aw-1.5", "Nội dung chính + văn bản neo (w=1,5)"},
            {"bm25x_ctx-main+anchorc_aw-0.5", "Nội dung chính + neo trong nội dung (w=0,5)"},
            {"bm25x_ctx-main+anchorc_aw-1", "Nội dung chính + neo trong nội dung (w=1)"},
            {"bm25x_ctx-main_pr-0.2", "Nội dung chính + PageRank (0,2)"},
    };

    private static Object anchor() throws IOException {
        String split = "test";
        List<Topic> topics = EvalIo.readTopics(Paths.get("eval/topics.tsv"), split);
        Map<String, Map<Integer, Integer>> qrels = EvalIo.readQrels(Paths.get("eval/qrels/" + split + ".tsv"));
        List<String> qids = new ArrayList<>();
        for (Topic t : topics) if (qrels.containsKey(t.qid) && Metrics.numRelevant(qrels.get(t.qid)) > 0) qids.add(t.qid);
        Path dir = Paths.get("eval/runs/" + split + "_extract");
        Map<String, double[][]> pq = new LinkedHashMap<>();
        Map<String, Double> condensed = new LinkedHashMap<>();
        for (String[] a : ANCHOR_RUNS) {
            Path p = dir.resolve(a[0] + ".run");
            if (!Files.exists(p)) throw new NoSuchFileException(p.toString());
            Map<String, List<Integer>> run = EvalIo.readRun(p);
            pq.put(a[0], EvalMain.perQuery(run, qrels, qids));
            double s = 0;     // danh sách rút gọn: bỏ tài liệu chưa có nhãn (giảm thiên lệch pool)
            for (String q : qids) {
                List<Integer> c = new ArrayList<>();
                for (int d : run.getOrDefault(q, List.of())) if (qrels.get(q).containsKey(d)) c.add(d);
                s += Metrics.ndcg(c, qrels.get(q), 10);
            }
            condensed.put(a[0], r4(s / qids.size()));
        }
        double[] base = col(pq.get(ANCHOR_RUNS[0][0]), 0);
        double[] praw = new double[ANCHOR_RUNS.length - 1];
        for (int i = 1; i < ANCHOR_RUNS.length; i++)
            praw[i - 1] = Significance.randomizationP(col(pq.get(ANCHOR_RUNS[i][0]), 0), base, PERM_ROUNDS, 42 + i);
        double[] padj = Significance.holm(praw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < ANCHOR_RUNS.length; i++) {
            double[] a = col(pq.get(ANCHOR_RUNS[i][0]), 0);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", ANCHOR_RUNS[i][0]);
            x.put("label", ANCHOR_RUNS[i][1]);
            x.put("ndcg", r4(Significance.mean(a)));
            x.put("condensed", condensed.get(ANCHOR_RUNS[i][0]));
            x.put("judged", r4(Significance.mean(col(pq.get(ANCHOR_RUNS[i][0]), 5))));
            if (i > 0) {
                double[] ci = Significance.bootstrapCi(a, base, BOOT_ROUNDS, 7);
                x.put("delta", r4(Significance.mean(a) - Significance.mean(base)));
                x.put("lo", r4(ci[0]));
                x.put("hi", r4(ci[1]));
                x.put("pHolm", r4(padj[i - 1]));
            }
            out.add(x);
        }
        return out;
    }

    private static double[] col(double[][] m, int c) {
        double[] x = new double[m.length];
        for (int i = 0; i < m.length; i++) x[i] = m[i][c];
        return x;
    }

    // ------------------------------------------------------------------ latency

    private static Object latency() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get("eval/report/latency.tsv"), StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("system\t")) continue;
            String[] f = l.split("\t");
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", f[0]);
            x.put("ms", Double.parseDouble(f[1]));
            x.put("note", f.length > 2 ? f[2] : "");
            out.add(x);
        }
        return out;
    }
}
