package vn.hust.ir.extract;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.hust.ir.extract.PageModel.Block;

/**
 * P4 — Bóc metadata trang HTML theo chuỗi ưu tiên; mỗi trường kèm nguồn ({@code source}) và độ tin cậy ({@code conf}).
 * Gọi {@link #structured(Document)} TRƯỚC khi tạo {@link PageModel} (JSON-LD nằm trong thẻ script), sau đó
 * {@link #fromContent(Result, PageModel, String, long)} để bổ sung bằng nội dung đã tách.
 */
public final class MetadataExtractor {
    private MetadataExtractor() {}

    /** Giá trị metadata lặp lại ở nhiều trang cùng host (hằng số của site, không mô tả riêng trang). */
    public interface Constants {
        boolean title(String v);

        boolean author(String v);

        boolean h1(String v);
    }

    public static final class Field {
        public String value;
        public String source;
        public double conf;

        Field(String v, String s, double c) {
            value = v;
            source = s;
            conf = c;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("value", value);
            m.put("source", source);
            m.put("conf", conf);
            return m;
        }
    }

    public static final class Result {
        public Field title, date, author, sourceRef;
        public String headline;       // <h1> đầu tiên (để chọn tiêu đề sau khi có khối nội dung)
        public String htmlTitle, ogTitle;
        public String lang;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SITE_SUFFIX = Pattern.compile("\\s+[-–—|»]\\s+[^-–—|»]{2,60}$");
    private static final Pattern DMY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[/.\\-]\\s*(\\d{1,2})\\s*[/.\\-]\\s*(\\d{4})(?!\\d)");
    private static final Pattern VI_LONG = Pattern.compile("ngày\\s+(\\d{1,2})\\s+tháng\\s+(\\d{1,2})[,\\s]+năm\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)");
    private static final String MON = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\.?";
    private static final Pattern EN_LONG = Pattern.compile("(?i)\\b(" + MON + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})");
    private static final Pattern DAY_MON_YEAR = Pattern.compile("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(" + MON + "),?\\s+(\\d{4})");
    private static final Pattern DATE_CUE = Pattern.compile("(?iu)(đăng|cập nhật|published|posted|updated|công bố|ban hành|date:|ngày:)");
    private static final Pattern PREV_LABEL = Pattern.compile("(?iu)(bắt đầu|kết thúc|hạn|deadline|start|end|từ ngày|đến ngày|thời gian|diễn ra)");
    private static final Pattern WEEKDAY = Pattern.compile("(?iu)(thứ\\s*\\S+|chủ nhật|monday|tuesday|wednesday|thursday|friday|saturday|sunday)");

    /** URL của trang lưu trữ/danh sách theo tác giả, chuyên mục, thẻ, tháng, trang số: không phải một bài viết có ngày đăng riêng. */
    private static final Pattern ARCHIVE_URL = Pattern.compile("(?i)([?&](m|author|cat|tag|paged?|s)=)|/(author|category|tag|tags|archive|page)/");

    static boolean archiveUrl(String url) {
        return url != null && ARCHIVE_URL.matcher(url).find();
    }

    private static final Pattern ISO_DT =Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");
    private static final Pattern BYLINE_DATE = Pattern.compile("(?iu)\\b(by|bởi|đăng bởi)\\s+\\S+(\\s+\\S+)?\\s*[|•·\\-]\\s*\\d");

    static boolean startsWithDate(String t) {
        return t.matches("^\\s*[(\\[]?\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}.*") || t.matches("^\\s*\\d{4}-\\d{2}-\\d{2}.*");
    }

    /** Dòng ngắn có ngày kèm tối đa 2 từ chữ khác (ví dụ "Scholarships 20 Sept 2026"). */
    static boolean shortDateLine(String t) {
        String r = t.replaceAll("\\d{1,2}(?:[:h]\\d{2}(?::\\d{2})?|h)", " ");
        r = WEEKDAY.matcher(r).replaceAll(" ");
        r = r.replaceAll("(?i)" + MON, " ").replaceAll("[\\d/.,:\\-–•|·()\\[\\]]+", " ");
        String[] w = r.trim().isEmpty() ? new String[0] : r.trim().split("\\s+");
        return w.length <= 3 && parseText(t, "x", 0) != null;
    }

