package vn.hust.ir.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M4 — Hợp nhất xếp hạng của một mô hình từ khóa và một mô hình vec-tơ dày bằng Reciprocal Rank Fusion:
 * score(d) = w_lex / (rrfK + rank_lex(d)) + w_dense / (rrfK + rank_dense(d)).
 * RRF chỉ dùng thứ hạng nên không cần chuẩn hóa điểm của hai mô hình có thang điểm khác nhau.
 */
public final class HybridRetriever implements Retriever {
    private final Retriever lexical, dense;
    private final double wLex, wDense;
    private final int rrfK, depth;

    public HybridRetriever(Retriever lexical, Retriever dense, double wLex, double wDense, int rrfK, int depth) {
        this.lexical = lexical;
        this.dense = dense;
        this.wLex = wLex;
        this.wDense = wDense;
        this.rrfK = rrfK;
        this.depth = depth;
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        Map<Integer, Double> fused = new HashMap<>();
        add(fused, lexical.search(query, depth), wLex);
        add(fused, dense.search(query, depth), wDense);
        List<Scored> out = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : fused.entrySet()) out.add(new Scored(e.getKey(), e.getValue().floatValue()));
        out.sort((a, b) -> b.score != a.score ? Float.compare(b.score, a.score) : Integer.compare(a.id, b.id));
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    // [DEMO:rrf] RRF: w / (k + hạng)
    private void add(Map<Integer, Double> fused, List<Scored> list, double w) {
        for (int r = 0; r < list.size(); r++) fused.merge(list.get(r).id, w / (rrfK + r + 1), Double::sum);
    }
}
