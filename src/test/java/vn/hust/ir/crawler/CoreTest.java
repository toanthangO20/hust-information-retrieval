package vn.hust.ir.crawler;

import static org.junit.Assert.*;

import org.junit.Test;

public class CoreTest {
    @Test
    public void normalizeRelativeAndNoise() {
        assertEquals("https://hust.edu.vn/vi/about/tong-quan.html",
                UrlNormalizer.normalize("https://hust.edu.vn/vi/news/a.html", "../about/tong-quan.html#top"));
        assertEquals("https://hust.edu.vn/a?x=1&y=2",
                UrlNormalizer.normalize(null, "HTTPS://HUST.edu.vn:443/a?y=2&utm_source=fb&x=1"));
        assertEquals("https://hust.edu.vn/", UrlNormalizer.normalize(null, "https://hust.edu.vn"));
        assertNull(UrlNormalizer.normalize(null, "mailto:a@hust.edu.vn"));
        assertNull(UrlNormalizer.normalize(null, "javascript:void(0)"));
        assertEquals("https://hust.edu.vn/vi/tin-t%E1%BB%A9c", UrlNormalizer.normalize(null, "https://hust.edu.vn/vi/tin-tức"));
    }

    @Test
    public void crawlableFilters() {
        assertFalse(UrlNormalizer.crawlable("https://hust.edu.vn/uploads/a.jpg"));
        assertFalse(UrlNormalizer.crawlable("https://hust.edu.vn/a/b/a/c/a/d"));
        assertTrue(UrlNormalizer.crawlable("https://hust.edu.vn/files/de-an.pdf"));
    }

    @Test
    public void robotsRules() {
        RobotsCache.Rules r = RobotsCache.parse(
                "User-agent: *\nDisallow: /admin/\nDisallow: /private\nAllow: /private/public\nCrawl-delay: 2\n"
                        + "Sitemap: https://x/sitemap.xml\n");
        assertFalse(r.allowed("/admin/index.php"));
        assertFalse(r.allowed("/private/x"));
        assertTrue(r.allowed("/private/public/x"));
        assertTrue(r.allowed("/vi/news"));
        assertEquals(2000, r.crawlDelayMs);
        assertEquals(1, r.sitemaps.size());
    }

    @Test
    public void robotsSpecificAgentWins() {
        RobotsCache.Rules r = RobotsCache.parse("User-agent: *\nDisallow: /\n\nUser-agent: HUSTIRBot\nDisallow: /x/\n");
        assertTrue(r.allowed("/a"));
        assertFalse(r.allowed("/x/b"));
    }

    @Test
    public void simhashNearDuplicate() {
        String a = "Đại học Bách khoa Hà Nội thông báo tuyển sinh đại học chính quy năm 2026 với nhiều ngành đào tạo "
                + "mới, chương trình tiên tiến và chương trình đào tạo quốc tế cho thí sinh trên cả nước.";
        String b = a + " Xem thêm";
        String c = "Thư viện Tạ Quang Bửu mở cửa phục vụ bạn đọc từ thứ hai đến thứ sáu hằng tuần, "
                + "sinh viên cần mang theo thẻ để mượn sách và truy cập cơ sở dữ liệu trực tuyến.";
        long ha = SimHash.compute(SimHash.tokens(a)), hb = SimHash.compute(SimHash.tokens(b)), hc = SimHash.compute(SimHash.tokens(c));
        assertTrue(SimHash.hamming(ha, hb) < SimHash.hamming(ha, hc));
        assertTrue(SimHash.hamming(ha, hc) > 10);
    }

    @Test
    public void frontierPolitenessAndPriority() throws Exception {
        Frontier f = new Frontier();
        f.add("https://a.hust.edu.vn/low", 2);
        f.add("https://a.hust.edu.vn/high", 0);
        f.add("https://b.hust.edu.vn/x", 1);
        Frontier.Task t1 = f.take();
        long t0 = System.currentTimeMillis();
        f.release(t1, 300);
        Frontier.Task t2 = f.take();               // host khác, không phải chờ
        f.release(t2, 300);
        Frontier.Task t3 = f.take();
        long waited = System.currentTimeMillis() - t0;
        assertNotEquals(t1.host, t2.host);
        assertEquals(t1.host, t3.host);
        assertTrue("phải chờ ~300ms cho cùng host: " + waited, waited >= 250);
        if (t1.host.equals("a.hust.edu.vn")) assertEquals("https://a.hust.edu.vn/high", t1.url);
        f.release(t3, 0);
        assertNull(f.take());
    }
}
