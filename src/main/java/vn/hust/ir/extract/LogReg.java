package vn.hust.ir.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Hồi quy logistic (L2) nhỏ gọn, huấn luyện bằng gradient descent; lưu/đọc tham số dạng JSON. */
public final class LogReg {
    public String[] names;
    public double[] mean, std, w;
    public double threshold = 0.5;

    private static final ObjectMapper JSON = new ObjectMapper();

    // [DEMO:logreg-predict] dự đoán
    public double prob(double[] x) {
        double z = 0;
        for (int j = 0; j < w.length; j++) z += w[j] * ((x[j] - mean[j]) / std[j]);
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** Huấn luyện; sw = trọng số mẫu (vd. căn bậc hai số từ của khối). */
    // [DEMO:logreg-train] huấn luyện: z-score, L2, trọng số mẫu
    public static LogReg train(String[] names, List<double[]> xs, List<Integer> ys, List<Double> sw, double l2, int epochs, double lr) {
        int d = names.length, n = xs.size();
        LogReg m = new LogReg();
        m.names = names;
        m.mean = new double[d];
        m.std = new double[d];
        m.w = new double[d];
        for (int j = 1; j < d; j++) {              // j = 0 là bias: không chuẩn hóa
            double s = 0;
            for (double[] x : xs) s += x[j];
            m.mean[j] = s / n;
            double v = 0;
            for (double[] x : xs) v += (x[j] - m.mean[j]) * (x[j] - m.mean[j]);
            m.std[j] = Math.max(1e-6, Math.sqrt(v / n));
        }
        m.std[0] = 1;
        double wsum = 0;
        for (double s : sw) wsum += s;
        double[] z = new double[d];
        for (int ep = 0; ep < epochs; ep++) {
            double[] g = new double[d];
            for (int i = 0; i < n; i++) {
                double[] x = xs.get(i);
                double dot = 0;
                for (int j = 0; j < d; j++) {
                    z[j] = (x[j] - m.mean[j]) / m.std[j];
                    dot += m.w[j] * z[j];
                }
                double p = 1.0 / (1.0 + Math.exp(-dot));
                double err = (p - ys.get(i)) * sw.get(i) / wsum;
                for (int j = 0; j < d; j++) g[j] += err * z[j];
            }
            for (int j = 0; j < d; j++) m.w[j] -= lr * (g[j] + (j == 0 ? 0 : l2 * m.w[j]));
        }
        return m;
    }

    public void save(Path f) throws IOException {
        Files.createDirectories(f.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), this);
    }

    public static LogReg load(Path f) throws IOException {
        return JSON.readValue(f.toFile(), LogReg.class);
    }

    public static LogReg loadResource(String res) throws IOException {
        try (InputStream in = LogReg.class.getResourceAsStream(res)) {
            return in == null ? null : JSON.readValue(in, LogReg.class);
        }
    }
}
