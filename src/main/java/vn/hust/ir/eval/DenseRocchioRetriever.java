package vn.hust.ir.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M5 — Phản hồi giả định trong không gian vec-tơ, dẫn đường bằng xếp hạng lai (Rocchio dày).
 * 1) Lai bước đầu: RRF(từ khóa, dense) -> lấy m tài liệu đầu (bước này có R@100 cao nhất trong các mô hình hiện có).
 * 2) q' = chuẩn hóa( alpha * q + (1-alpha) * trung bình vec-tơ m tài liệu đó ).
 * 3) Truy hồi lại bằng q' và lai lần nữa với danh sách từ khóa: RRF(từ khóa, dense(q'), [dense(q)]).
 * Ý tưởng: danh sách từ khóa sửa lỗi "trôi chủ đề" của dense, rồi vec-tơ phản hồi kéo truy vấn về đúng cụm tài liệu,
 * bù cho truy vấn ngắn/diễn đạt lại mà BM25 không khớp từ.
 */
public final class DenseRocchioRetriever implements Retriever {
    private final Retriever lexical;
    private final DenseRetriever dense;
    private final int m, rrfK, depth;
    private final double alpha, wLex, wDense, wOrig;

    public DenseRocchioRetriever(Retriever lexical, DenseRetriever dense, int m, double alpha,
                                 double wLex, double wDense, double wOrig, int rrfK, int depth) {
        this.lexical = lexical;
        this.dense = dense;
        this.m = m;
        this.alpha = alpha;
        this.wLex = wLex;
        this.wDense = wDense;
        this.wOrig = wOrig;
        this.rrfK = rrfK;
        this.depth = depth;
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        List<Scored> lex = lexical.search(query, depth);
        float[] q = dense.embedQuery(query);
        List<Scored> d0 = dense.searchVec(q, depth);

        Map<Integer, Double> first = new HashMap<>();
        fuse(first, lex, 1.0);
        fuse(first, d0, 1.0);
        List<Scored> seed = sorted(first, m);

        float[] q2 = new float[q.length];
        for (int j = 0; j < q.length; j++) q2[j] = (float) (alpha * q[j]);
        if (!seed.isEmpty()) {
            double beta = (1 - alpha) / seed.size();
            for (Scored s : seed) {
                float[] v = dense.vectorOf(s.id);
                for (int j = 0; j < q.length; j++) q2[j] += (float) (beta * v[j]);
            }
        }
        double norm = 0;
        for (float x : q2) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int j = 0; j < q2.length; j++) q2[j] /= (float) norm;

        List<Scored> d1 = dense.searchVec(q2, depth);
        Map<Integer, Double> fin = new HashMap<>();
        fuse(fin, lex, wLex);
        fuse(fin, d1, wDense);
        if (wOrig > 0) fuse(fin, d0, wOrig);
        return sorted(fin, k);
    }

    private void fuse(Map<Integer, Double> acc, List<Scored> list, double w) {
        for (int r = 0; r < list.size(); r++) acc.merge(list.get(r).id, w / (rrfK + r + 1), Double::sum);
    }

    private static List<Scored> sorted(Map<Integer, Double> acc, int k) {
        List<Scored> out = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : acc.entrySet()) out.add(new Scored(e.getKey(), e.getValue().floatValue()));
        out.sort((a, b) -> b.score != a.score ? Float.compare(b.score, a.score) : Integer.compare(a.id, b.id));
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }
}
