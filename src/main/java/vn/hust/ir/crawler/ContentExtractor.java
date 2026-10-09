package vn.hust.ir.crawler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Tách nội dung chính và liên kết từ trang (Chương 10: "Tách nội dung", "Tìm khối nội dung").
 * - HTML: jsoup loại bỏ phần dẫn hướng/footer/quảng cáo và các khối có mật độ liên kết cao
 *   (heuristic nội dung vs. nhiễu), sau đó Tika trích văn bản từ phần còn lại.
 * - PDF/Office/văn bản thuần: Tika AutoDetectParser.
 */
public final class ContentExtractor {
    public static final class Result {
        public String title = "";
        public String text = "";
        public List<String> links = new ArrayList<>();
        public String canonical;
        public boolean noindex;
        public boolean nofollow;
    }

    private static final AutoDetectParser PARSER = new AutoDetectParser();
    private static final String BOILERPLATE =
            "script,style,noscript,template,iframe,svg,canvas,nav,aside,footer,button,select,option,input,textarea,"
                    + "[role=navigation],[role=banner],[role=contentinfo],[aria-hidden=true],"
                    + ".breadcrumb,.navbar,.pagination,.sidebar";

    public Result extractHtml(byte[] body, String charset, String url) throws Exception {
        Document doc = Jsoup.parse(new ByteArrayInputStream(body), charset, url);
        Result r = new Result();
        r.title = doc.title().trim();

        String robots = doc.select("meta[name=robots]").attr("content").toLowerCase(Locale.ROOT);
        r.noindex = robots.contains("noindex");
        r.nofollow = robots.contains("nofollow");

        String canon = doc.select("link[rel=canonical]").attr("href");
        if (!canon.isEmpty()) r.canonical = UrlNormalizer.normalize(doc.baseUri(), canon);

        Set<String> links = new LinkedHashSet<>();
        for (Element a : doc.select("a[href]")) {
            String rel = a.attr("rel").toLowerCase(Locale.ROOT);
            if (rel.contains("nofollow")) continue;
            String n = UrlNormalizer.normalize(doc.baseUri(), a.attr("href"));
            if (n != null) links.add(n);
        }
        r.links.addAll(links);

        // 1) loại phần tử nhiễu; <form> chỉ "mở bọc" vì trang ASP.NET đặt toàn bộ nội dung trong một form;
        //    <header> chỉ loại khi không nằm trong bài viết
        for (Element f : doc.select("form")) f.unwrap();
        doc.select(BOILERPLATE).remove();
        for (Element h : doc.select("header"))
            if (h.parents().select("article,main").isEmpty()) h.remove();

        // 2) chọn vùng nội dung chính nếu có
        Element root = doc.body();
        if (root == null) return r;
        Element best = null;
        for (Element e : root.select("article,main,[role=main]"))
            if (best == null || e.text().length() > best.text().length()) best = e;
        if (best != null && best.text().length() > 100) root = best;

        // 3) loại các khối có mật độ liên kết cao (menu, danh sách liên kết, "xem thêm"...)
        Elements blocks = root.select("div,ul,ol,table,p,li,section");
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Element e = blocks.get(i);
            int t = e.text().length();
            if (t == 0) continue;
            int l = 0;
            for (Element a : e.select("a")) l += a.text().length();
            if (l > 0 && (double) l / t > 0.8 && t < 3000) e.remove();
        }

        r.text = tikaText(root.outerHtml().getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8", null, new Metadata());
        return r;
    }

    public Result extractDocument(byte[] body, String contentType, String url) {
        Result r = new Result();
        Metadata md = new Metadata();
        try {
            r.text = tikaText(body, contentType, url, md);
        } catch (Exception e) {
            r.text = "";
        }
        String t = md.get(TikaCoreProperties.TITLE);
        r.title = t == null ? "" : t.trim();
        return r;
    }

    private static String tikaText(byte[] bytes, String contentType, String url, Metadata md) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(2_000_000);
        if (contentType != null && !contentType.isEmpty()) md.set(Metadata.CONTENT_TYPE, contentType);
        if (url != null) md.set(TikaCoreProperties.RESOURCE_NAME_KEY, url.substring(url.lastIndexOf('/') + 1));
        try {
            PARSER.parse(new ByteArrayInputStream(bytes), handler, md, new ParseContext());
        } catch (org.xml.sax.SAXException e) {
            // chạm giới hạn ghi: dùng phần văn bản đã trích được
        }
        return clean(handler.toString());
    }

    static String clean(String s) {
        return s.replace(' ', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
