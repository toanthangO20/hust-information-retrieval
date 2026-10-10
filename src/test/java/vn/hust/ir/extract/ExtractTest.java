package vn.hust.ir.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

public class ExtractTest {
    private static final String NAV = "<nav><ul><li><a href='/'>Trang chủ</a></li><li><a href='/gioi-thieu'>Giới thiệu</a></li>"
            + "<li><a href='/tin-tuc'>Tin tức</a></li><li><a href='/lien-he'>Liên hệ</a></li></ul></nav>";
    private static final String ARTICLE = "<div id='main'><h1>Thông báo nghỉ lễ Quốc khánh</h1><p>Đăng ngày 25/08/2023 bởi Nguyễn Văn An</p>"
            + "<p>Nhà trường thông báo lịch nghỉ lễ Quốc khánh năm 2023 cho toàn thể cán bộ viên chức và sinh viên như sau, "
            + "sinh viên cần theo dõi kế hoạch học tập để bù các buổi học còn thiếu trong học kỳ này.</p>"
            + "<p>Lịch nghỉ bắt đầu từ ngày 01 đến hết ngày 04 tháng 9 năm 2023, các đơn vị bố trí lực lượng trực theo quy định hiện hành "
            + "và báo cáo kết quả về Ban Giám hiệu sau kỳ nghỉ. Xem <a href='/quy-che.pdf'>quy chế</a> để biết thêm chi tiết.</p>"
            + "<p><a href='/anh/lich.jpg'><img src='/anh/lich.jpg' alt='Lịch nghỉ lễ'></a></p></div>";
    private static final String FOOTER = "<footer><p><a href='/a'>Liên kết</a> <a href='/b'>Đối tác</a> <a href='/c'>Bản đồ</a></p>"
            + "<p>Copyright 2023</p></footer>";

    private static String page() {
        return "<html><head><title>Thông báo nghỉ lễ - HUST</title>"
                + "<meta property='article:published_time' content='2023-08-25T08:00:00+07:00'>"
                + "<meta name='author' content='Nguyễn Văn An'></head><body>" + NAV + ARTICLE + FOOTER + "</body></html>";
    }

    private static PageModel model(String html) {
        Document d = Jsoup.parse(html, "http://hust.edu.vn/tin-tuc/nghi-le");
        return new PageModel(d);
    }

    @Test
    public void bteSelectsArticleAndDropsBoilerplate() {
        PageModel m = model(page());
        new BlockFinder.Bte(false).apply(m);
        String t = m.contentText();
        assertTrue(t, t.contains("lịch nghỉ lễ Quốc khánh"));
        assertFalse(t, t.contains("Giới thiệu"));
        assertFalse(t, t.contains("Copyright"));
    }

    @Test
    public void rulesSelectsArticle() {
        PageModel m = model(page());
        new BlockFinder.Rules(true, 2, 0.5).apply(m);
        String t = m.contentText();
        assertTrue(t, t.contains("Thông báo nghỉ lễ Quốc khánh"));
        assertTrue(t, t.contains("Lịch nghỉ bắt đầu"));
        assertFalse(t, t.contains("Tin tức"));
    }

    @Test
    public void cssRuleSelectsRoot() {
        PageModel m = model(page());
        new BlockFinder.Css(List.of("#main"), List.of(), 10).apply(m);
        assertTrue(m.contentText().startsWith("Thông báo nghỉ lễ"));
        assertFalse(m.contentText().contains("Trang chủ"));
    }

    @Test
    public void templateFilterDropsRepeatedBlocks() {
        PageModel m = model(page());
        new BlockFinder.Bte(false).apply(m);
        java.util.Map<Long, Integer> df = new java.util.HashMap<>();
        for (PageModel.Block b : m.blocks)
            if (b.text.startsWith("Đăng ngày"))
                df.put(PageModel.hash64(PageModel.normalizeForHash(b.text)), 50);
        new BlockFinder.Template(df, 100, 0.2, 5).apply(m);
        assertFalse(m.contentText().contains("Đăng ngày"));
        assertTrue(m.contentText().contains("Nhà trường thông báo"));
    }

