package vn.hust.ir.search;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.NullFragmenter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLEncoder;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.SimpleSpanFragmenter;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.index.Term;

/**
 * Truy vấn trên chỉ mục BM25: tìm theo tiêu đề (trọng số cao), nội dung và từ trong URL;
 * thưởng thêm khi cụm từ khớp liền nhau; lọc theo host / loại tài liệu; đếm facet; tạo đoạn trích có tô sáng.
 */
public final class SearchEngine implements Closeable {
    public static final class Hit {
        public int id;
        public String url, host, type, title, titleHtml, snippet;
        public int words;
        public float score;
    }

    public static final class Facet {
        public String value;
        public int count;

        Facet(String value, int count) {
            this.value = value;
            this.count = count;
        }
    }

    public static final class Result {
        public String query;
        public boolean relaxed;     // true nếu không có kết quả chứa đủ mọi từ nên đã nới sang "một trong các từ"
        public long total;
        public int page, size;
        public long tookMs;
        public List<Hit> hits = new ArrayList<>();
        public List<Facet> hosts = new ArrayList<>(), types = new ArrayList<>();
    }

    private static final String[] FIELDS = {"title_t", "content", "url_t"};
    private static final Map<String, Float> BOOSTS = Map.of("title_t", 3.0f, "content", 1.0f, "url_t", 0.6f);
    private static final String[] EXACT_FIELDS = {"title_x", "content_x"};
    private static final Map<String, Float> EXACT_BOOSTS = Map.of("title_x", 3.0f, "content_x", 0.3f);
    private static final int MAX_FACETS = 40;

    private final FSDirectory dir;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final Analyzer analyzer = ViAnalyzer.forIndex();

    public SearchEngine(Path indexDir) throws IOException {
        dir = FSDirectory.open(indexDir);
        reader = DirectoryReader.open(dir);
        searcher = new IndexSearcher(reader);
    }

    public int numDocs() {
        return reader.numDocs();
    }

    public Result search(String q, String host, String type, int page, int size) throws IOException {
        long t0 = System.nanoTime();
        q = q == null ? "" : Text.nfc(q).strip();
        page = Math.max(1, page);
        size = Math.min(50, Math.max(1, size));
        Result res = new Result();
        res.query = q;
        res.page = page;
        res.size = size;
        boolean hasFilter = (host != null && !host.isEmpty()) || (type != null && !type.isEmpty());
        if (q.isEmpty() && !hasFilter) return res;

        Query textQuery;
        if (q.isEmpty()) {          // chỉ duyệt theo bộ lọc
            textQuery = new MatchAllDocsQuery();
        } else {
            textQuery = buildQuery(q, QueryParser.Operator.AND);
            if (count(textQuery, host, type) == 0 && tokens(q).size() > 1) {
                textQuery = buildQuery(q, QueryParser.Operator.OR);
                res.relaxed = true;
            }
        }

        Query full = withFilters(textQuery, host, type);
        TopDocs top = searcher.search(full, page * size);
        res.total = top.totalHits.value;

        Highlighter contentHl = highlighter(textQuery, "content", true);
        Highlighter titleHl = highlighter(textQuery, "title_t", false);
        int from = (page - 1) * size;
        for (int i = from; i < top.scoreDocs.length; i++) {
            ScoreDoc sd = top.scoreDocs[i];
            Document d = searcher.storedFields().document(sd.doc);
            Hit h = new Hit();
            h.id = d.getField("id").numericValue().intValue();
            h.url = d.get("url");
            h.host = d.get("host");
            h.type = d.get("type");
            h.title = d.get("title");
            h.words = d.getField("words").numericValue().intValue();
            h.score = sd.score;
            String text = d.get("content");
            h.titleHtml = highlight(titleHl, "title_t", h.title, escape(h.title));
            String flat = text.replaceAll("\\s+", " ");
            h.snippet = highlight(contentHl, "content", flat, escape(flat.length() > 240 ? flat.substring(0, 240) + "…" : flat));
            res.hits.add(h);
        }

        res.hosts = facet(textQuery, host, type, "host_dv", true);
        res.types = facet(textQuery, host, type, "type_dv", false);
        res.tookMs = (System.nanoTime() - t0) / 1_000_000;
        return res;
    }

    // ---- xây dựng truy vấn ----

