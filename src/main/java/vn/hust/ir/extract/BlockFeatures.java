package vn.hust.ir.extract;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jsoup.nodes.Element;

import vn.hust.ir.extract.PageModel.Block;

/**
 * Đặc trưng mức khối cho bộ phân loại có giám sát (nội dung chính / khuôn mẫu): thống kê văn bản, mật độ liên kết,
 * vị trí, tần suất khối trong host (khuôn mẫu), từ khóa class/id của các phần tử tổ tiên và đặc trưng của khối kề.
 */
public final class BlockFeatures {
    private BlockFeatures() {}

    public static final String[] NAMES = {
            "bias", "logWords", "linkDensity", "isHeading", "isP", "isLi", "isTd", "isDiv", "isH1",
            "relPos", "afterH1", "tplRatio", "tplHigh", "negNear", "negFar", "posNear", "posFar",
            "inNav", "inFooter", "inAside", "inHeaderTag", "inForm", "inArticle", "inMain", "depth",
            "prevLogWords", "nextLogWords", "prevLink", "nextLink", "prevTpl", "nextTpl",
            "parentLinkDensity", "dateLike", "avgWordLen", "bteSel", "rulesSel", "isFirstOfH1Group"};

    private static final Pattern NEG = Pattern.compile("(menu|(^|[^a-z])nav|navbar|footer|(^|[^a-z])foot($|[^a-z])|sidebar|side-bar|widget|breadcrumb|crumb|"
            + "comment|related|share|social|banner|search|login|pagination|pager|(^|[^a-z])tags?($|[^a-z])|toolbar|topbar|"
            + "advert|(^|[^a-z])ads?($|[^a-z])|popup|modal|cookie|hotline|sitemap|copyright|counter|rating|vote|lien-quan|other-news|"
            + "newer|older|recent|latest|map|skip|header|stats|thongke|danhmuc|bannerbox|quangcao|binhluan)");
    private static final Pattern POS = Pattern.compile("(content|article|post|entry|detail|bodytext|(^|[^a-z])body($|[^a-z])|main|"
            + "news|field-item|page-content|single|description|noidung|chitiet|baiviet|tin)");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|ngày\\s+\\d{1,2}|"
            + "(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2})");

    public static double[][] compute(PageModel m, Extractor.HostStats hs, boolean[] bteSel, boolean[] rulesSel) {
        List<Block> bs = m.blocks;
        int n = bs.size();
        double[][] x = new double[n][NAMES.length];
        int firstH1 = -1;
        for (Block b : bs)
            if (b.container != null && b.container.normalName().equals("h1")) {
                firstH1 = b.id;
                break;
            }
        double[] tpl = new double[n];
        for (int i = 0; i < n; i++) {
            if (hs != null && hs.pages >= 20) {
                Integer c = hs.df.get(PageModel.hash64(PageModel.normalizeForHash(bs.get(i).text)));
                tpl[i] = c == null ? 0 : Math.min(1.0, (double) c / hs.pages);
            }
        }
        for (int i = 0; i < n; i++) {
            Block b = bs.get(i);
            double[] f = x[i];
            Element c = b.container;
            String tag = c == null ? "" : c.normalName();
            int k = 0;
            f[k++] = 1;
            f[k++] = Math.log1p(b.words);
            f[k++] = b.linkDensity();
            f[k++] = b.heading ? 1 : 0;
            f[k++] = tag.equals("p") ? 1 : 0;
            f[k++] = tag.equals("li") ? 1 : 0;
            f[k++] = tag.equals("td") || tag.equals("th") ? 1 : 0;
            f[k++] = tag.equals("div") ? 1 : 0;
            f[k++] = tag.equals("h1") ? 1 : 0;
            f[k++] = n <= 1 ? 0 : (double) i / (n - 1);
            f[k++] = firstH1 >= 0 && i >= firstH1 ? 1 : 0;
            f[k++] = tpl[i];
            f[k++] = tpl[i] >= 0.2 ? 1 : 0;
            double negNear = 0, negFar = 0, posNear = 0, posFar = 0;
            int depth = 0;
            boolean inNav = false, inFooter = false, inAside = false, inHeader = false, inForm = false, inArticle = false, inMain = false;
            for (Element p = c; p != null; p = p.parent(), depth++) {
                String t = p.normalName();
                String key = (p.id() + " " + p.className()).toLowerCase(Locale.ROOT);
                boolean neg = !key.isBlank() && NEG.matcher(key).find();
                boolean pos = !key.isBlank() && POS.matcher(key).find();
                if (depth <= 3) {
                    if (neg) negNear = 1;
                    if (pos) posNear = 1;
                } else if (depth <= 8) {
                    if (neg) negFar = 1;
                    if (pos) posFar = 1;
                }
                switch (t) {
                    case "nav": inNav = true; break;
                    case "footer": inFooter = true; break;
                    case "aside": inAside = true; break;
                    case "header": inHeader = true; break;
                    case "form": inForm = true; break;
                    case "article": inArticle = true; break;
                    case "main": inMain = true; break;
                    default:
                }
            }
            f[k++] = negNear;
            f[k++] = negFar;
            f[k++] = posNear;
            f[k++] = posFar;
            f[k++] = inNav ? 1 : 0;
            f[k++] = inFooter ? 1 : 0;
            f[k++] = inAside ? 1 : 0;
            f[k++] = inHeader && !inArticle ? 1 : 0;
            f[k++] = inForm ? 1 : 0;
            f[k++] = inArticle ? 1 : 0;
            f[k++] = inMain ? 1 : 0;
            f[k++] = Math.min(depth, 25) / 25.0;
            f[k++] = i > 0 ? Math.log1p(bs.get(i - 1).words) : 0;
            f[k++] = i + 1 < n ? Math.log1p(bs.get(i + 1).words) : 0;
            f[k++] = i > 0 ? bs.get(i - 1).linkDensity() : 0;
            f[k++] = i + 1 < n ? bs.get(i + 1).linkDensity() : 0;
            f[k++] = i > 0 ? tpl[i - 1] : 0;
            f[k++] = i + 1 < n ? tpl[i + 1] : 0;
            // mật độ liên kết của phần tử cha (bối cảnh gần)
            double pl = 0;
            if (c != null && c.parent() != null) {
                Element par = c.parent();
                int tw = par.text().length();
                int lw = 0;
                for (Element a : par.select("a")) lw += a.text().length();
                pl = tw == 0 ? 0 : Math.min(1.0, (double) lw / tw);
            }
            f[k++] = pl;
            f[k++] = b.words <= 12 && DATE.matcher(b.text).find() ? 1 : 0;
            f[k++] = Math.min(12, (double) b.text.length() / Math.max(1, b.words)) / 12.0;
            f[k++] = bteSel != null && bteSel[i] ? 1 : 0;
            f[k++] = rulesSel != null && rulesSel[i] ? 1 : 0;
            f[k++] = b.heading && i == firstH1 ? 1 : 0;
        }
        return x;
    }
}
