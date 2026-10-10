package vn.hust.ir.extract;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.hust.ir.crawler.Config;
import vn.hust.ir.crawler.UrlNormalizer;

/**
 * P5/P6 — Dựng đồ thị liên kết cuối cùng từ pages.jsonl + links.jsonl:
 * chuẩn hóa đích về URL chuẩn (theo chuyển hướng/trùng lặp/canonical đã biết), gộp văn bản neo theo đích,
 * thêm nút cho ảnh/tài liệu/URL chưa tải, tính PageRank. Ghi {@code pages.final.jsonl}, {@code links.final.jsonl}, {@code graph_stats.json}.
 */
public final class GraphBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> IMG_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico", "tif", "tiff");
    private static final Set<String> DOC_EXT = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "txt", "rtf", "zip", "rar");
    private static final Set<String> JUNK_ANCHOR = Set.of("xem thêm", "đọc tiếp", "chi tiết", "xem chi tiết", "tải về",
            "tải xuống", "download", "click here", "here", "read more", "more", "»", "›", "«", "‹", "...", "…", "tại đây",
            "xem tiếp", "tìm hiểu thêm", "đọc thêm", "xem tất cả", "trang chủ", "home", "next", "previous", "trước", "sau",
            "tiếp", "quay lại", "back", "top", "đăng ký", "đăng nhập", "login", "search", "tìm kiếm");

    private final Config cfg = new Config();
    private final Map<String, String> datasetBySchemeless = new HashMap<>();
    private final Map<String, String> forward = new HashMap<>();      // schemeless(url) -> url đích (chuyển hướng/trùng)

    /** Nút trong đồ thị. */
    private static final class Node {
        String id, url, host, kind, docType;
        boolean inDataset;
        int idx;
        int inlinks, inlinksContent;
        final Set<Integer> srcs = new HashSet<>();
        final Map<String, Integer> anchors = new HashMap<>();
        final Map<String, Integer> alts = new HashMap<>();
        final Map<String, Integer> captions = new HashMap<>();
        final Map<String, Integer> titles = new HashMap<>();
        final Set<String> srcHosts = new HashSet<>();
        int outlinks, outlinksContent;
    }

    public void build(Path extractDir, Path invalidTsv, Path rawManifest) throws Exception {
        Path pagesIn = extractDir.resolve("pages.jsonl");
        // --- 1. trang trong bộ dữ liệu
        List<Map<String, Object>> pages = new ArrayList<>();
        Map<String, Node> nodes = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(pagesIn, StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.isBlank()) continue;
                Map<String, Object> p = JSON.readValue(l, new TypeReference<Map<String, Object>>() { });
                pages.add(p);
                Node n = new Node();
                n.url = (String) p.get("url");
                n.id = (String) p.get("_id");
                n.host = (String) p.get("host");
                n.kind = (String) p.get("kind");
                n.docType = (String) p.get("docType");
                n.inDataset = true;
                n.idx = nodes.size();
                nodes.put(n.url, n);
                datasetBySchemeless.putIfAbsent(UrlNormalizer.schemeless(n.url), n.url);
                String fin = (String) p.get("finalUrl");
                if (fin != null && !fin.equals(n.url)) forward.putIfAbsent(UrlNormalizer.schemeless(fin), n.url);
            }
        }
        // --- 2. bản đồ chuyển hướng / trùng lặp từ crawler và từ bước chụp
        int fw = 0;
        if (Files.exists(invalidTsv)) {
            try (BufferedReader r = Files.newBufferedReader(invalidTsv, StandardCharsets.UTF_8)) {
                String l;
                while ((l = r.readLine()) != null) {
                    String[] f = l.split("\t");
                    if (f.length < 3) continue;
                    String reason = f[2];
                    for (String pre : new String[]{"redirect:", "duplicate:", "canonical-dup:"})
                        if (reason.startsWith(pre)) {
                            forward.putIfAbsent(UrlNormalizer.schemeless(f[0]), reason.substring(pre.length()));
                            fw++;
                        }
                }
            }
        }
        if (Files.exists(rawManifest)) {
            try (BufferedReader r = Files.newBufferedReader(rawManifest, StandardCharsets.UTF_8)) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.isBlank()) continue;
                    RawStore.Entry e = JSON.readValue(l, RawStore.Entry.class);
                    String prev = e.url;
                    for (String t : e.redirects) {
                        forward.putIfAbsent(UrlNormalizer.schemeless(prev), t);
                        prev = t;
                    }
                }
            }
        }
        System.out.println("Trang: " + pages.size() + "; bản đồ chuyển hướng/trùng: " + forward.size() + " (invalid.tsv: " + fw + ")");

        // --- 3. duyệt liên kết, gộp theo (src, dst chuẩn, kind, anchor)
        long edgesIn = 0, edgesOut = 0, resolvedViaMap = 0;
        Path linksOut = extractDir.resolve("links.final.jsonl");
        try (BufferedReader r = Files.newBufferedReader(extractDir.resolve("links.jsonl"), StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(linksOut, StandardCharsets.UTF_8)) {
            String curSrc = null;
            Map<String, Map<String, Object>> group = new LinkedHashMap<>();
            String l;
            while (true) {
                l = r.readLine();
                Map<String, Object> e = null;
                if (l != null) {
                    if (l.isBlank()) continue;
                    e = JSON.readValue(l, new TypeReference<Map<String, Object>>() { });
                    edgesIn++;
                }
                String src = e == null ? null : (String) e.get("src");
                if (curSrc != null && (e == null || !src.equals(curSrc))) {
                    for (Map<String, Object> g : group.values()) {
                        w.write(JSON.writeValueAsString(g));
                        w.newLine();
                        edgesOut++;
                    }
                    group.clear();
                }
                if (e == null) break;
                curSrc = src;
                Node s = nodes.get(src);
                String dstRaw = (String) e.get("dst");
                String dst = resolve(dstRaw);
                if (!dst.equals(dstRaw)) resolvedViaMap++;
                if (dst.equals(src)) continue;
                Node d = nodes.get(dst);
                if (d == null) {
                    d = new Node();
                    d.url = dst;
                    d.id = RawStore.sha1(dst);
                    d.host = hostOf(dst);
                    d.kind = classify(dst, (String) e.get("kind"));
                    d.docType = d.kind.equals("image") ? "image" : DocExtractor.docType("", dst);
                    d.idx = nodes.size();
                    nodes.put(dst, d);
                }
                String kind = (String) e.get("kind");
                String anchor = str(e.get("anchor"));
                boolean inContent = Boolean.TRUE.equals(e.get("inContent"));
                String key = d.id + "\u0001" + kind + "\u0001" + anchor;
                Map<String, Object> g = group.get(key);
                if (g != null) {
                    g.merge("count", 1, (a, b) -> ((Number) a).intValue() + 1);
                    if (inContent) g.put("inContent", true);
                    continue;
                }
                g = new LinkedHashMap<>();
                g.put("src", s != null ? s.id : RawStore.sha1(src));
                g.put("srcUrl", src);
                g.put("dst", d.id);
                g.put("dstUrl", dst);
                g.put("kind", kind);
                g.put("anchor", anchor);
                for (String k : new String[]{"alt", "title", "caption"}) if (e.get(k) != null) g.put(k, e.get(k));
                g.put("inContent", inContent);
                g.put("srcHost", s != null ? s.host : hostOf(src));
                g.put("dstHost", d.host);
                g.put("count", e.get("count") == null ? 1 : e.get("count"));
                group.put(key, g);
                // thống kê nút đích
                boolean first = s != null ? d.srcs.add(s.idx) : d.srcs.add(-1 - d.srcs.size());
                if (first) {
                    d.inlinks++;
                    if (inContent) d.inlinksContent++;
                }
                d.srcHosts.add(s != null ? s.host : hostOf(src));
                if (!isJunk(anchor)) d.anchors.merge(anchor, 1, Integer::sum);
                String alt = str(e.get("alt"));
                if (!alt.isEmpty()) d.alts.merge(alt, 1, Integer::sum);
                String cap = str(e.get("caption"));
                if (!cap.isEmpty()) d.captions.merge(cap, 1, Integer::sum);
                String tt = str(e.get("title"));
                if (!tt.isEmpty()) d.titles.merge(tt, 1, Integer::sum);
                if (s != null) {
                    s.outlinks++;
                    if (inContent) s.outlinksContent++;
                }
            }
        }
        System.out.println("Cạnh: " + edgesIn + " -> " + edgesOut + " (đích được quy về URL chuẩn: " + resolvedViaMap + ")");

        // --- 4. PageRank trên các nút trong bộ dữ liệu (cạnh <a>), hai biến thể: mọi liên kết / chỉ liên kết trong nội dung
        List<Node> list = new ArrayList<>(nodes.values());
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            list.get(i).idx = i;
            idx.put(list.get(i).id, i);
        }
        List<int[]> all = new ArrayList<>(), content = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(linksOut, StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                Map<String, Object> e = JSON.readValue(l, new TypeReference<Map<String, Object>>() { });
                if (!"a".equals(e.get("kind"))) continue;
                Integer a = idx.get(e.get("src")), b = idx.get(e.get("dst"));
                if (a == null || b == null) continue;
                all.add(new int[]{a, b});
                if (Boolean.TRUE.equals(e.get("inContent"))) content.add(new int[]{a, b});
            }
        }
        double[] prAll = pageRank(list.size(), all), prContent = pageRank(list.size(), content);

        // --- 5. ghi pages.final.jsonl: trang trong bộ dữ liệu + nút bổ sung (ảnh, tài liệu, URL chưa tải)
        Map<String, Integer> byKind = new TreeMap<>();
        try (BufferedWriter w = Files.newBufferedWriter(extractDir.resolve("pages.final.jsonl"), StandardCharsets.UTF_8)) {
            for (Map<String, Object> p : pages) {
                Node n = nodes.get((String) p.get("url"));
                decorate(p, n, prAll[n.idx], prContent[n.idx]);
                p.put("inDataset", true);
                w.write(JSON.writeValueAsString(p));
                w.newLine();
                byKind.merge("dataset:" + p.get("kind"), 1, Integer::sum);
            }
            for (Node n : list) {
                if (n.inDataset) continue;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("_id", n.id);
                p.put("url", n.url);
                p.put("host", n.host);
                p.put("kind", n.kind);
                p.put("docType", n.docType);
                p.put("inDataset", false);
                decorate(p, n, prAll[n.idx], prContent[n.idx]);
                w.write(JSON.writeValueAsString(p));
                w.newLine();
                byKind.merge("stub:" + n.kind, 1, Integer::sum);
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pages", pages.size());
        stats.put("nodes", list.size());
        stats.put("edgesRaw", edgesIn);
        stats.put("edgesFinal", edgesOut);
        stats.put("nodesByKind", byKind);
        stats.put("edgesAnchor", all.size());
        stats.put("edgesAnchorInContent", content.size());
        JSON.writerWithDefaultPrettyPrinter().writeValue(extractDir.resolve("graph_stats.json").toFile(), stats);
        System.out.println("Đồ thị: " + stats);
    }

    private static void decorate(Map<String, Object> p, Node n, double prAll, double prContent) {
        p.put("inlinks", n.inlinks);
        p.put("inlinksContent", n.inlinksContent);
        p.put("inlinkHosts", n.srcHosts.size());
        p.put("outlinksResolved", n.outlinks);
        p.put("pagerank", prAll);
        p.put("pagerankContent", prContent);
        p.put("anchors", top(n.anchors, 15));
        if (!n.alts.isEmpty()) p.put("altTexts", top(n.alts, 5));
        if (!n.captions.isEmpty()) p.put("captions", top(n.captions, 3));
        if (!n.titles.isEmpty()) p.put("linkTitles", top(n.titles, 3));
    }

    private static List<Map<String, Object>> top(Map<String, Integer> m, int k) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, es.size()); i++) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("text", es.get(i).getKey());
            o.put("count", es.get(i).getValue());
            out.add(o);
        }
        return out;
    }

    static double[] pageRank(int n, List<int[]> edges) {
        double[] pr = new double[n];
        if (n == 0) return pr;
        int[] outDeg = new int[n];
        for (int[] e : edges) outDeg[e[0]]++;
        java.util.Arrays.fill(pr, 1.0 / n);
        double d = 0.85;
        for (int it = 0; it < 40; it++) {
            double[] next = new double[n];
            double dangling = 0;
            for (int i = 0; i < n; i++) if (outDeg[i] == 0) dangling += pr[i];
            for (int[] e : edges) next[e[1]] += pr[e[0]] / outDeg[e[0]];
            double base = (1 - d) / n + d * dangling / n;
            for (int i = 0; i < n; i++) next[i] = base + d * next[i];
            pr = next;
        }
        return pr;
    }

    /** Đưa URL về dạng chuẩn của bộ dữ liệu nếu biết (theo chuyển hướng/trùng lặp/canonical). */
    String resolve(String url) {
        String cur = url;
        for (int hop = 0; hop < 8; hop++) {
            String key = UrlNormalizer.schemeless(cur);
            String ds = datasetBySchemeless.get(key);
            if (ds != null) return ds;
            String nxt = forward.get(key);
            if (nxt == null || nxt.equals(cur)) break;
            cur = nxt;
        }
        return cur;
    }

    private String classify(String url, String edgeKind) {
        String p = url.toLowerCase(Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int dot = p.lastIndexOf('.');
        String ext = dot >= 0 && p.length() - dot <= 6 ? p.substring(dot + 1) : "";
        String host = hostOf(url);
        if (IMG_EXT.contains(ext) || "img".equals(edgeKind)) return "image";
        if (DOC_EXT.contains(ext)) return "document";
        if (!cfg.inScope(host)) return "external";
        return "html";
    }

    private static String hostOf(String url) {
        try {
            return UrlNormalizer.host(url);
        } catch (Exception e) {
            return "";
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    static boolean isJunk(String a) {
        if (a == null) return true;
        String t = a.trim().toLowerCase(Locale.ROOT);
        return t.length() < 2 || JUNK_ANCHOR.contains(t) || t.startsWith("http") || t.matches("[\\d\\s.,/-]+");
    }
}
