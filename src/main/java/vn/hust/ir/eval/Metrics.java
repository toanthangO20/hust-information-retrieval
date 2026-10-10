package vn.hust.ir.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Các độ đo của Ch05. `rels`: mã tài liệu -> mức phù hợp (0/1/2) của một truy vấn; tài liệu vắng mặt coi là không phù hợp.
 * Phù hợp nhị phân = mức >= 1. IDCG của nDCG tính từ toàn bộ qrels (không phải từ danh sách trả về của mô hình),
 * nên so sánh được giữa các mô hình khác nhau.
 */
public final class Metrics {
    private Metrics() {}

    /** DCG theo slide: r1 + sum_{i>=2} r_i / log2(i). */
    static double dcg(int[] gains, int k) {
        double s = 0;
        for (int i = 0; i < Math.min(k, gains.length); i++) s += i == 0 ? gains[0] : gains[i] / (Math.log(i + 1) / Math.log(2));
        return s;
    }

    public static double ndcg(List<Integer> ranked, Map<Integer, Integer> rels, int k) {
        int[] g = new int[Math.min(k, ranked.size())];
        for (int i = 0; i < g.length; i++) g[i] = rels.getOrDefault(ranked.get(i), 0);
        List<Integer> all = new ArrayList<>(rels.values());
        all.sort(Collections.reverseOrder());
        int[] ideal = new int[Math.min(k, all.size())];
        for (int i = 0; i < ideal.length; i++) ideal[i] = all.get(i);
        double idcg = dcg(ideal, k);
        return idcg == 0 ? 0 : dcg(g, k) / idcg;
    }

    public static int numRelevant(Map<Integer, Integer> rels) {
        int r = 0;
        for (int v : rels.values()) if (v >= 1) r++;
        return r;
    }

    /** Average Precision: tổng P@k tại các vị trí phù hợp chia cho số tài liệu phù hợp trong qrels. */
    public static double averagePrecision(List<Integer> ranked, Map<Integer, Integer> rels) {
        int total = numRelevant(rels);
        if (total == 0) return 0;
        double s = 0;
        int hit = 0;
        for (int i = 0; i < ranked.size(); i++)
            if (rels.getOrDefault(ranked.get(i), 0) >= 1) s += (double) ++hit / (i + 1);
        return s / total;
    }

    public static double reciprocalRank(List<Integer> ranked, Map<Integer, Integer> rels, int k) {
        for (int i = 0; i < Math.min(k, ranked.size()); i++)
            if (rels.getOrDefault(ranked.get(i), 0) >= 1) return 1.0 / (i + 1);
        return 0;
    }

    public static double precisionAt(List<Integer> ranked, Map<Integer, Integer> rels, int k) {
        int hit = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) if (rels.getOrDefault(ranked.get(i), 0) >= 1) hit++;
        return (double) hit / k;
    }

    public static double recallAt(List<Integer> ranked, Map<Integer, Integer> rels, int k) {
        int total = numRelevant(rels);
        if (total == 0) return 0;
        int hit = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) if (rels.getOrDefault(ranked.get(i), 0) >= 1) hit++;
        return (double) hit / total;
    }

    /** Tỉ lệ kết quả trong top-k đã có nhãn (kể cả nhãn 0) — đo độ phủ của pool. */
    public static double judgedAt(List<Integer> ranked, Map<Integer, Integer> rels, int k) {
        int j = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) if (rels.containsKey(ranked.get(i))) j++;
        return (double) j / k;
    }

    /** Hệ số kappa của Cohen giữa hai danh sách nhãn nhị phân cùng độ dài (Ch05, ví dụ 5.15). */
    public static double kappa(boolean[] a, boolean[] b) {
        int n = a.length, yy = 0, nn = 0, ya = 0, yb = 0;
        for (int i = 0; i < n; i++) {
            if (a[i]) ya++;
            if (b[i]) yb++;
            if (a[i] && b[i]) yy++;
            if (!a[i] && !b[i]) nn++;
        }
        double pa = (double) (yy + nn) / n;
        double pe = ((double) ya / n) * ((double) yb / n) + (1 - (double) ya / n) * (1 - (double) yb / n);
        return pe == 1 ? 1 : (pa - pe) / (1 - pe);
    }
}
