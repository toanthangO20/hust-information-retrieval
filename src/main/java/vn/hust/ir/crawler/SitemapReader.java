package vn.hust.ir.crawler;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Đọc sitemap (và sitemap index) để phát hiện URL mới / URL có lastmod thay đổi — nền tảng cho cơ chế cập nhật. */
public final class SitemapReader {
    public static final class Entry {
        public final String url;
        public final long lastmod;

        Entry(String url, long lastmod) {
            this.url = url;
            this.lastmod = lastmod;
        }
    }

    private static final Pattern LOC = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>", Pattern.DOTALL);
    private static final Pattern LASTMOD = Pattern.compile("<lastmod>\\s*(.*?)\\s*</lastmod>", Pattern.DOTALL);
    private static final int MAX_SITEMAPS = 100;

    private final Fetcher fetcher;
    private final long delayMs;

    public SitemapReader(Fetcher fetcher, long delayMs) {
        this.fetcher = fetcher;
        this.delayMs = delayMs;
    }

    public List<Entry> read(String sitemapUrl) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        walk(sitemapUrl, 0, seen, out);
        return out;
    }

    private void walk(String url, int depth, Set<String> seen, List<Entry> out) {
        if (depth > 2 || seen.size() >= MAX_SITEMAPS || !seen.add(url)) return;
        Fetcher.Result r = fetcher.get(url, true);
        for (int attempt = 1; attempt <= 4 && (r.status == 429 || r.status == 503); attempt++) {
            try {
                Thread.sleep(Math.max(r.retryAfterMs, 2000L * attempt));   // server quá tải: chờ rồi thử lại
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            r = fetcher.get(url, true);
        }
        if (r.status != 200 || r.body == null) {
            Crawler.log("không đọc được sitemap %s (HTTP %d %s)", url, r.status, r.error == null ? "" : r.error);
            return;
        }
        String xml = new String(r.body, StandardCharsets.UTF_8);
        sleep();
        if (xml.contains("<sitemapindex")) {
            Matcher m = LOC.matcher(xml);
            while (m.find()) walk(unescape(m.group(1)), depth + 1, seen, out);
            return;
        }
        for (String block : xml.split("<url>")) {
            Matcher m = LOC.matcher(block);
            if (!m.find()) continue;
            long lm = 0;
            Matcher l = LASTMOD.matcher(block);
            if (l.find()) lm = parseDate(l.group(1));
            out.add(new Entry(unescape(m.group(1)), lm));
        }
    }

    private void sleep() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static long parseDate(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception ignored) { }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) { }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) { }
        return 0;
    }

    private static String unescape(String s) {
        s = s.replace("<![CDATA[", "").replace("]]>", "").trim();
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
    }
}