    /** Dòng chỉ gồm một ngày (có thể kèm thứ/giờ), ví dụ "Thứ năm - 24/07/2025 15:32" hoặc "10 February, 2026". */
    static boolean pureDateLine(String t) {
        String r = t.replaceAll("\\d{1,2}(?:[:h]\\d{2}(?::\\d{2})?|h)", " ");
        r = WEEKDAY.matcher(r).replaceAll(" ");
        r = r.replaceAll("(?iu)\\b(ngày|tháng|năm|at|lúc|on)\\b", " ");
        r = r.replaceAll("(?i)" + MON, " ").replaceAll("[\\d\\s/.,:\\-–•|·()\\[\\]]+", "");
        return r.length() <= 3;
    }
    private static final Pattern AUTHOR_CUE = Pattern.compile(
            "(?iu)(?:tác giả|người đăng|đăng bởi|biên tập|người viết|author|posted by|by)\\s*[:：]\\s*([^\\n|•·,;]{2,60})");
    private static final Pattern AUTHOR_LOOSE = Pattern.compile("(?iu)(?:^|[|•·]\\s*|\\d\\s+)(?:đăng bởi|bởi|by)\\s+([^\\n|•·,;:()]{2,60})");
    private static final Pattern SOURCE_CUE = Pattern.compile("(?iu)(?:nguồn|source)\\s*[:：]\\s*([^\\n]{2,120})");
    private static final String[] MONTHS = {"january", "february", "march", "april", "may", "june", "july", "august",
            "september", "october", "november", "december"};

    /** Metadata có cấu trúc (JSON-LD, meta, microdata, thẻ time). Không sửa DOM. */
    public static Result structured(Document d) {
        Result r = new Result();
        r.htmlTitle = d.title().trim();
        r.ogTitle = meta(d, "property", "og:title");
        if (r.ogTitle.isEmpty()) r.ogTitle = meta(d, "name", "twitter:title");
        Element h1 = d.selectFirst("h1");
        if (h1 != null) r.headline = h1.text().trim();
        r.lang = d.select("html").attr("lang");

        JsonNode article = null;
        for (Element s : d.select("script[type=application/ld+json]")) {
            try {
                article = findArticle(JSON.readTree(s.data()));
            } catch (Exception ignored) { }
            if (article != null) break;
        }
        // --- ngày đăng (dữ liệu có cấu trúc; các nguồn DOM/văn bản được xét sau khi đã tách khối nội dung)
        Field date = null;
        if (article != null && article.hasNonNull("datePublished"))
            date = dateField(article.get("datePublished").asText(), "json-ld:datePublished", 0.95);
        if (date == null)
            for (String[] k : new String[][]{{"property", "article:published_time"}, {"property", "og:article:published_time"},
                    {"itemprop", "datePublished"}, {"name", "pubdate"}, {"name", "publishdate"}, {"name", "date"},
                    {"name", "DC.date.issued"}, {"name", "dcterms.created"}}) {
                date = dateField(meta(d, k[0], k[1]), "meta:" + k[1], 0.9);
                if (date != null) break;
            }
        r.date = date;
        // --- tác giả
        Field author = null;
        if (article != null) {
            JsonNode a = article.get("author");
            if (a != null) {
                String n = a.isArray() && a.size() > 0 ? text(a.get(0)) : text(a);
                if (!n.isBlank() && !ORG.matcher(n.trim()).find()) author = new Field(clean(n), "json-ld:author", 0.9);
            }
        }
        if (author == null) {
            for (String[] k : new String[][]{{"name", "author"}, {"property", "article:author"}, {"itemprop", "author"},
                    {"name", "DC.creator"}, {"name", "dcterms.creator"}}) {
                String v = meta(d, k[0], k[1]);
                if (!v.isBlank() && !v.startsWith("http") && !ORG.matcher(v.trim()).find()) {
                    author = new Field(clean(v), "meta:" + k[1], 0.8);
                    break;
                }
            }
        }
        r.author = author;
        return r;
    }

    /** Tên tổ chức/đơn vị/tài khoản hệ thống: không phải tác giả bài viết. */
    private static final Pattern ORG = Pattern.compile("(?iu)^(trường|khoa|viện|trung tâm|phòng|ban\\b|đoàn|hội|bộ môn|đại học|"
            + "university|school|institute|centre|center|department|faculty|lab\\b|laboratory|hust|icech|icpt|icef|soict|sedic|"
            + "admin(istrator)?$|super admin|website|webmaster)|bách khoa");

    private static final String DATE_SELECTORS = "[itemprop=datePublished], time, .entry-date, .post-date, .news-date, "
            + ".date-post, .article-date, .published, [class*=ngay-dang], [class*=ngaydang]";

