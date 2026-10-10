package vn.hust.ir.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Máy chủ HTTP nhỏ: giao diện tĩnh (trong jar hoặc thư mục --web-dir) và API JSON /api/search, /api/stats. */
public final class WebServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml", "ttf", "font/ttf", "woff2", "font/woff2", "png", "image/png", "ico", "image/x-icon");

    private final SearchEngine engine;
    private final Path manifest;
    private final Path webDir;      // null: đọc từ classpath

    public WebServer(SearchEngine engine, Path manifest, Path webDir) {
        this.engine = engine;
        this.manifest = manifest;
        this.webDir = webDir;
    }

    public HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/api/search", this::search);
        s.createContext("/api/stats", this::stats);
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
