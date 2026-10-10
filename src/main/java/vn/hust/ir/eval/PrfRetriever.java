package vn.hust.ir.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.ScoreDoc;

/**
 * M3 — BM25 + giả lập phản hồi (pseudo-relevance feedback, Ch06 §1, dạng RM3/Rocchio):
 * coi `fbDocs` kết quả đầu của BM25 là liên quan, chọn `fbTerms` âm tiết có trọng số tf·idf cao nhất trong các tài liệu đó,
 * rồi truy vấn lại với q' = (1 - lambda)·q0 + lambda·q_fb (trọng số mỗi chuỗi được chuẩn hóa tổng bằng 1).
 */
public final class PrfRetriever extends Bm25Retriever {
    private static final int MAX_CHARS = 6000;
    private final int fbDocs, fbTerms;
    private final double lambda;

    public PrfRetriever(LuceneCorpus corpus, float k1, float b, int fbDocs, int fbTerms, double lambda) {
        super(corpus, k1, b);
        this.fbDocs = fbDocs;
        this.fbTerms = fbTerms;
        this.lambda = lambda;
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        Map<String, Double> q0 = queryTerms(query);
        if (q0.isEmpty()) return new ArrayList<>();
        ScoreDoc[] first = searcher.search(buildQuery(q0), fbDocs).scoreDocs;
        if (first.length == 0) return run(buildQuery(q0), k);

        Map<String, Double> cand = new HashMap<>();
        for (ScoreDoc sd : first) {
            List<String> toks = corpus.tokens(truncate(corpus.content(sd.doc)));
            if (toks.isEmpty()) continue;
            Map<String, Integer> tf = new HashMap<>();
            for (String t : toks) tf.merge(t, 1, Integer::sum);
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String t = e.getKey();
                if (t.length() < 2 || t.chars().allMatch(Character::isDigit)) continue;
                cand.merge(t, (double) e.getValue() / toks.size(), Double::sum);
            }
        }
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (Map.Entry<String, Double> e : cand.entrySet()) {
            int df = corpus.reader.docFreq(new Term("content", e.getKey()));
            if (df < 3) continue;                                   // bỏ âm tiết quá hiếm/nhiễu
            double idf = Math.log((double) corpus.n / df);
            scored.add(Map.entry(e.getKey(), e.getValue() / first.length * idf));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Double> fb = new LinkedHashMap<>();
        double sum = 0;
        for (int i = 0; i < Math.min(fbTerms, scored.size()); i++) {
            fb.put(scored.get(i).getKey(), scored.get(i).getValue());
            sum += scored.get(i).getValue();
        }
        double q0Sum = q0.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> mix = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : q0.entrySet()) mix.merge(e.getKey(), (1 - lambda) * e.getValue() / q0Sum, Double::sum);
        for (Map.Entry<String, Double> e : fb.entrySet()) mix.merge(e.getKey(), lambda * e.getValue() / sum, Double::sum);
        return run(buildQuery(mix), k);
    }

    private static String truncate(String s) {
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s;
    }
}
