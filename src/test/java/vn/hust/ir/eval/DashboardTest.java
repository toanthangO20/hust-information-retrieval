package vn.hust.ir.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Dữ liệu báo cáo trực quan: quy ước chấm từng trang, tách host, và các số liệu chính tính lại từ eval/runs. */
public class DashboardTest {
    @Test
    public void pageScoresFollowBlockEvalConventions() {
        assertEquals(1.0, Dashboard.pageScores(0, 0, 0)[2], 1e-9);      // không có nội dung vàng, không chọn gì = đúng hoàn toàn
        assertEquals(0.0, Dashboard.pageScores(0, 10, 0)[2], 1e-9);     // chọn nhầm khi trang không có nội dung
        assertEquals(0.0, Dashboard.pageScores(0, 0, 10)[2], 1e-9);     // bỏ sót toàn bộ
        double[] s = Dashboard.pageScores(5, 10, 20);
        assertEquals(0.5, s[0], 1e-9);
        assertEquals(0.25, s[1], 1e-9);
        assertEquals(2 * 0.5 * 0.25 / 0.75, s[2], 1e-9);
    }

    @Test
    public void hostIsLowercasedWithoutPortOrPath() {
        assertEquals("abc.hust.edu.vn", Dashboard.host("https://ABC.hust.edu.vn:8443/x/y?z=1#f"));
        assertEquals("hust.edu.vn", Dashboard.host("http://hust.edu.vn"));
        assertEquals("?", Dashboard.host("mailto:a@b"));
    }

    /** Chốt các con số được báo cáo: M4 là hybrid w=2 (chọn trên dev), chỉ VSM khác BM25 có ý nghĩa trên test. */
    @Test
    @SuppressWarnings("unchecked")
    public void headlineTestNumbersMatchReport() throws Exception {
        assumeTrue(Files.exists(Paths.get("eval/runs/test/hybrid_w-2.run")) && Files.exists(Paths.get("eval/qrels/test.tsv")));
        Map<String, Object> r = (Map<String, Object>) Dashboard.retrieval("test");
        assertEquals(40, r.get("n"));
        double m4 = 0, bm25 = 0;
        for (Map<String, Object> s : (List<Map<String, Object>>) r.get("systems")) {
            double v = ((Map<String, Double>) s.get("mean")).get("nDCG@10");
            if (s.get("id").equals("hybrid_w-2")) {
                assertEquals("M4", s.get("code"));
                m4 = v;
            }
            if (s.get("id").equals("bm25")) bm25 = v;
        }
        assertEquals(0.6854, m4, 1e-4);
        assertEquals(0.6752, bm25, 1e-4);
        for (Map<String, Object> v : (List<Map<String, Object>>) r.get("vsBaseline")) {
            boolean sig = (Double) v.get("pHolm") < 0.05;
            assertEquals("chỉ VSM có ý nghĩa: " + v.get("id"), v.get("id").equals("vsm"), sig);
            assertTrue((Double) v.get("lo") <= (Double) v.get("delta") && (Double) v.get("delta") <= (Double) v.get("hi"));
        }
    }
}
