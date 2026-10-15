package vn.hust.ir.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import vn.hust.ir.eval.LiveCompare;

/**
 * Máy chủ HTTP nhỏ: giao diện tĩnh (trong jar hoặc thư mục --web-dir) và API JSON /api/search, /api/stats, /api/export;
 * thêm /api/compare-info, /api/compare (so sánh trực tiếp các mô hình truy hồi) khi có {@link LiveCompare}.
 */
public final class WebServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml", "ttf", "font/ttf", "woff2", "font/woff2", "png", "image/png", "ico", "image/x-icon");

    private static final int MAX_EXPORT = 1000;

    private final SearchEngine engine;
    private final Path manifest;
    private final Path webDir;      // null: đọc từ classpath
    private LiveCompare compare;    // null: tắt trang so sánh

    public WebServer(SearchEngine engine, Path manifest, Path webDir) {
        this.engine = engine;
        this.manifest = manifest;
        this.webDir = webDir;
    }

    public WebServer withCompare(LiveCompare c) {
        this.compare = c;
        return this;
    }

    public HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/api/search", this::search);
        s.createContext("/api/export", this::export);
        s.createContext("/api/stats", this::stats);
        s.createContext("/api/compare-info", this::compareInfo);
        s.createContext("/api/compare", this::compare);
        s.createContext("/", this::staticFile);
        s.setExecutor(Executors.newFixedThreadPool(8));
        s.start();
        return s;
    }

    private void search(HttpExchange ex) throws IOException {
        try {
            Map<String, String> p = params(ex.getRequestURI().getRawQuery());
            SearchEngine.Result r = engine.search(p.get("q"), p.get("host"), p.get("type"),
                    intOr(p.get("page"), 1), intOr(p.get("size"), 10));
            send(ex, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(r));
        } catch (Exception e) {
            send(ex, 500, "application/json; charset=utf-8",
                    JSON.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    /** Xuất top n kết quả: GET /api/export?q=&host=&type=&n=&format=csv|txt (n tối đa MAX_EXPORT). */
    private void export(HttpExchange ex) throws IOException {
        try {
            Map<String, String> p = params(ex.getRequestURI().getRawQuery());
            boolean csv = !"txt".equalsIgnoreCase(p.get("format"));
            int n = Math.min(MAX_EXPORT, Math.max(1, intOr(p.get("n"), 100)));
            String q = p.get("q") == null ? "" : p.get("q");
            List<SearchEngine.Hit> hits = new ArrayList<>();
            long total = 0;
            for (int page = 1; hits.size() < n; page++) {
                SearchEngine.Result r = engine.search(q, p.get("host"), p.get("type"), page, 50);
                total = r.total;
                if (r.hits.isEmpty()) break;
                hits.addAll(r.hits);
                if (hits.size() >= r.total) break;
            }
            if (hits.size() > n) hits = hits.subList(0, n);

            StringBuilder sb = new StringBuilder();
            if (csv) {
                sb.append('﻿');     // BOM để Excel đọc đúng tiếng Việt
                sb.append("rank,title,url,host,type,words,score,snippet\r\n");
                int rank = 0;
                for (SearchEngine.Hit h : hits) {
                    sb.append(++rank).append(',').append(csvCell(h.title)).append(',').append(csvCell(h.url)).append(',')
                            .append(csvCell(h.host)).append(',').append(csvCell(h.type)).append(',').append(h.words).append(',')
                            .append(String.format(Locale.ROOT, "%.4f", h.score)).append(',')
                            .append(csvCell(plain(h.snippet))).append("\r\n");
                }
            } else {
                sb.append("Từ khóa: ").append(q).append("\r\n");
                sb.append("Kết quả: ").append(hits.size()).append(" / ").append(total).append("\r\n\r\n");
                int rank = 0;
                for (SearchEngine.Hit h : hits) {
                    sb.append(++rank).append(". ").append(h.title == null || h.title.isBlank() ? "(không có tiêu đề)" : h.title).append("\r\n")
                            .append("   ").append(h.url).append("\r\n")
                            .append("   ").append(h.host).append(" · ").append(h.type).append(" · ").append(h.words)
                            .append(" từ · điểm ").append(String.format(Locale.ROOT, "%.4f", h.score)).append("\r\n")
                            .append("   ").append(plain(h.snippet)).append("\r\n\r\n");
                }
            }
            String base = "ket-qua-" + q.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
            if (base.equals("ket-qua-")) base = "ket-qua";
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"ket-qua." + (csv ? "csv" : "txt")
                    + "\"; filename*=UTF-8''" + java.net.URLEncoder.encode(base + (csv ? ".csv" : ".txt"), StandardCharsets.UTF_8).replace("+", "%20"));
            send(ex, 200, (csv ? "text/csv" : "text/plain") + "; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            send(ex, 500, "application/json; charset=utf-8",
                    JSON.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private static String csvCell(String s) {
        return '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"';
    }

    /** Bỏ thẻ tô sáng và giải mã thực thể HTML trong đoạn trích. */
    private static String plain(String html) {
        if (html == null) return "";
        String s = html.replaceAll("</?mark>", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#x([0-9A-Fa-f]+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find())
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    new String(Character.toChars(Integer.parseInt(m.group(1), 16)))));
        m.appendTail(sb);
        return sb.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private void compareInfo(HttpExchange ex) throws IOException {
        try {
            if (compare == null) throw new IllegalStateException("Trang so sánh chưa bật (serve chạy với --no-compare)");
            send(ex, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(compare.info()));
        } catch (Exception e) {
            send(ex, 500, "application/json; charset=utf-8",
                    JSON.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    /** GET /api/compare?q=&qid=&systems=bm25;hybrid:w=2&k=10 — top-k của từng mô hình, kèm nhãn qrels khi có qid. */
    private void compare(HttpExchange ex) throws IOException {
        try {
            if (compare == null) throw new IllegalStateException("Trang so sánh chưa bật (serve chạy với --no-compare)");
            Map<String, String> p = params(ex.getRequestURI().getRawQuery());
            String q = p.getOrDefault("q", "").strip();
            if (q.isEmpty()) throw new IllegalArgumentException("Thiếu từ khóa");
            List<String> systems = new ArrayList<>();
            for (String id : p.getOrDefault("systems", "bm25").split(";")) if (!id.isBlank()) systems.add(id.strip());
            int k = Math.min(20, Math.max(1, intOr(p.get("k"), 10)));
            String qid = p.get("qid") == null || p.get("qid").isBlank() ? null : p.get("qid");
            send(ex, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(compare.compare(q, qid, systems, k)));
        } catch (Exception e) {
            send(ex, 500, "application/json; charset=utf-8",
                    JSON.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void stats(HttpExchange ex) throws IOException {
        Map<String, Object> out = new HashMap<>();
        out.put("indexed", engine.numDocs());
        if (manifest != null && Files.exists(manifest)) {
            JsonNode m = JSON.readTree(manifest.toFile());
            for (String k : new String[]{"version", "created", "documents", "hosts", "total_words", "avg_words_per_doc", "by_doc_type", "by_host"})
                out.put(k, m.get(k));
        }
        send(ex, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(out));
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || path.contains("\\")) {
            send(ex, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body;
        if (webDir != null) {
            Path f = webDir.resolve(path.substring(1));
            body = Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        } else {
            try (InputStream in = WebServer.class.getResourceAsStream("/web" + path)) {
                body = in == null ? null : in.readAllBytes();
            }
        }
        if (body == null) {
            send(ex, 404, "text/plain; charset=utf-8", "Không tìm thấy".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String ext = path.substring(path.lastIndexOf('.') + 1);
        ex.getResponseHeaders().set("Cache-Control", ext.equals("ttf") ? "public, max-age=86400" : "no-cache");
        send(ex, 200, MIME.getOrDefault(ext, "application/octet-stream"), body);
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static Map<String, String> params(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int intOr(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
