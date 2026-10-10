package vn.hust.ir.extract;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * P2 — Mô hình trang: DOM chuẩn hóa được duỗi thành dãy "token" (thẻ / từ) đúng như bài toán Ch10 tr.17,
 * đồng thời chia thành các khối văn bản (block) phục vụ các thuật toán tìm khối nội dung.
 * Lưu ý: hàm tạo sẽ xóa script/style... khỏi {@code doc}; hãy trích metadata từ doc TRƯỚC khi tạo mô hình.
 */
public final class PageModel {
    /** Khối văn bản liên tục nằm trong một phần tử cấp khối. */
    public static final class Block {
        public int id;
        public Element container;
        public String text;
        public int words;
        public int linkWords;       // số từ nằm trong thẻ <a>
        public int startTok, endTok; // chỉ số token (gồm cả thẻ nội dòng nằm giữa)
        public boolean heading;
        public boolean content;     // kết quả phân loại cuối cùng

        public double linkDensity() {
            return words == 0 ? 0 : (double) linkWords / words;
        }
    }

    private static final Set<String> BLOCK_TAGS = Set.of("address", "article", "aside", "blockquote", "body", "dd",
            "details", "dialog", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2",
            "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table",
            "tbody", "thead", "tfoot", "tr", "td", "th", "ul", "caption", "summary", "iframe", "option", "center");
    private static final Set<String> VOID_TAGS = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");
    private static final String REMOVE = "script,style,noscript,template,svg,canvas,select,textarea,button,input,"
            + "[hidden],[aria-hidden=true],.hidden,.hide,.d-none,.sr-only,.screen-reader-text,[style*=display:none]";

    public final Document doc;
    public final List<Block> blocks = new ArrayList<>();
    /** Chiều dài dãy token; isTag[i]: token i là thẻ; wordBlock[i]: id khối chứa từ i (−1 nếu là thẻ); inLink[i]: từ nằm trong <a>. */
    public int tokenCount;
    public boolean[] isTag;
    public int[] wordBlock;
    public boolean[] inLink;
    /** Vị trí token của các phần tử (a, img, ...) để xác định chúng nằm trong khối nội dung hay không. */
    public final Map<Element, Integer> position = new IdentityHashMap<>();

    public PageModel(Document doc) {
        this.doc = doc;
        doc.select(REMOVE).remove();
        build();
    }

