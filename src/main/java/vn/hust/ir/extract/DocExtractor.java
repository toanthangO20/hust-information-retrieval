package vn.hust.ir.extract;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/** Bóc văn bản + metadata (tiêu đề, tác giả, ngày tạo, số trang) của tệp tài liệu bằng Tika. */
public final class DocExtractor {
    private DocExtractor() {}

    public static final class Result {
        public String text = "";
        public String title = "";
        public String author = "";
        public String created = "";     // yyyy-MM-dd hoặc rỗng
        public int pages;
        public String error;
    }

    private static final AutoDetectParser PARSER = new AutoDetectParser();

    public static Result extract(byte[] body, String contentType, String url) {
        Result r = new Result();
        Metadata md = new Metadata();
        BodyContentHandler h = new BodyContentHandler(2_000_000);
        if (contentType != null && !contentType.isEmpty()) md.set(Metadata.CONTENT_TYPE, contentType);
        if (url != null) md.set(TikaCoreProperties.RESOURCE_NAME_KEY, url.substring(url.lastIndexOf('/') + 1));
        try {
            try {
                PARSER.parse(new ByteArrayInputStream(body), h, md, new ParseContext());
            } catch (org.xml.sax.SAXException e) {
                // chạm giới hạn ghi: dùng phần đã trích được
            }
            r.text = clean(h.toString());
        } catch (Throwable e) {
            r.error = e.getClass().getSimpleName();
        }
        r.title = nz(md.get(TikaCoreProperties.TITLE));
        r.author = nz(first(md, "dc:creator", "meta:author", "Author", "creator"));
        String c = first(md, "dcterms:created", "meta:creation-date", "Creation-Date", "created");
        if (c != null && c.length() >= 10) {
            try {
                LocalDate d = LocalDate.parse(c.substring(0, 10));
                if (d.getYear() >= 1990 && d.isBefore(LocalDate.now().plusDays(2))) r.created = d.toString();
            } catch (Exception ignored) { }
        }
        String np = first(md, "xmpTPg:NPages", "meta:page-count", "Page-Count", "nbPage");
        try {
            if (np != null) r.pages = Integer.parseInt(np.trim());
        } catch (NumberFormatException ignored) { }
        return r;
    }

    public static String docType(String contentType, String url) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String p = url.toLowerCase(Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (ct.contains("html")) return "html";
        if (ct.contains("pdf") || p.endsWith(".pdf")) return "pdf";
        if (ct.contains("wordprocessing") || ct.contains("msword") || p.matches(".*\\.docx?$")) return "word";
        if (ct.contains("spreadsheet") || ct.contains("ms-excel") || p.matches(".*\\.xlsx?$")) return "excel";
        if (ct.contains("presentation") || ct.contains("ms-powerpoint") || p.matches(".*\\.pptx?$")) return "powerpoint";
        if (ct.startsWith("text/plain")) return "text";
        return "other";
    }

    private static String first(Metadata md, String... keys) {
        for (String k : keys) {
            String v = md.get(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String clean(String s) {
        return s.replace(' ', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    @SuppressWarnings("unused")
    private static LocalDate fromEpoch(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
