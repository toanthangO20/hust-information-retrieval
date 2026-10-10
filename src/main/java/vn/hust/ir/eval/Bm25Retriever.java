package vn.hust.ir.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;

/**
 * M2 — BM25 đa trường (Ch07): tổng điểm BM25 của từng âm tiết truy vấn trên tiêu đề / nội dung / từ trong URL với trọng số trường.
 * Ngữ nghĩa OR (mọi âm tiết đều được tính điểm), không thưởng cụm từ, không nới AND->OR, để chỉ đo riêng hàm xếp hạng.
 * `k1`, `b` chỉnh được lúc truy vấn (chuẩn hóa độ dài lưu ở chỉ mục không phụ thuộc k1, b).
 */
public class Bm25Retriever implements Retriever {
    static final String[] FIELDS = {"title_t", "content", "url_t"};
    static final float[] BOOSTS = {3.0f, 1.0f, 0.6f};

    protected final LuceneCorpus corpus;
    protected final IndexSearcher searcher;

    public Bm25Retriever(LuceneCorpus corpus, float k1, float b) {
        this.corpus = corpus;
        this.searcher = new IndexSearcher(corpus.reader);
        this.searcher.setSimilarity(new BM25Similarity(k1, b));
    }

    /** Truy vấn một âm tiết trên mọi trường, nhân với trọng số w. */
    static Query termQuery(String term, double w) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (int i = 0; i < FIELDS.length; i++)
            b.add(new BoostQuery(new TermQuery(new Term(FIELDS[i], term)), BOOSTS[i]), Occur.SHOULD);
        return new BoostQuery(b.build(), (float) w);
    }

    protected Query buildQuery(Map<String, Double> termWeights) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (Map.Entry<String, Double> e : termWeights.entrySet()) b.add(termQuery(e.getKey(), e.getValue()), Occur.SHOULD);
        return b.build();
    }

    protected Map<String, Double> queryTerms(String query) throws IOException {
        Map<String, Double> w = new LinkedHashMap<>();
        for (String t : corpus.tokens(query)) w.merge(t, 1.0, Double::sum);   // trọng số = tần suất trong truy vấn
        return w;
    }

    protected List<Scored> run(Query q, int k) throws IOException {
        List<Scored> out = new ArrayList<>();
        for (ScoreDoc sd : searcher.search(q, k).scoreDocs) out.add(new Scored(corpus.idOf[sd.doc], sd.score));
        return out;
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        Map<String, Double> w = queryTerms(query);
        if (w.isEmpty()) return new ArrayList<>();
        return run(buildQuery(w), k);
    }
}
