package vn.hust.ir.crawler;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Kiểm thử tích hợp cơ chế cập nhật trên một website cục bộ có thể thay đổi giữa hai lần chạy:
 * lần 1 crawl từ đầu; sau đó thêm trang mới, xóa một trang, sửa nội dung một trang; lần 2 (update) phải
 * phát hiện đúng: URL mới được thêm, URL biến mất bị loại, URL trùng/rỗng không lọt vào danh sách.
 */
public class UpdateMechanismTest {
    private static String words(String seed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append(seed).append(i).append(' ');
        return sb.toString();
    }

    private static String page(String title, String body, String... links) {
        StringBuilder sb = new StringBuilder("<html><head><title>" + title + "</title></head><body><nav><a href='/x'>menu</a></nav><main><p>")
                .append(body).append("</p></main>");
        for (String l : links) sb.append("<a href='").append(l).append("'>link ").append(l).append("</a> ");
        return sb.append("</body></html>").toString();
    }

    private static void crawl(Config cfg) throws Exception {
        try (UrlStore store = new UrlStore(cfg.dataDir.resolve("index"))) {
            Map<String, UrlRecord> recs = store.loadAll();
            new Crawler(cfg, store, recs).run();
            Exporter.export(recs, cfg.outDir);
        }
    }

    @Test
    public void updateDetectsNewChangedAndRemovedPages() throws Exception {
        Map<String, String> site = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = site.get(ex.getRequestURI().getPath());
            byte[] b = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(body == null ? 404 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            Path tmp = Files.createTempDirectory("hustcrawl");
            Config cfg = new Config();
            cfg.rootDomain = "127.0.0.1";
            cfg.seeds.clear();
            cfg.seeds.add("http://127.0.0.1:" + server.getAddress().getPort() + "/index.html");
            cfg.useSitemaps = false;
            cfg.threads = 2;
            cfg.delayMs = 0;
            cfg.globalDelayMs = 0;
            cfg.hostDelayMs = Map.of();
            cfg.hubMinLinks = 1;
            cfg.hubRevisitHours = 0;
            cfg.revisitDays = 0;
            cfg.dataDir = tmp.resolve("data");
            cfg.outDir = tmp.resolve("out");
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            // ---- lần 1
            site.put("/index.html", page("Home", words("home"), "/a.html", "/b.html", "/empty.html", "/dup.html", "/gone.html"));
            site.put("/a.html", page("A", words("alpha")));
            site.put("/b.html", page("B", words("beta")));
            site.put("/dup.html", page("A copy", words("alpha")));      // trùng nội dung với /a.html
            site.put("/empty.html", page("Empty", "too short"));         // không đủ nội dung
            crawl(cfg);

            List<String> valid1 = Files.readAllLines(cfg.outDir.resolve("urls_valid.txt"));
            assertEquals(List.of(base + "/a.html", base + "/b.html", base + "/index.html"), valid1);
            String invalid = Files.readString(cfg.outDir.resolve("urls_invalid.tsv"));
            assertTrue(invalid.contains(base + "/empty.html\t200\tno-content"));
            assertTrue(invalid.contains(base + "/gone.html\t404\thttp-404"));
            assertTrue(invalid.matches("(?s).*" + base + "/dup.html\\t200\\tduplicate:.*"));

            // ---- website thay đổi
            Thread.sleep(20);
            site.put("/index.html", page("Home", words("home"), "/a.html", "/b.html", "/empty.html", "/dup.html", "/gone.html", "/c.html"));
            site.put("/c.html", page("C", words("gamma")));              // trang mới
            site.remove("/b.html");                                       // trang biến mất
            site.put("/a.html", page("A", words("alpha-v2")));           // nội dung đổi

            // ---- lần 2 (update)
            crawl(cfg);
            List<String> valid2 = Files.readAllLines(cfg.outDir.resolve("urls_valid.txt"));
            // /dup.html không còn là bản sao của /a.html (a đã đổi nội dung) nên trở thành hợp lệ
            assertEquals(List.of(base + "/a.html", base + "/c.html", base + "/dup.html", base + "/index.html"), valid2);

            List<String> added = Files.list(cfg.outDir.resolve("updates")).filter(p -> p.getFileName().toString().startsWith("urls_added_"))
                    .sorted().reduce((x, y) -> y).map(p -> {
                        try {
                            return Files.readAllLines(p);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }).get();
            List<String> removed = Files.list(cfg.outDir.resolve("updates")).filter(p -> p.getFileName().toString().startsWith("urls_removed_"))
                    .map(p -> {
                        try {
                            return Files.readAllLines(p);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }).flatMap(List::stream).collect(Collectors.toList());
            assertEquals(List.of(base + "/c.html", base + "/dup.html"), added);
            assertEquals(List.of(base + "/b.html"), removed);

            // nội dung /a.html đổi => lastChanged được cập nhật
            try (UrlStore store = new UrlStore(cfg.dataDir.resolve("index"))) {
                UrlRecord a = store.loadAll().get(base + "/a.html");
                assertTrue(a.lastChanged > a.firstSeen);
            }
        } finally {
            server.stop(0);
        }
    }
}