    private static boolean constTitle(Constants k, String raw) {
        return k != null && raw != null && !raw.isBlank() && k.title(raw);
    }

    /** Bổ sung tiêu đề và các trường còn thiếu bằng nội dung đã tách (m.content phải đã được gán). */
    public static void fromContent(Result r, PageModel m, String lastModified, long fetchedAt, Constants k) {
        // ---- tiêu đề: ứng viên không phải hằng số của site, theo thứ tự h1 > og:title > <title>
        String h1 = dedupDouble(r.headline == null ? "" : r.headline);
        int h1w = h1.isEmpty() ? 0 : h1.split("\\s+").length;
        boolean h1Ok = h1w >= 1 && h1w <= 40;
        boolean h1Const = k != null && k.h1(r.headline);
        String og = stripSuffix(r.ogTitle, h1), ht = stripSuffix(r.htmlTitle, h1);
        boolean ogConst = constTitle(k, r.ogTitle), htConst = constTitle(k, r.htmlTitle);
        if (h1Ok && !h1Const && h1w >= 2) r.title = new Field(h1, "dom:h1", 0.8);
        else if (!og.isEmpty() && !ogConst) r.title = new Field(og, "meta:og:title", 0.7);
        else if (!ht.isEmpty() && !htConst) r.title = new Field(ht, "html:title", 0.6);
        else if (h1Ok && !h1Const) r.title = new Field(h1, "dom:h1", 0.5);
        else {
            // mọi ứng viên đều lặp lại ở nhiều trang: dùng heading/khối đầu của nội dung, nếu không có thì ứng viên đầu tiên còn lại
            Field f = null;
            for (Block b : m.blocks)
                if (b.content && b.heading && !b.text.startsWith("http") && !(k != null && (k.h1(b.text) || k.title(b.text)))) {
                    f = new Field(dedupDouble(b.text), "dom:first-heading", 0.4);
                    break;
                }
            if (f == null)
                for (Block b : m.blocks)
                    if (b.content) {
                        if (b.words <= 30 && !b.text.startsWith("http") && !b.text.contains("@"))
                            f = new Field(dedupDouble(b.text), "dom:first-block", 0.3);
                        break;
                    }
            if (f == null && h1Ok) f = new Field(h1, "dom:h1", 0.3);
            if (f == null && !og.isEmpty()) f = new Field(og, "meta:og:title", 0.3);
            if (f == null && !ht.isEmpty()) f = new Field(ht, "html:title", 0.3);
            r.title = f;
        }

        List<Block> content = new ArrayList<>();
        for (Block b : m.blocks) if (b.content) content.add(b);
        // "đầu bài": vài khối ngay trước khối nội dung đầu tiên và các khối nội dung đầu; "đuôi bài": các khối nội dung cuối
        List<Block> head = new ArrayList<>(), tail = new ArrayList<>();
        int firstIdx = content.isEmpty() ? 0 : content.get(0).id;
        // vùng "gần nội dung": khối nội dung và các khối cách nó tối đa 2 vị trí (dòng ngày/tác giả hay bị coi là phụ)
        boolean[] nearFlag = new boolean[m.blocks.size()];
        for (Block c : content) {
            nearFlag[c.id] = true;
            if (!c.heading) continue;
            for (int j = Math.max(0, c.id - 2); j <= Math.min(m.blocks.size() - 1, c.id + 2); j++) nearFlag[j] = true;
        }
        if (!content.isEmpty()) {     // vài khối ngay sau nội dung (dòng "Tác giả: ...", "Nguồn: ...")
            int last = content.get(content.size() - 1).id;
            for (int j = last + 1; j <= Math.min(m.blocks.size() - 1, last + 3); j++) nearFlag[j] = true;
        }
        for (int i = 0; i < nearFlag.length; i++) if (nearFlag[i] && m.blocks.get(i).words <= 25) head.add(m.blocks.get(i));
        for (int i = Math.max(0, content.size() - 4); i < content.size(); i++) tail.add(content.get(i));

        // ---- ngày đăng từ DOM / văn bản (chỉ trong vùng nội dung; bỏ qua trang danh sách có nhiều ngày khác nhau)
        if (r.date == null && !archiveUrl(m.doc.baseUri())) {
            // Trang danh sách: nhiều tiêu đề mỗi tiêu đề kèm một dòng ngày ngay trước/sau; hoặc rất nhiều dòng ngắn là ngày.
            int dateLines = 0, headingPairs = 0;
            for (int i = 0; i < m.blocks.size(); i++) {
                Block b = m.blocks.get(i);
                if (!b.content || b.words > 14) continue;
                if (b.words <= 8 && parseText(b.text, "x", 0) != null && (pureDateLine(b.text) || startsWithDate(b.text))) dateLines++;
                if (!b.heading) continue;
                for (int j = Math.max(0, i - 2); j <= Math.min(m.blocks.size() - 1, i + 2); j++) {
                    Block c = m.blocks.get(j);
                    if (j != i && c.words <= 8 && (pureDateLine(c.text) || startsWithDate(c.text) || shortDateLine(c.text))
                            && parseText(c.text, "x", 0) != null) {
                        headingPairs++;
                        break;
                    }
                }
            }
            boolean listing = headingPairs >= 2 || dateLines >= 6;
            if (!listing) {
                for (Element e : m.doc.select(DATE_SELECTORS)) {
                    Integer pos = m.position.get(e);
                    Block eb = pos == null ? null : m.blockAt(pos);
                    if (!m.inContent(e) && !(eb != null && nearFlag[eb.id])) continue;
                    String v = e.hasAttr("datetime") ? e.attr("datetime") : (e.hasAttr("content") ? e.attr("content") : e.text());
                    Field f = dateField(v, e.normalName().equals("time") ? "dom:time" : "dom:class-date", 0.85);
                    if (f == null) f = parseText(e.text(), e.normalName().equals("time") ? "dom:time" : "dom:class-date", 0.75);
                    if (f != null) {
                        r.date = f;
                        break;
                    }
                }
                if (r.date == null)
                    for (Block b : head) {
                        boolean cue = DATE_CUE.matcher(b.text.substring(0, Math.min(60, b.text.length()))).find()
                                || ISO_DT.matcher(b.text).find() || BYLINE_DATE.matcher(b.text).find();
                        if (!cue && !(b.words <= 8 && (pureDateLine(b.text) || (b.words <= 6 && shortDateLine(b.text))))) continue;
                        if (!cue && b.id > 0 && PREV_LABEL.matcher(m.blocks.get(b.id - 1).text).find()) continue;
                        Field f = parseText(b.text, "text:head", 0.6);
                        if (f != null) {
                            r.date = f;
                            break;
                        }
                    }
            }
        }

        // ---- tác giả
        if (r.author != null && k != null && k.author(r.author.value)) r.author = null;
        if (r.author == null) {
            Element a = m.doc.selectFirst("[itemprop=author], a[rel=author], .author-name, .byline, .post-author, "
                    + ".entry-author, [class*=tacgia], [class*=tac-gia], [class*=nguoi-dang]");
            if (a != null && m.inContent(a) && !a.text().isBlank() && a.text().length() <= 60 && !a.text().contains(":")) {
                String v = clean(a.text().replaceFirst("(?iu)^(by|bởi|tác giả|đăng bởi)\\s*[:：]?\\s*", ""));
                if (!v.isEmpty() && !ORG.matcher(v).find()) r.author = new Field(v, "dom:author", 0.7);
            }
        }
        if (r.author == null) {
            // dòng "Tác giả: X" / "Người đăng: X" ở bất kỳ vị trí nào trong nội dung; chỉ nhận khi mọi dòng cho cùng một giá trị
            java.util.Set<String> vals = new java.util.LinkedHashSet<>();
            for (Block b : head) {
                if (b.heading || b.words > 12) continue;
                Matcher mm = AUTHOR_CUE.matcher(b.text);
                if (mm.find()) {
                    String v = clean(mm.group(1));
                    if (!v.isEmpty() && v.split("\\s+").length <= 6 && Character.isUpperCase(v.codePointAt(0))) vals.add(v);
                }
            }
            if (vals.size() == 1) r.author = new Field(vals.iterator().next(), "text:byline", 0.65);
        }
        if (r.author == null) {
            List<Block> scan = new ArrayList<>(head);
            scan.addAll(tail);
            for (Block b : scan) {
                if (b.heading || b.words > 25) continue;
                Matcher mm = AUTHOR_CUE.matcher(b.text);
                boolean strict = mm.find();
                if (!strict && b.words <= 10) {
                    mm = AUTHOR_LOOSE.matcher(b.text);
                    if (!mm.find()) mm = null;
                } else if (!strict) mm = null;
                if (mm != null) {
                    String v = clean(mm.group(1));
                    if (v.isEmpty() || v.split("\\s+").length > 6 || !Character.isUpperCase(v.codePointAt(0))) continue;
                    r.author = new Field(v, "text:byline", 0.6);
                    break;
                }
            }
        }
        // ---- nguồn: thường ở cuối bài
        for (int i = content.size() - 1; i >= Math.max(0, content.size() - 6); i--) {
            Matcher mm = SOURCE_CUE.matcher(content.get(i).text);
            if (mm.find() && content.get(i).words <= 25) {
                r.sourceRef = new Field(clean(mm.group(1)), "text:tail", 0.6);
                break;
            }
        }
    }

