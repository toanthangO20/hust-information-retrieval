package vn.hust.ir.eval;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Kiểm tra các độ đo với ví dụ trong slide Ch05. */
public class MetricsTest {
    private static Map<Integer, Integer> rels(int... relevantIds) {
        Map<Integer, Integer> m = new HashMap<>();
        for (int d : relevantIds) m.put(d, 1);
        return m;
    }

    @Test
    public void dcgExample513() {
        assertEquals(9.61, Metrics.dcg(new int[] {3, 2, 3, 0, 0, 1, 2, 2, 3, 0}, 10), 0.01);
    }

    @Test
    public void ndcgExample514() {
        Map<Integer, Integer> r = new HashMap<>();
        r.put(1, 0); r.put(2, 1); r.put(3, 2); r.put(4, 2);     // d1..d4 có mức 0,1,2,2 như ví dụ 5.14
        assertEquals(1.0, Metrics.ndcg(Arrays.asList(3, 4, 2, 1), r, 4), 1e-9);
        assertEquals(0.9203, Metrics.ndcg(Arrays.asList(3, 2, 4, 1), r, 4), 1e-3);
    }

    @Test
    public void averagePrecisionExample510() {
        // 5 tài liệu phù hợp: 1..5; danh sách d1* d2 d3* d4 d5* -> AP = (1/1 + 2/3 + 3/5)/5 ~ 0.45
        Map<Integer, Integer> r = rels(1, 3, 5, 100, 101);
        List<Integer> l = Arrays.asList(1, 2, 3, 4, 5);
        assertEquals(0.45, Metrics.averagePrecision(l, r), 0.005);
        assertEquals(0.33, Metrics.averagePrecision(Arrays.asList(1, 2, 3, 4, 6), r), 0.005);
    }

    @Test
    public void mapExample512() {
        Map<Integer, Integer> r1 = new HashMap<>();
        List<Integer> l1 = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);       // RNRNNRNNRR
        for (int d : new int[] {1, 3, 6, 9, 10, 101, 102, 103, 104, 105}) r1.put(d, 1);
        assertEquals(0.31, Metrics.averagePrecision(l1, r1), 0.01);
        Map<Integer, Integer> r2 = new HashMap<>();
        List<Integer> l2 = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);       // NRNNRNRNNN, 8 phù hợp
        for (int d : new int[] {2, 5, 7, 101, 102, 103, 104, 105}) r2.put(d, 1);
        assertEquals(0.17, Metrics.averagePrecision(l2, r2), 0.01);
    }

    @Test
    public void kappaExample515() {
        boolean[] a = new boolean[400], b = new boolean[400];
        int i = 0;
        for (int k = 0; k < 300; k++, i++) { a[i] = true; b[i] = true; }
        for (int k = 0; k < 20; k++, i++) { a[i] = true; b[i] = false; }
        for (int k = 0; k < 10; k++, i++) { a[i] = false; b[i] = true; }
        assertEquals(0.776, Metrics.kappa(a, b), 0.01);
    }
}
