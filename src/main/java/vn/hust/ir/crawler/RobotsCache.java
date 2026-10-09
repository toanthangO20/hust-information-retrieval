package vn.hust.ir.crawler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Giao thức robots.txt (Chương 10): tải một lần cho mỗi host, lưu trong bộ nhớ đệm,
 * tuân thủ Allow/Disallow (khớp tiền tố dài nhất, hỗ trợ * và $), Crawl-delay và Sitemap.
 */
public final class RobotsCache {
    public static final String AGENT_TOKEN = "hustirbot";

    /** Tập quy tắc của một host. */
    public static final class Rules {
        private final List<Object[]> rules = new ArrayList<>();   // {Boolean allow, Pattern, Integer length}
        public long crawlDelayMs;
        public final List<String> sitemaps = new ArrayList<>();

        public boolean allowed(String pathAndQuery) {
            int best = -1;
            boolean allow = true;
            for (Object[] r : rules) {
                int len = (Integer) r[2];
                if (((Pattern) r[1]).matcher(pathAndQuery).lookingAt()) {
                    boolean a = (Boolean) r[0];
                    if (len > best || (len == best && a)) {
                        best = len;
                        allow = a;
                    }
                }
            }
            return allow;
        }
    }

    private final ConcurrentHashMap<String, Rules> cache = new ConcurrentHashMap<>();
    private final Fetcher fetcher;

    public RobotsCache(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    public Rules forHost(String scheme, String host) {
        return cache.computeIfAbsent(host, h -> {
            Fetcher.Result r = fetcher.get(scheme + "://" + h + "/robots.txt", true);
            if (r.status == 200 && r.body != null) return parse(new String(r.body, StandardCharsets.UTF_8));
            if (r.error != null && scheme.equals("https")) {
                Fetcher.Result r2 = fetcher.get("http://" + h + "/robots.txt", true);
                if (r2.status == 200 && r2.body != null) return parse(new String(r2.body, StandardCharsets.UTF_8));
            }
            return new Rules();     // không có robots.txt (hoặc không tải được) => cho phép
        });
    }

    public boolean allowed(String url) {
        URI u = URI.create(url);
        Rules r = forHost(u.getScheme(), u.getHost());
        String p = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) p += "?" + u.getRawQuery();
        return r.allowed(p);
    }

    public static Rules parse(String text) {
        Rules out = new Rules();
        List<List<String>> groupAgents = new ArrayList<>();
        List<List<Object[]>> groupRules = new ArrayList<>();
        List<Long> groupDelay = new ArrayList<>();
        boolean lastWasAgent = false;
        for (String raw : text.split("\\R")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String key = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(c + 1).trim();
            switch (key) {
                case "user-agent":
                    if (!lastWasAgent || groupAgents.isEmpty()) {
                        groupAgents.add(new ArrayList<>());
                        groupRules.add(new ArrayList<>());
                        groupDelay.add(0L);
                    }
                    groupAgents.get(groupAgents.size() - 1).add(val.toLowerCase(Locale.ROOT));
                    lastWasAgent = true;
                    continue;
                case "allow":
                case "disallow":
                    if (!groupAgents.isEmpty() && !val.isEmpty())
                        groupRules.get(groupRules.size() - 1)
                                .add(new Object[]{key.equals("allow"), toRegex(val), val.length()});
                    break;
                case "crawl-delay":
                    try {
                        if (!groupAgents.isEmpty())
                            groupDelay.set(groupDelay.size() - 1, (long) (Double.parseDouble(val) * 1000));
                    } catch (NumberFormatException ignored) { }
                    break;
                case "sitemap":
                    out.sitemaps.add(val);
                    break;
                default:
                    break;
            }
            lastWasAgent = false;
        }
        int pick = -1;
        for (int i = 0; i < groupAgents.size(); i++)
            for (String a : groupAgents.get(i))
                if (!a.equals("*") && AGENT_TOKEN.contains(a) && !a.isEmpty()) pick = i;
        if (pick < 0)
            for (int i = 0; i < groupAgents.size(); i++)
                if (groupAgents.get(i).contains("*")) pick = i;
        if (pick >= 0) {
            out.rules.addAll(groupRules.get(pick));
            out.crawlDelayMs = groupDelay.get(pick);
        }
        return out;
    }

    private static Pattern toRegex(String robotsPattern) {
        boolean end = robotsPattern.endsWith("$");
        String p = end ? robotsPattern.substring(0, robotsPattern.length() - 1) : robotsPattern;
        StringBuilder sb = new StringBuilder();
        for (char ch : p.toCharArray()) {
            if (ch == '*') sb.append(".*");
            else sb.append(Pattern.quote(String.valueOf(ch)));
        }
        if (end) sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