    private void build() {
        final Element body = doc.body();
        if (body == null) {
            isTag = new boolean[0];
            wordBlock = new int[0];
            inLink = new boolean[0];
            return;
        }
        final List<Boolean> tagList = new ArrayList<>();
        final List<Integer> blockList = new ArrayList<>();
        final List<Boolean> linkList = new ArrayList<>();
        final StringBuilder buf = new StringBuilder();
        final int[] st = new int[6];        // 0: words, 1: linkWords, 2: startTok, 3: endTok, 4: linkDepth
        final Element[] container = new Element[1];
        final boolean[] headingFlag = new boolean[1];
        final ArrayList<Element> stack = new ArrayList<>();

        Runnable flush = () -> {
            String t = buf.toString().replaceAll("[\\s\\u00A0]+", " ").trim();
            if (!t.isEmpty() && st[0] > 0) {
                Block b = new Block();
                b.id = blocks.size();
                b.container = container[0];
                b.text = t;
                b.words = st[0];
                b.linkWords = st[1];
                b.startTok = st[2];
                b.endTok = st[3];
                b.heading = headingFlag[0];
                blocks.add(b);
                for (int k = b.startTok; k <= b.endTok; k++)
                    if (!tagList.get(k) && blockList.get(k) == -1) blockList.set(k, b.id);
            }
            buf.setLength(0);
            st[0] = st[1] = 0;
            st[2] = st[3] = -1;
            container[0] = null;
            headingFlag[0] = false;
        };
        st[2] = st[3] = -1;

        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element) {
                    Element e = (Element) node;
                    String tn = e.normalName();
                    boolean blk = BLOCK_TAGS.contains(tn);
                    if (blk) flush.run();
                    position.put(e, tagList.size());
                    tagList.add(true); blockList.add(-1); linkList.add(false);
                    if (tn.equals("a")) st[4]++;
                    stack.add(e);
                    if (tn.equals("br")) buf.append('\n');
                } else if (node instanceof TextNode) {
                    String s = ((TextNode) node).text();
                    if (s.isBlank()) { buf.append(' '); return; }
                    if (container[0] == null) {
                        Element p = (Element) node.parent();
                        while (p != null && !BLOCK_TAGS.contains(p.normalName())) p = p.parent();
                        container[0] = p;
                        headingFlag[0] = p != null && p.normalName().matches("h[1-6]");
                    }
                    String[] w = s.trim().split("[\\s\\u00A0]+");
                    for (String x : w) {
                        if (x.isEmpty()) continue;
                        int idx = tagList.size();
                        if (st[2] < 0) st[2] = idx;
                        st[3] = idx;
                        tagList.add(false); blockList.add(-1); linkList.add(st[4] > 0);
                        st[0]++;
                        if (st[4] > 0) st[1]++;
                    }
                    buf.append(s).append(' ');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (!(node instanceof Element)) return;
                Element e = (Element) node;
                String tn = e.normalName();
                if (tn.equals("a") && st[4] > 0) st[4]--;
                if (!VOID_TAGS.contains(tn)) {
                    tagList.add(true); blockList.add(-1); linkList.add(false);
                }
                if (BLOCK_TAGS.contains(tn)) flush.run();
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            }
        }, body);
        flush.run();

        tokenCount = tagList.size();
        isTag = new boolean[tokenCount];
        wordBlock = new int[tokenCount];
        inLink = new boolean[tokenCount];
        for (int i = 0; i < tokenCount; i++) {
            isTag[i] = tagList.get(i);
            wordBlock[i] = blockList.get(i);
            inLink[i] = linkList.get(i);
        }
    }

    public int totalWords() {
        int n = 0;
        for (Block b : blocks) n += b.words;
        return n;
    }

    /** Khối chứa token vị trí pos (tính cả thẻ nội dòng nằm giữa các từ), hoặc null. */
    public Block blockAt(int pos) {
        int lo = 0, hi = blocks.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Block b = blocks.get(mid);
            if (pos < b.startTok) hi = mid - 1;
            else if (pos > b.endTok) lo = mid + 1;
            else return b;
        }
        return null;
    }

    /** Văn bản của các khối được chọn, ngăn cách bằng dòng trống. */
    public String contentText() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks)
            if (b.content) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(b.text);
            }
        return sb.toString();
    }

    public int contentWords() {
        int n = 0;
        for (Block b : blocks) if (b.content) n += b.words;
        return n;
    }

    /** Token của khối nội dung đầu tiên và cuối cùng (bao lồi), hoặc null nếu không có. */
    public int[] contentHull() {
        int lo = Integer.MAX_VALUE, hi = -1;
        for (Block b : blocks)
            if (b.content) {
                lo = Math.min(lo, b.startTok);
                hi = Math.max(hi, b.endTok);
            }
        return hi < 0 ? null : new int[]{lo, hi};
    }

    /** Phần tử nằm trong nội dung chính? (đã có kết quả phân loại content) */
    public boolean inContent(Element e) {
        Integer p = position.get(e);
        if (p == null) return false;
        Block b = blockAt(p);
        if (b != null) return b.content;
        int[] hull = contentHull();
        if (hull == null || p < hull[0] || p > hull[1]) return false;
        // phần tử nằm giữa các khối (vd. liên kết ảnh): nằm trong nội dung nếu các khối kề đều là nội dung
        Block prev = null, next = null;
        for (Block x : blocks) {
            if (x.endTok < p) prev = x;
            else if (x.startTok > p) { next = x; break; }
        }
        return prev != null && next != null && prev.content && next.content;
    }

    /** Phần tử chung thấp nhất của mọi khối nội dung (null nếu không có). */
    public Element contentRoot() {
        Element root = null;
        for (Block b : blocks) {
            if (!b.content || b.container == null) continue;
            if (root == null) root = b.container;
            else {
                java.util.Set<Element> anc = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                for (Element p = root; p != null; p = p.parent()) anc.add(p);
                Element q = b.container;
                while (q != null && !anc.contains(q)) q = q.parent();
                if (q != null) root = q;
            }
        }
        return root;
    }

    /** Mô tả ngắn của phần tử: tag#id.class (tối đa 3 class) cùng 2 tổ tiên gần nhất. */
    public static String describe(Element e) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Element p = e; p != null && depth < 3 && !p.normalName().equals("#root"); p = p.parent(), depth++) {
            StringBuilder one = new StringBuilder(p.normalName());
            if (!p.id().isEmpty()) one.append('#').append(p.id());
            int c = 0;
            for (String cl : p.classNames()) {
                if (cl.isEmpty() || c++ >= 3) continue;
                one.append('.').append(cl);
            }
            if (sb.length() > 0) sb.insert(0, " > ");
            sb.insert(0, one);
            if (!p.id().isEmpty()) break;
        }
        return sb.toString();
    }

    public static String normalizeForHash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
    }

    public static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