    private Query buildQuery(String q, QueryParser.Operator op) throws IOException {
        MultiFieldQueryParser p = new MultiFieldQueryParser(FIELDS, analyzer, BOOSTS);
        p.setDefaultOperator(op);
        Query main;
        try {
            main = p.parse(q);
        } catch (ParseException e) {
            try {
                main = p.parse(QueryParser.escape(q));
            } catch (ParseException e2) {
                throw new IOException(e2);
            }
        }
        List<String> toks = tokens(q);
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(main, Occur.MUST);
        if (toks.size() >= 2) {
            b.add(new BoostQuery(phrase("title_t", toks, 0), 4f), Occur.SHOULD);
            b.add(new BoostQuery(phrase("content", toks, 2), 1.5f), Occur.SHOULD);
        }
        // thưởng điểm khi khớp đúng dấu (không ảnh hưởng tập kết quả)
        try {
            MultiFieldQueryParser px = new MultiFieldQueryParser(EXACT_FIELDS, analyzer, EXACT_BOOSTS);
            px.setDefaultOperator(QueryParser.Operator.OR);
            b.add(new BoostQuery(px.parse(QueryParser.escape(q)), 1f), Occur.SHOULD);
            List<String> ex = exactTokens(q);
            if (ex.size() >= 2) {
                b.add(new BoostQuery(phrase("title_x", ex, 0), 3f), Occur.SHOULD);
                b.add(new BoostQuery(phrase("content_x", ex, 2), 1f), Occur.SHOULD);
            }
        } catch (ParseException ignored) { }
        return b.build();
    }

    private static Query phrase(String field, List<String> toks, int slop) {
        PhraseQuery.Builder pb = new PhraseQuery.Builder();
        for (String t : toks) pb.add(new Term(field, t));
        pb.setSlop(slop);
        return pb.build();
    }

    private List<String> exactTokens(String q) throws IOException {
        return tokens("content_x", q);
    }

    private List<String> tokens(String q) throws IOException {
        return tokens("content", q);
    }

    private List<String> tokens(String field, String q) throws IOException {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(field, q)) {
            CharTermAttribute a = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(a.toString());
            ts.end();
        }
        return out;
    }

    private static Query withFilters(Query text, String host, String type) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(text, Occur.MUST);
        if (host != null && !host.isEmpty()) b.add(new TermQuery(new Term("host", host)), Occur.FILTER);
        if (type != null && !type.isEmpty()) b.add(new TermQuery(new Term("type", type)), Occur.FILTER);
        return b.build();
    }

    private int count(Query text, String host, String type) throws IOException {
        return searcher.count(withFilters(text, host, type));
    }

    // ---- facet ----

    /** Đếm giá trị của trường doc-values trên tập kết quả; bỏ chính bộ lọc của chiều đó để người dùng đổi lựa chọn được. */
    private List<Facet> facet(Query text, String host, String type, String dvField, boolean isHost) throws IOException {
        Query q = withFilters(text, isHost ? null : host, isHost ? type : null);
        CountCollector c = new CountCollector(dvField);
        searcher.search(q, c);
        Map<String, Integer> counts = c.result();
        List<Facet> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) out.add(new Facet(e.getKey(), e.getValue()));
        out.sort((a, b) -> b.count != a.count ? b.count - a.count : a.value.compareTo(b.value));
        return out.size() > MAX_FACETS ? new ArrayList<>(out.subList(0, MAX_FACETS)) : out;
    }

    private static final class CountCollector implements Collector {
        private final String field;
        private final List<SortedDocValues> dvs = new ArrayList<>();
        private final List<int[]> counts = new ArrayList<>();

        CountCollector(String field) {
            this.field = field;
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext ctx) throws IOException {
            SortedDocValues dv = ctx.reader().getSortedDocValues(field);
            if (dv == null) return new LeafCollector() {
                @Override public void setScorer(Scorable scorer) { }
                @Override public void collect(int doc) { }
            };
            int[] cnt = new int[dv.getValueCount()];
            dvs.add(dv);
            counts.add(cnt);
            return new LeafCollector() {
                @Override public void setScorer(Scorable scorer) { }

                @Override
                public void collect(int doc) throws IOException {
                    if (dv.advanceExact(doc)) cnt[dv.ordValue()]++;
                }
            };
        }

        Map<String, Integer> result() throws IOException {
            Map<String, Integer> out = new HashMap<>();
            for (int s = 0; s < dvs.size(); s++) {
                int[] cnt = counts.get(s);
                for (int ord = 0; ord < cnt.length; ord++)
                    if (cnt[ord] > 0) out.merge(dvs.get(s).lookupOrd(ord).utf8ToString(), cnt[ord], Integer::sum);
            }
            return out;
        }
    }

    // ---- tô sáng ----

    private Highlighter highlighter(Query q, String field, boolean fragment) {
        SimpleHTMLFormatter fmt = new SimpleHTMLFormatter("<mark>", "</mark>");
        QueryScorer scorer = new QueryScorer(q, field);
        Highlighter h = new Highlighter(fmt, new SimpleHTMLEncoder(), scorer);
        h.setTextFragmenter(fragment ? new SimpleSpanFragmenter(scorer, 260) : new NullFragmenter());
        h.setMaxDocCharsToAnalyze(60_000);
        return h;
    }

    private String highlight(Highlighter h, String field, String text, String fallback) {
        try {
            String s = h.getBestFragments(analyzer.tokenStream(field, text), text, 2, " … ");
            if (s == null || s.isBlank()) return fallback;
            return s.replaceAll("</mark>(\\s+)<mark>", "$1");      // gộp các từ khóa liền nhau thành một vệt tô
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void close() throws IOException {
        reader.close();
        dir.close();
    }
}
