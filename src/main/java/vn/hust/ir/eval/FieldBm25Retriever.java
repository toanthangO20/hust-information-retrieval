package vn.hust.ir.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * BM25 với tập trường và trọng số tuỳ chọn, dùng để so sánh tín hiệu bóc tách (khối nội dung chính, văn bản liên kết)
 * trên chỉ mục {@code index/ver1x} (Ch07 BM25F dạng đơn giản: tổng BM25 từng trường có trọng số).
 * {@code prior > 0}: nhân điểm với (1 + prior·z) với z = phân vị PageRank nội dung của trang (0..1).
 */
public final class FieldBm25Retriever extends Bm25Retriever {
    private final String[] fields;
    private final float[] boosts;
    private final double prior;
    private final float[] prz;

    public FieldBm25Retriever(LuceneCorpus corpus, float k1, float b, String[] fields, float[] boosts, double prior) throws IOException {
        super(corpus, k1, b);
        this.fields = fields;
        this.boosts = boosts;
        this.prior = prior;
        this.prz = new float[corpus.n];
        if (prior > 0) {
            for (int d = 0; d < corpus.n; d++) {
                Number v = corpus.reader.storedFields().document(d, Set.of("prz")).getField("prz") == null ? null
                        : corpus.reader.storedFields().document(d, Set.of("prz")).getField("prz").numericValue();
                prz[d] = v == null ? 0f : v.floatValue();
            }
        }
    }

    @Override
    protected Query buildQuery(Map<String, Double> termWeights) {
        BooleanQuery.Builder q = new BooleanQuery.Builder();
        for (Map.Entry<String, Double> e : termWeights.entrySet()) {
            BooleanQuery.Builder t = new BooleanQuery.Builder();
            for (int i = 0; i < fields.length; i++)
                t.add(new BoostQuery(new TermQuery(new Term(fields[i], e.getKey())), boosts[i]), Occur.SHOULD);
            q.add(new BoostQuery(t.build(), e.getValue().floatValue()), Occur.SHOULD);
        }
        return q.build();
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        if (prior <= 0) return super.search(query, k);
        List<Scored> base = super.search(query, Math.max(k, 100));
        // idOf -> doc Lucene để lấy z
        Map<Integer, Integer> docOf = new java.util.HashMap<>();
        for (int d = 0; d < corpus.n; d++) docOf.put(corpus.idOf[d], d);
        List<Scored> out = new ArrayList<>();
        for (Scored s : base) {
            int d = docOf.get(s.id);
            out.add(new Scored(s.id, (float) (s.score * (1 + prior * prz[d]))));
        }
        out.sort((a, b2) -> Float.compare(b2.score, a.score));
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }
}
