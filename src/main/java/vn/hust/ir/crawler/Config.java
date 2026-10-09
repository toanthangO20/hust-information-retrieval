package vn.hust.ir.crawler;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Cấu hình crawler; đọc từ tham số dòng lệnh dạng --key value. */
public final class Config {
    public static final List<String> DEFAULT_SEEDS = Arrays.asList(
            "https://hust.edu.vn/", "https://soict.hust.edu.vn/", "https://sme.hust.edu.vn/",
            "https://ctt.hust.edu.vn/", "https://ctt-sis.hust.edu.vn/", "https://library.hust.edu.vn/",
            "https://svbk.hust.edu.vn/", "https://tuyendung.hust.edu.vn/", "https://work.hust.edu.vn/",
            "https://e.hust.edu.vn/", "https://csv.hust.edu.vn/", "https://bulletin.hust.edu.vn/");

    public List<String> seeds = new ArrayList<>(DEFAULT_SEEDS);
    public int threads = 8;
    public long delayMs = 500;          // khoảng chờ tối thiểu giữa 2 request tới cùng một host (tính lịch thiệp)
    public long globalDelayMs = 100;    // khoảng cách tối thiểu giữa 2 request bất kỳ (giới hạn tốc độ toàn cục)
    public int maxPages = 5000;         // số trang tối đa được tải trong một lần chạy
    public int maxPerHost = 3000;       // số trang tối đa / host / lần chạy (chống bẫy crawler)
    public int minChars = 200;          // ngưỡng nội dung tối thiểu
    public int minWords = 30;
    public int revisitDays = 7;         // chu kỳ tải lại URL đã biết
    public int hubRevisitHours = 24;    // chu kỳ tải lại trang "hub" (trang nhiều liên kết ra)
    public int hubMinLinks = 30;
    public int hubLimit = 300;
    public Set<String> onlyHosts = new java.util.HashSet<>();           // nếu không rỗng: chỉ tải các host này
    public boolean dropNearDup = false;
    public Set<String> recheckReasons = new java.util.HashSet<>();   // tải lại các URL từng bị loại với lý do này
    public boolean useSitemaps = true;
    public Path outDir = Paths.get("output");
    public Path dataDir = Paths.get("data");
    public String userAgent = "Mozilla/5.0 (compatible; HUSTIRBot/1.0; educational crawler for IR course)";
    public Set<String> blockedHosts = Set.of("mail.hust.edu.vn");
    public java.util.Map<String, Long> hostDelayMs = java.util.Map.of("hust.edu.vn", 700L);   // host chính chịu tải ít hơn, bị giới hạn tốc độ chặt hơn

    public static final String ROOT_DOMAIN = "hust.edu.vn";
    public String rootDomain = ROOT_DOMAIN;     // chỉ đổi trong kiểm thử tích hợp

    public boolean inScope(String host) {
        return host != null && (host.equals(rootDomain) || host.endsWith("." + rootDomain));
    }

    public boolean allowedHost(String host) {
        return inScope(host) && !blockedHosts.contains(host);
    }

    public static Config parse(String[] a, int from) {
        Config c = new Config();
        for (int i = from; i < a.length; i++) {
            String k = a[i];
            switch (k) {
                case "--threads": c.threads = Integer.parseInt(a[++i]); break;
                case "--delay-ms": c.delayMs = Long.parseLong(a[++i]); break;
                case "--global-delay-ms": c.globalDelayMs = Long.parseLong(a[++i]); break;
                case "--max-pages": c.maxPages = Integer.parseInt(a[++i]); break;
                case "--max-per-host": c.maxPerHost = Integer.parseInt(a[++i]); break;
                case "--min-chars": c.minChars = Integer.parseInt(a[++i]); break;
                case "--min-words": c.minWords = Integer.parseInt(a[++i]); break;
                case "--revisit-days": c.revisitDays = Integer.parseInt(a[++i]); break;
                case "--hub-revisit-hours": c.hubRevisitHours = Integer.parseInt(a[++i]); break;
                case "--out": c.outDir = Paths.get(a[++i]); break;
                case "--data": c.dataDir = Paths.get(a[++i]); break;
                case "--seeds": c.seeds = new ArrayList<>(Arrays.asList(a[++i].split(","))); break;
                case "--no-sitemap": c.useSitemaps = false; break;
                case "--only-hosts": c.onlyHosts.addAll(Arrays.asList(a[++i].split(","))); break;
                case "--recheck": c.recheckReasons.addAll(Arrays.asList(a[++i].split(","))); break;
                case "--drop-near-dup": c.dropNearDup = true; break;
                default: throw new IllegalArgumentException("Tham số không hợp lệ: " + k);
            }
        }
        return c;
    }
}