    // ---------------------------------------------------------------- tiện ích

    private static String meta(Document d, String attr, String key) {
        Element e = d.selectFirst("meta[" + attr + "=" + key + "]");
        return e == null ? "" : e.attr("content").trim();
    }

    private static JsonNode findArticle(JsonNode n) {
        if (n == null) return null;
        if (n.isArray()) {
            for (JsonNode c : n) {
                JsonNode f = findArticle(c);
                if (f != null) return f;
            }
            return null;
        }
        if (n.isObject()) {
            JsonNode t = n.get("@type");
            String ts = t == null ? "" : t.toString();
            if (ts.matches("(?i).*(Article|BlogPosting|NewsArticle|WebPage|Report|CreativeWork).*") && n.has("datePublished"))
                return n;
            if (n.has("@graph")) return findArticle(n.get("@graph"));
            if (ts.matches("(?i).*(Article|BlogPosting|NewsArticle).*")) return n;
        }
        return null;
    }

    private static String text(JsonNode n) {
        if (n == null) return "";
        if (n.isTextual()) return n.asText();
        if (n.hasNonNull("name")) return n.get("name").asText();
        return "";
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String stripSuffix(String title, String h1) {
        if (title == null) return "";
        title = clean(title);
        if (!h1.isEmpty() && title.startsWith(h1)) return h1;
        Matcher m = SITE_SUFFIX.matcher(title);
        if (m.find() && m.start() > 10) return title.substring(0, m.start()).trim();
        return title;
    }

    private static boolean plausible(LocalDate d) {
        return d.getYear() >= 2000 && !d.isAfter(LocalDate.now().plusDays(1));
    }

    private static Field dateField(String v, String source, double conf) {
        if (v == null || v.isBlank()) return null;
        v = v.trim();
        LocalDate d = null;
        try {
            d = OffsetDateTime.parse(v).toLocalDate();
        } catch (Exception ignored) { }
        if (d == null) try {
            d = LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v);
        } catch (Exception ignored) { }
        if (d == null) try {
            d = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
        } catch (Exception ignored) { }
        if (d == null) return null;
        return plausible(d) ? new Field(d.toString(), source, conf) : null;
    }

