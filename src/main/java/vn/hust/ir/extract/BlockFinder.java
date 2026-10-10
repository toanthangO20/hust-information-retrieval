package vn.hust.ir.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jsoup.nodes.Element;

import vn.hust.ir.extract.PageModel.Block;

/**
 * P3 — Các thuật toán tìm khối nội dung. Mỗi thuật toán đặt {@link Block#content} cho các khối của trang.
 * <ul>
 *   <li>{@link Bte}: bài toán tối ưu của Ch10 tr.17 (cực đại hóa thẻ trước i + từ trong [i,j] + thẻ sau j), O(n).</li>
 *   <li>{@link Rules}: phân loại từng khối theo đặc trưng nông (số từ, mật độ liên kết, khối kề) kiểu boilerpipe,
 *       cho phép nhiều khối rời nhau, sau đó hợp nhất các cụm gần nhau.</li>
 *   <li>{@link Css}: quy tắc CSS theo host/CMS (Ch10 tr.18: "xử lý thủ công trên DOM bằng XPath/CSS").</li>
 *   <li>{@link Template}: loại khối lặp lại ở nhiều trang cùng host (khuôn mẫu site).</li>
 * </ul>
 */
public interface BlockFinder {
    String name();

    void apply(PageModel m);

    /** BTE (Body Text Extraction). linkAware=true: từ nằm trong thẻ &lt;a&gt; được tính như thẻ. */
    final class Bte implements BlockFinder {
        private final boolean linkAware;

        public Bte(boolean linkAware) {
            this.linkAware = linkAware;
        }

        @Override
        public String name() {
            return linkAware ? "bte-link" : "bte";
        }

        @Override
        public void apply(PageModel m) {
            for (Block b : m.blocks) b.content = false;
            int n = m.tokenCount;
            if (n == 0) return;
            boolean[] tag = new boolean[n];
            int totalTags = 0;
            for (int k = 0; k < n; k++) {
                tag[k] = m.isTag[k] || (linkAware && m.inLink[k]);
                if (tag[k]) totalTags++;
            }
            // g(i) = Tc[i] − Wc[i];  h(j) = Wc[j+1] − Tc[j+1];  cực đại g(i) + h(j) với i ≤ j
            int tc = 0, wc = 0;            // Tc[i], Wc[i] (số thẻ/từ trước vị trí i)
            int bestG = Integer.MIN_VALUE, bestGi = 0;
            long best = Long.MIN_VALUE;
            int bi = 0, bj = -1;
            for (int j = 0; j < n; j++) {
                int g = tc - wc;
                if (g > bestG) {
                    bestG = g;
                    bestGi = j;
                }
                if (tag[j]) tc++; else wc++;
                int h = wc - tc;
                long f = (long) bestG + h;
                if (f > best) {
                    best = f;
                    bi = bestGi;
                    bj = j;
                }
            }
            if (bj < 0) return;
            for (Block b : m.blocks)
                if (b.startTok >= bi && b.startTok <= bj) {
                    // với linkAware, khối chỉ gồm liên kết không được coi là nội dung
                    b.content = !linkAware || b.linkWords < b.words;
                }
        }
    }

    /** Phân loại theo đặc trưng nông của khối và hai khối kề (số từ, mật độ liên kết). */
    final class Rules implements BlockFinder {
        private final boolean keepLargest;
        private final int maxGap;
        private final double keepRatio;

        public Rules(boolean keepLargest, int maxGap, double keepRatio) {
            this.keepLargest = keepLargest;
            this.maxGap = maxGap;
            this.keepRatio = keepRatio;
        }

        @Override
        public String name() {
            return keepLargest ? "rules-largest" : "rules";
        }

        @Override
        public void apply(PageModel m) {
            List<Block> bs = m.blocks;
            int n = bs.size();
            boolean[] c = new boolean[n];
            for (int i = 0; i < n; i++) {
                Block cur = bs.get(i);
                double pld = i > 0 ? bs.get(i - 1).linkDensity() : 0;
                int pw = i > 0 ? bs.get(i - 1).words : 0;
                int nw = i + 1 < n ? bs.get(i + 1).words : 0;
                if (cur.linkDensity() <= 0.333) {
                    if (pld <= 0.555) {
                        if (cur.words <= 16) {
                            if (nw <= 15) c[i] = pw > 4; else c[i] = true;
                        } else c[i] = true;
                    } else {
                        if (cur.words <= 40) c[i] = nw > 17; else c[i] = true;
                    }
                }
            }
            if (keepLargest && n > 0) {
                // gom các khối nội dung thành cụm (khoảng trống ≤ maxGap khối), giữ cụm lớn và các cụm ≥ keepRatio
                List<int[]> clusters = new ArrayList<>();   // {firstIdx, lastIdx, words}
                int first = -1, last = -1, words = 0;
                for (int i = 0; i < n; i++) {
                    if (!c[i]) continue;
                    if (first >= 0 && i - last - 1 <= maxGap) {
                        last = i;
                        words += bs.get(i).words;
                    } else {
                        if (first >= 0) clusters.add(new int[]{first, last, words});
                        first = last = i;
                        words = bs.get(i).words;
                    }
                }
                if (first >= 0) clusters.add(new int[]{first, last, words});
                int max = 0;
                for (int[] k : clusters) max = Math.max(max, k[2]);
                boolean[] keep = new boolean[n];
                for (int[] k : clusters) {
                    if (k[2] >= keepRatio * max) {
                        for (int i = k[0]; i <= k[1]; i++)
                            keep[i] = c[i] || bs.get(i).linkDensity() < 0.5;     // lấp khoảng trống ít liên kết
                        // tiêu đề ngay trước cụm
                        for (int i = k[0] - 1; i >= Math.max(0, k[0] - 2); i--)
                            if (bs.get(i).heading) keep[i] = true;
                    }
                }
                c = keep;
            }
            for (int i = 0; i < n; i++) bs.get(i).content = c[i];
        }
    }

