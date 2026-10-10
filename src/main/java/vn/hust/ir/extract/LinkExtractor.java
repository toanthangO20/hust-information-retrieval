package vn.hust.ir.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.nodes.Element;

import vn.hust.ir.crawler.UrlNormalizer;

/**
 * P5 — Trích các cạnh đồ thị liên kết (nguồn → đích : văn bản mô tả) từ một trang đã có kết quả tách khối nội dung.
 * Mỗi cạnh cho biết liên kết nằm trong khối nội dung chính hay trong khuôn mẫu (menu, chân trang...).
 */
public final class LinkExtractor {
    private LinkExtractor() {}

    public static final class Edge {
        public String dst;
        public String anchor = "";
        public String kind;          // a | img | iframe | embed
        public String alt = "";      // chỉ với ảnh
        public String title = "";    // thuộc tính title của thẻ
        public String caption = "";  // figcaption của ảnh
        public boolean inContent;
        public boolean nofollow;
        public int count = 1;
    }

    private static final int MAX_ANCHOR = 200;

    public static List<Edge> extract(PageModel m, String baseUrl, String selfUrl) {
        Map<String, Edge> out = new LinkedHashMap<>();
        for (Element e : m.doc.select("a[href], area[href], img, iframe[src], embed[src]")) {
            String tag = e.normalName();
            String raw;
            String kind;
            switch (tag) {
                case "a": case "area": raw = e.attr("href"); kind = "a"; break;
                case "img": raw = imgSrc(e); kind = "img"; break;
                case "iframe": raw = e.attr("src"); kind = "iframe"; break;
                default: raw = e.attr("src"); kind = "embed";
            }
            if (raw == null || raw.isBlank() || raw.startsWith("data:") || raw.startsWith("javascript:")) continue;
            String dst = UrlNormalizer.normalize(baseUrl, raw);
            if (dst == null || dst.equals(selfUrl)) continue;

            Edge ed = new Edge();
            ed.dst = dst;
            ed.kind = kind;
            ed.nofollow = e.attr("rel").toLowerCase(Locale.ROOT).contains("nofollow");
            ed.inContent = m.inContent(e);
            if (kind.equals("a")) {
                String t = e.text().replaceAll("\\s+", " ").trim();
                Element img = e.selectFirst("img");
                ed.title = e.attr("title").trim();
                if (t.isEmpty() && img != null) t = img.attr("alt").trim();
                if (t.isEmpty()) t = ed.title;
                if (t.isEmpty()) t = e.attr("aria-label").trim();
                ed.anchor = trunc(t);
                if (img != null && imgSrc(img) != null) {       // ảnh bọc trong liên kết: cũng ghi nhận cạnh ảnh
                    ed.alt = img.attr("alt").trim();
                }
            } else if (kind.equals("img")) {
                ed.alt = e.attr("alt").trim();
                ed.title = e.attr("title").trim();
                Element fig = e.closest("figure");
                if (fig != null) {
                    Element fc = fig.selectFirst("figcaption");
                    if (fc != null) ed.caption = trunc(fc.text().trim());
                }
                if (ed.caption.isEmpty()) {
                    Element p = e.parent();
                    if (p != null && p.normalName().equals("a")) p = p.parent();
                    // chú thích kiểu WordPress/NukeViet: [class*=caption] gần ảnh
                    if (p != null) {
                        Element cap = p.selectFirst("[class*=caption], figcaption");
                        if (cap != null && cap.text().length() < 300) ed.caption = trunc(cap.text().trim());
                    }
                }
                ed.anchor = trunc(!ed.alt.isEmpty() ? ed.alt : ed.title);
                // ảnh nằm trong <a> trỏ tới chính nó: ghi thêm văn bản neo của liên kết
                Element a = e.closest("a[href]");
                if (a != null && ed.anchor.isEmpty()) ed.anchor = trunc(a.text().trim());
            }
            String key = dst + "\u0001" + kind + "\u0001" + ed.anchor;
            Edge prev = out.get(key);
            if (prev != null) {
                prev.count++;
                prev.inContent |= ed.inContent;
            } else {
                out.put(key, ed);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static String imgSrc(Element img) {
        for (String k : new String[]{"src", "data-src", "data-original", "data-lazy-src", "data-lazy"}) {
            String v = img.attr(k);
            if (!v.isBlank() && !v.startsWith("data:")) return v;
        }
        String ss = img.attr("srcset");
        if (!ss.isBlank()) return ss.trim().split("[\\s,]+")[0];
        return null;
    }

    private static String trunc(String s) {
        return s.length() > MAX_ANCHOR ? s.substring(0, MAX_ANCHOR) : s;
    }
}