    @Test
    public void metadataFromStructuredData() {
        Document d = Jsoup.parse(page(), "http://hust.edu.vn/x");
        MetadataExtractor.Result r = MetadataExtractor.structured(d);
        PageModel m = new PageModel(d);
        new BlockFinder.Rules(true, 2, 0.5).apply(m);
        MetadataExtractor.fromContent(r, m, null, 0, null);
        assertEquals("2023-08-25", r.date.value);
        assertEquals("Nguyễn Văn An", r.author.value);
        assertEquals("Thông báo nghỉ lễ Quốc khánh", r.title.value);
    }

    @Test
    public void dateParsingVietnameseFormats() {
        assertEquals("2023-09-02", MetadataExtractor.parseText("Đăng ngày 02/09/2023", "t", 1).value);
        assertEquals("2023-09-02", MetadataExtractor.parseText("ngày 2 tháng 9 năm 2023", "t", 1).value);
        assertEquals("2022-05-19", MetadataExtractor.parseText("May 19, 2022", "t", 1).value);
        assertEquals("2021-12-31", MetadataExtractor.parseText("12/31/2021", "t", 1).value);   // mm/dd
        assertNull(MetadataExtractor.parseText("khóa 45 học kỳ 20231", "t", 1));
    }

    @Test
    public void dateFallbackFromTextWhenNoMeta() {
        String html = "<html><head><title>x</title></head><body>" + NAV + ARTICLE + FOOTER + "</body></html>";
        Document d = Jsoup.parse(html, "http://hust.edu.vn/x");
        MetadataExtractor.Result r = MetadataExtractor.structured(d);
        PageModel m = new PageModel(d);
        new BlockFinder.Rules(true, 2, 0.5).apply(m);
        MetadataExtractor.fromContent(r, m, null, 0, null);
        assertNotNull(r.date);
        assertEquals("2023-08-25", r.date.value);
        assertEquals("Nguyễn Văn An", r.author.value);
    }

    @Test
    public void linksCarryAnchorAndContentFlag() {
        PageModel m = model(page());
        new BlockFinder.Rules(true, 2, 0.5).apply(m);
        List<LinkExtractor.Edge> es = LinkExtractor.extract(m, "http://hust.edu.vn/tin-tuc/nghi-le", "http://hust.edu.vn/tin-tuc/nghi-le");
        LinkExtractor.Edge pdf = es.stream().filter(e -> e.dst.endsWith("/quy-che.pdf")).findFirst().orElse(null);
        assertNotNull(pdf);
        assertEquals("quy chế", pdf.anchor);
        assertTrue(pdf.inContent);
        LinkExtractor.Edge nav = es.stream().filter(e -> e.dst.endsWith("/gioi-thieu")).findFirst().orElse(null);
        assertNotNull(nav);
        assertFalse(nav.inContent);
        LinkExtractor.Edge img = es.stream().filter(e -> e.kind.equals("img")).findFirst().orElse(null);
        assertNotNull(img);
        assertEquals("Lịch nghỉ lễ", img.alt);
    }

    @Test
    public void pageRankSumsToOneAndRanksInlinkedNodeHigher() {
        List<int[]> edges = List.of(new int[]{0, 2}, new int[]{1, 2}, new int[]{2, 0});
        double[] pr = GraphBuilder.pageRank(3, edges);
        assertEquals(1.0, pr[0] + pr[1] + pr[2], 1e-9);
        assertTrue(pr[2] > pr[1]);
    }

    @Test
    public void junkAnchorsAreRecognized() {
        assertTrue(GraphBuilder.isJunk("Xem thêm"));
        assertTrue(GraphBuilder.isJunk("12"));
        assertTrue(GraphBuilder.isJunk("https://hust.edu.vn/x"));
        assertFalse(GraphBuilder.isJunk("Học bổng Honda 2023"));
    }
}