    /** Tìm ngày trong một đoạn văn bản (dd/mm/yyyy, "ngày d tháng m năm y", ISO, tiếng Anh). */
    static Field parseText(String t, String source, double conf) {
        if (t == null) return null;
        Matcher m = VI_LONG.matcher(t);
        if (m.find()) return build(m.group(3), m.group(2), m.group(1), source, conf);
        m = DMY.matcher(t);
        if (m.find()) {
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            if (b > 12 && a <= 12) return build(m.group(3), m.group(1), m.group(2), source, conf);   // mm/dd/yyyy
            return build(m.group(3), m.group(2), m.group(1), source, conf);
        }
        m = ISO.matcher(t);
        if (m.find()) return build(m.group(1), m.group(2), m.group(3), source, conf);
        m = EN_LONG.matcher(t);
        if (m.find()) return build(m.group(3), String.valueOf(month(m.group(1))), m.group(2), source, conf);
        m = DAY_MON_YEAR.matcher(t);
        if (m.find()) return build(m.group(3), String.valueOf(month(m.group(2))), m.group(1), source, conf);
        return null;
    }

    private static int month(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace(".", "");
        if (n.length() < 3) return 0;
        for (int i = 0; i < 12; i++) if (MONTHS[i].startsWith(n.substring(0, 3))) return i + 1;
        return 0;
    }

    /** "A A" -> "A": nhiều CMS in tiêu đề hai lần trong một thẻ. */
    static String dedupDouble(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        int n = t.length();
        if (n % 2 == 1) {
            String a = t.substring(0, n / 2), b = t.substring(n / 2 + 1);
            if (a.equals(b)) return a;
        }
        return t;
    }

    private static Field build(String y, String mo, String d, String source, double conf) {
        try {
            LocalDate ld = LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
            return plausible(ld) ? new Field(ld.toString(), source, conf) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
