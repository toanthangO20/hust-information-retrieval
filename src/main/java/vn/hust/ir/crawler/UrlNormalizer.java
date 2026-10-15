package vn.hust.ir.crawler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chuẩn hóa URL (Chương 10: "Chuẩn hóa URL") và lọc bẫy crawler. */
public final class UrlNormalizer {
    private UrlNormalizer() {}

    // [DEMO:url-junk-params] tham số rác bị bỏ
    private static final Set<String> DROP_PARAMS = Set.of("fbclid", "gclid", "phpsessid", "sid", "sessionid",
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "zarsrc");
    private static final Set<String> SKIP_EXT = Set.of("jpg", "jpeg", "png", "gif", "svg", "webp", "ico", "bmp",
            "css", "js", "json", "xml", "zip", "rar", "7z", "tar", "gz", "mp3", "mp4", "avi", "mov", "wmv", "flv",
            "woff", "woff2", "ttf", "eot", "exe", "msi", "apk", "rss", "atom", "dmg", "iso", "swf");
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");
    private static final Pattern PCT = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final String UNSAFE = "\"<>\\^`{|}";

    /** Trả về URL tuyệt đối đã chuẩn hóa, hoặc null nếu không phải liên kết http(s) hợp lệ. */
    // [DEMO:url-normalize] chuẩn hoá URL, chống bẫy
    public static String normalize(String base, String href) {
        if (href == null) return null;
        href = href.trim();
        if (href.isEmpty() || href.startsWith("#")) return null;
        Matcher sm = SCHEME.matcher(href);
        if (sm.find()) {
            String s = sm.group(1).toLowerCase(Locale.ROOT);
            if (!s.equals("http") && !s.equals("https")) return null;
        }
        try {
            URI u = new URI(encode(href));
            URI r = (base == null) ? u : new URI(encode(base)).resolve(u);
            r = r.normalize();
            String scheme = r.getScheme() == null ? null : r.getScheme().toLowerCase(Locale.ROOT);
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
            String host = r.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            int port = r.getPort();
            boolean defPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            String path = r.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            path = path.replaceAll("/{2,}", "/");
            String q = filterQuery(r.getRawQuery());
            if (q == null) return null;
            StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
            if (!defPort) sb.append(':').append(port);
            sb.append(upperPct(path));
            if (!q.isEmpty()) sb.append('?').append(upperPct(q));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String filterQuery(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        List<String> kept = new ArrayList<>();
        for (String p : raw.split("&")) {
            if (p.isEmpty()) continue;
            String key = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
            String k = key.toLowerCase(Locale.ROOT);
            if (DROP_PARAMS.contains(k) || k.startsWith("utm_")) continue;
            if (!kept.contains(p)) kept.add(p);
        }
        if (kept.size() > 4) return null;  // quá nhiều tham số: nhiều khả năng là bẫy/lọc động
        Collections.sort(kept);
        return String.join("&", kept);
    }

    private static String upperPct(String s) {
        Matcher m = PCT.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, m.group().toUpperCase(Locale.ROOT));
        m.appendTail(sb);
        return sb.toString();
    }

    private static String encode(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp <= 0x20 || cp >= 0x7f || UNSAFE.indexOf(cp) >= 0) {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8))
                    sb.append('%').append(String.format("%02X", b & 0xff));
            } else {
                sb.append((char) cp);
            }
        }
        return sb.toString();
    }

    /** Lọc bẫy crawler và các định dạng không mang nội dung văn bản. */
    public static boolean crawlable(String url) {
        if (url.length() > 300) return false;
        URI u = URI.create(url);
        String path = u.getRawPath();
        if (path == null) return true;
        String[] seg = path.split("/");
        if (seg.length > 12) return false;
        for (int i = 0; i < seg.length; i++) {
            if (seg[i].isEmpty()) continue;
            int rep = 0;
            for (String t : seg) if (t.equals(seg[i])) rep++;
            if (rep >= 3) return false;             // /a/a/a/... lặp đường dẫn
        }
        String last = seg.length == 0 ? "" : seg[seg.length - 1];
        int dot = last.lastIndexOf('.');
        return dot < 0 || !SKIP_EXT.contains(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static String host(String url) {
        return URI.create(url).getHost();
    }

    /** Bỏ phần scheme để so sánh http/https. */
    public static String schemeless(String url) {
        int i = url.indexOf("://");
        return i < 0 ? url : url.substring(i + 3);
    }
}
