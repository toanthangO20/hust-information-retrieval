package vn.hust.ir.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.junit.Test;

public class ExtractMoreTest {
    @Test
    public void rawStoreRoundTripAndResume() throws Exception {
        Path dir = Files.createTempDirectory("rawstore");
        byte[] body = "<html><body>Xin chào</body></html>".getBytes(StandardCharsets.UTF_8);
        try (RawStore s = new RawStore(dir)) {
            RawStore.Entry e = new RawStore.Entry();
            e.url = "https://hust.edu.vn/a";
            e.host = "hust.edu.vn";
            e.status = 200;
            e.kind = "HTML";
            s.put(e, body);
        }
        try (RawStore s = new RawStore(dir)) {         // mở lại: bản ghi và nội dung còn nguyên
            RawStore.Entry e = s.get("https://hust.edu.vn/a");
            assertNotNull(e);
            assertEquals(200, e.status);
            assertEquals(new String(body, StandardCharsets.UTF_8), new String(s.read(e), StandardCharsets.UTF_8));
            assertEquals(RawStore.sha1(body), e.bodySha1);
        }
    }

    @Test
    public void binaryDocumentsBehindHtmlUrlsAreDetected() {
        assertTrue(Extractor.looksBinaryDoc("%PDF-1.7 ....".getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(Extractor.looksBinaryDoc("<!doctype html><html>".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void logisticRegressionLearnsSeparableData() {
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < 400; i++) {
            double v = r.nextGaussian();
            int y = v > 0 ? 1 : 0;
            xs.add(new double[]{1, v + 0.1 * r.nextGaussian()});
            ys.add(y);
            ws.add(1.0);
        }
        LogReg m = LogReg.train(new String[]{"bias", "x"}, xs, ys, ws, 1e-4, 800, 0.5);
        assertTrue(m.prob(new double[]{1, 2.0}) > 0.9);
        assertTrue(m.prob(new double[]{1, -2.0}) < 0.1);
    }

    @Test
    public void learnedFinderUsesFeaturesAndKeepsArticle() {
        String nav = "<nav><ul><li><a href='/a'>Trang chủ</a></li><li><a href='/b'>Giới thiệu</a></li><li><a href='/c'>Liên hệ</a></li></ul></nav>";
        String art = "<div class='entry-content'><h1>Thông báo tuyển sinh</h1><p>Trường tổ chức tuyển sinh đại học chính quy năm 2026 với nhiều ngành "
                + "đào tạo mới, thí sinh đăng ký trực tuyến theo hướng dẫn chi tiết đăng trên cổng thông tin tuyển sinh của Trường.</p>"
                + "<p>Thời gian nhận hồ sơ từ ngày 01/06 đến hết ngày 30/06 theo kế hoạch chung của Đại học, thí sinh cần chuẩn bị đầy đủ giấy tờ.</p></div>";
        String foot = "<footer><p>Bản quyền thuộc về Trường. <a href='/x'>Sơ đồ</a> <a href='/y'>Liên hệ</a></p></footer>";
        PageModel m = new PageModel(Jsoup.parse("<html><body>" + nav + art + foot + "</body></html>", "http://hust.edu.vn/"));
        // mô hình tay: ưu tiên nhiều từ, ít liên kết, không nằm trong nav/footer
        LogReg lr = new LogReg();
        lr.names = BlockFeatures.NAMES;
        int d = lr.names.length;
        lr.mean = new double[d];
        lr.std = new double[d];
        java.util.Arrays.fill(lr.std, 1.0);
        lr.w = new double[d];
        lr.w[0] = -1.0;
        lr.w[java.util.Arrays.asList(lr.names).indexOf("logWords")] = 1.0;
        lr.w[java.util.Arrays.asList(lr.names).indexOf("linkDensity")] = -6.0;
        lr.w[java.util.Arrays.asList(lr.names).indexOf("inNav")] = -3.0;
        lr.w[java.util.Arrays.asList(lr.names).indexOf("inFooter")] = -3.0;
        new BlockFinder.Learned(lr, null).apply(m);
        String t = m.contentText();
        assertTrue(t, t.contains("Thời gian nhận hồ sơ"));
        assertFalse(t, t.contains("Giới thiệu"));
        assertFalse(t, t.contains("Bản quyền"));
    }

    @Test
    public void headingFallbackIgnoresSiteConstantBanner() {
        String html = "<html><head><title>Khoa Ngoại ngữ</title></head><body><div><h1>Trang chủ</h1></div>"
                + "<div class='c'><h3>ThS. Lê Thanh Hương</h3><p>Giảng viên chính, bộ môn Tiếng Anh chuyên nghiệp, "
                + "nghiên cứu phương pháp giảng dạy tiếng Anh và biên phiên dịch khoa học kỹ thuật cho sinh viên.</p></div></body></html>";
        org.jsoup.nodes.Document d = Jsoup.parse(html, "http://sofl.hust.edu.vn/x");
        MetadataExtractor.Result r = MetadataExtractor.structured(d);
        PageModel m = new PageModel(d);
        for (PageModel.Block b : m.blocks) b.content = true;
        MetadataExtractor.Constants k = new MetadataExtractor.Constants() {
            public boolean title(String v) { return "Khoa Ngoại ngữ".equals(v); }
            public boolean author(String v) { return false; }
            public boolean h1(String v) { return "Trang chủ".equals(v); }
        };
        MetadataExtractor.fromContent(r, m, null, 0, k);
        assertNotNull(r.title);
        assertEquals("ThS. Lê Thanh Hương", r.title.value);
    }

    @Test
    public void archiveUrlsAndDateLines() {
        assertTrue(MetadataExtractor.archiveUrl("http://ds.soict.hust.edu.vn/?m=202508"));
        assertTrue(MetadataExtractor.archiveUrl("https://icamt2024.smse.hust.edu.vn/author/admin"));
        assertFalse(MetadataExtractor.archiveUrl("https://fed.hust.edu.vn/vi/news/tin-tuc/abc-296277.html"));
        assertTrue(MetadataExtractor.pureDateLine("Thứ năm - 24/07/2025 15:32"));
        assertTrue(MetadataExtractor.pureDateLine("10 February, 2026"));
        assertFalse(MetadataExtractor.pureDateLine("Hội nghị diễn ra ngày 22/05/2017 tại phòng 902"));
        assertEquals("2026-09-20", MetadataExtractor.parseText("Scholarships 20 Sept 2026", "t", 1).value);
    }

    @Test
    public void bteMatchesBruteForceOnRandomStreams() {
        // O(n) của BTE phải trùng với cực đại hóa O(n^2) theo định nghĩa trong slide Ch10
        java.util.Random r = new java.util.Random(7);
        for (int trial = 0; trial < 50; trial++) {
            int n = 5 + r.nextInt(40);
            boolean[] tag = new boolean[n];
            for (int i = 0; i < n; i++) tag[i] = r.nextDouble() < 0.5;
            int best = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++)
                for (int j = i; j < n; j++) {
                    int f = 0;
                    for (int k = 0; k < i; k++) if (tag[k]) f++;
                    for (int k = i; k <= j; k++) if (!tag[k]) f++;
                    for (int k = j + 1; k < n; k++) if (tag[k]) f++;
                    best = Math.max(best, f);
                }
            int tc = 0, wc = 0, bestG = Integer.MIN_VALUE, fast = Integer.MIN_VALUE, totalTags = 0;
            for (boolean t : tag) if (t) totalTags++;
            for (int j = 0; j < n; j++) {
                bestG = Math.max(bestG, tc - wc);
                if (tag[j]) tc++; else wc++;
                fast = Math.max(fast, bestG + (wc - tc));
            }
            assertEquals(best, fast + totalTags);
        }
    }
}
