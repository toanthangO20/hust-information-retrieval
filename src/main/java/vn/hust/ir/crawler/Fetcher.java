package vn.hust.ir.crawler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

/** Tải trang bằng HttpClient của JDK. Không tự động theo chuyển hướng để crawler kiểm soát phạm vi/robots. */
public final class Fetcher {
    public enum Kind { HTML, DOCUMENT, OTHER }

    public static final class Result {
        public int status;
        public String location;
        public String lastModified;
        public long retryAfterMs;
        public String contentType = "";
        public String charset;
        public String error;
        public byte[] body;
        public Kind kind = Kind.OTHER;
    }

    private static final long MAX_HTML = 4L << 20;
    private static final long MAX_DOC = 20L << 20;

    /** Watchdog: đóng luồng đọc nếu server treo giữa chừng khi trả body (timeout của HttpClient chỉ áp dụng tới khi có header). */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fetch-watchdog");
        t.setDaemon(true);
        return t;
    });
    private static final long BODY_DEADLINE_SEC = 60;

    private final HttpClient client;
    private final String userAgent;

    public Fetcher(Config cfg) {
        this.userAgent = cfg.userAgent;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Result get(String url) {
        return get(url, false);
    }

    /** forceBody=true: đọc body bất kể content-type (dùng cho robots.txt, sitemap). */
    public Result get(String url, boolean forceBody) {
        Result r = new Result();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/pdf,*/*;q=0.5")
                    .header("Accept-Language", "vi,en;q=0.8")
                    .GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            InputStream in = resp.body();
            ScheduledFuture<?> dog = WATCHDOG.schedule(() -> {
                try {
                    in.close();
                } catch (Exception ignored) { }
            }, BODY_DEADLINE_SEC, TimeUnit.SECONDS);
            try (InputStream body = in) {
                r.status = resp.statusCode();
                r.location = resp.headers().firstValue("location").orElse(null);
                r.lastModified = resp.headers().firstValue("last-modified").orElse(null);
                try {
                    r.retryAfterMs = Long.parseLong(resp.headers().firstValue("retry-after").orElse("0").trim()) * 1000;
                } catch (NumberFormatException ignored) { }
                String ct = resp.headers().firstValue("content-type").orElse("");
                r.contentType = ct.toLowerCase(Locale.ROOT);
                int cs = r.contentType.indexOf("charset=");
                if (cs >= 0) r.charset = r.contentType.substring(cs + 8).replace("\"", "").split(";")[0].trim();
                r.kind = classify(r.contentType, url);
                if (r.status == 200 && (forceBody || r.kind != Kind.OTHER))
                    r.body = readLimited(in, r.kind == Kind.DOCUMENT ? MAX_DOC : MAX_HTML);
            } finally {
                dog.cancel(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.error = "interrupted";
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName();
        }
        return r;
    }

    public static Kind classify(String contentType, String url) {
        String ct = contentType == null ? "" : contentType;
        if (ct.contains("html")) return Kind.HTML;
        if (ct.contains("pdf") || ct.contains("msword") || ct.contains("officedocument")
                || ct.contains("ms-excel") || ct.contains("ms-powerpoint") || ct.contains("opendocument")
                || ct.startsWith("text/plain")) return Kind.DOCUMENT;
        if (ct.isEmpty() || ct.contains("octet-stream")) {
            String p = url.toLowerCase(Locale.ROOT);
            int q = p.indexOf('?');
            if (q >= 0) p = p.substring(0, q);
            if (p.matches(".*\\.(pdf|docx?|pptx?|xlsx?)$")) return Kind.DOCUMENT;
        }
        return Kind.OTHER;
    }

    private static byte[] readLimited(InputStream in, long limit) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total >= limit) break;
        }
        return out.toByteArray();
    }
}
