package vn.hust.ir.eval;

import java.util.Arrays;
import java.util.Random;

/** Kiểm định thống kê ghép cặp theo truy vấn: kiểm định hoán vị dấu (randomization) và khoảng tin cậy bootstrap. */
public final class Significance {
    private Significance() {}

    public static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return x.length == 0 ? 0 : s / x.length;
    }

    /** p-value hai phía của kiểm định hoán vị dấu trên hiệu số a[i] - b[i]. */
    public static double randomizationP(double[] a, double[] b, int rounds, long seed) {
        int n = a.length;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = a[i] - b[i];
        double obs = Math.abs(mean(d));
        if (obs == 0) return 1.0;
        Random r = new Random(seed);
        int ge = 0;
        for (int t = 0; t < rounds; t++) {
            double s = 0;
            for (int i = 0; i < n; i++) s += r.nextBoolean() ? d[i] : -d[i];
            if (Math.abs(s / n) >= obs - 1e-12) ge++;
        }
        return (ge + 1.0) / (rounds + 1.0);
    }

    /** Khoảng tin cậy bootstrap 95% của hiệu số trung bình a - b. */
    public static double[] bootstrapCi(double[] a, double[] b, int rounds, long seed) {
        int n = a.length;
        Random r = new Random(seed);
        double[] m = new double[rounds];
        for (int t = 0; t < rounds; t++) {
            double s = 0;
            for (int i = 0; i < n; i++) {
                int j = r.nextInt(n);
                s += a[j] - b[j];
            }
            m[t] = s / n;
        }
        Arrays.sort(m);
        return new double[] {m[(int) (0.025 * rounds)], m[(int) (0.975 * rounds)]};
    }

    /** Hiệu chỉnh Holm-Bonferroni; trả p đã hiệu chỉnh theo thứ tự đầu vào. */
    public static double[] holm(double[] p) {
        int m = p.length;
        Integer[] idx = new Integer[m];
        for (int i = 0; i < m; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Double.compare(p[x], p[y]));
        double[] out = new double[m];
        double run = 0;
        for (int k = 0; k < m; k++) {
            run = Math.max(run, Math.min(1.0, p[idx[k]] * (m - k)));
            out[idx[k]] = run;
        }
        return out;
    }
}