    /** Quy tắc CSS: chọn phần tử đầu tiên (theo thứ tự quy tắc) có đủ văn bản; các khối bên trong là nội dung. */
    final class Css implements BlockFinder {
        private final List<String> selectors;
        private final List<String> excludes;
        private final int minWords;

        public Css(List<String> selectors, List<String> excludes, int minWords) {
            this.selectors = selectors;
            this.excludes = excludes;
            this.minWords = minWords;
        }

        @Override
        public String name() {
            return "css";
        }

        /** Trả về true nếu có quy tắc khớp. */
        public boolean matched(PageModel m) {
            return root(m) != null;
        }

        private Element root(PageModel m) {
            for (String sel : selectors) {
                Element best = null;
                int bestWords = 0;
                try {
                    for (Element e : m.doc.select(sel)) {
                        int w = e.text().split("\\s+").length;
                        if (w > bestWords) {
                            best = e;
                            bestWords = w;
                        }
                    }
                } catch (Exception ignored) { }
                if (best != null && bestWords >= minWords) return best;
            }
            return null;
        }

        @Override
        public void apply(PageModel m) {
            for (Block b : m.blocks) b.content = false;
            Element root = root(m);
            if (root == null) return;
            List<Element> ex = new ArrayList<>();
            for (String s : excludes) {
                try {
                    ex.addAll(root.select(s));
                } catch (Exception ignored) { }
            }
            for (Block b : m.blocks) {
                Element c = b.container;
                boolean inside = false;
                boolean excluded = false;
                for (Element p = c; p != null; p = p.parent()) {
                    if (p == root) { inside = true; break; }
                }
                if (inside) {
                    for (Element x : ex)
                        for (Element p = c; p != null && p != root; p = p.parent())
                            if (p == x) { excluded = true; break; }
                }
                b.content = inside && !excluded;
            }
        }
    }

    /** Bộ phân loại học có giám sát (hồi quy logistic trên đặc trưng khối); cần thống kê host cho đặc trưng khuôn mẫu. */
    final class Learned implements BlockFinder {
        private final LogReg model;
        private final Extractor.HostStats hs;

        public Learned(LogReg model, Extractor.HostStats hs) {
            this.model = model;
            this.hs = hs;
        }

        @Override
        public String name() {
            return "learned";
        }

        @Override
        public void apply(PageModel m) {
            int n = m.blocks.size();
            boolean[] bte = new boolean[n], rl = new boolean[n];
            new Bte(false).apply(m);
            for (int i = 0; i < n; i++) bte[i] = m.blocks.get(i).content;
            new Rules(false, 2, 0.5).apply(m);
            for (int i = 0; i < n; i++) rl[i] = m.blocks.get(i).content;
            double[][] f = BlockFeatures.compute(m, hs, bte, rl);
            for (int i = 0; i < n; i++) m.blocks.get(i).content = model.prob(f[i]) >= model.threshold;
        }
    }

    /** Loại khối có tần suất xuất hiện (số trang) cao trong cùng host = khuôn mẫu site. */
    final class Template implements BlockFinder {
        private final Map<Long, Integer> df;
        private final int threshold;

        public Template(Map<Long, Integer> df, int pagesInHost, double ratio, int minDf) {
            this.df = df;
            this.threshold = Math.max(minDf, (int) Math.ceil(ratio * pagesInHost));
        }

        @Override
        public String name() {
            return "template";
        }

        public boolean isBoilerplate(Block b) {
            Integer c = df.get(PageModel.hash64(PageModel.normalizeForHash(b.text)));
            return c != null && c >= threshold;
        }

        @Override
        public void apply(PageModel m) {
            for (Block b : m.blocks)
                if (b.content && isBoilerplate(b)) b.content = false;
        }
    }
}
