package vn.hust.ir.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.document.Document;

import vn.hust.ir.eval.EvalIo.Topic;

/**
 * So sánh trực tiếp các mô hình truy hồi trên cùng một truy vấn (trang /compare.html của ứng dụng web).
 * Với truy vấn thuộc bộ đánh giá (chọn theo mã q01–q60), mỗi tài liệu được tô theo nhãn phù hợp trong qrels
 * và mỗi cột có nDCG@10 của chính truy vấn đó — cùng cách chấm với {@code eval.bat eval}.
 * Mô hình được nạp một lần (lần gọi đầu tiên hoặc {@link #warmUp()}); cross-encoder không có ở đây vì ~12 giây/truy vấn.
 */
public final class LiveCompare {
    /** Mã đặc tả (như --systems của eval.bat run), mã mô hình, nhãn. */
    static final String[][] SYSTEMS = {
            {"current", "W", "Công cụ web hiện tại"},
            {"vsm", "M1", "VSM tf-idf"},
            {"bm25", "M2", "BM25 đa trường"},
            {"prf:docs=5,terms=20,lambda=0.5", "M3", "BM25 + PRF"},
            {"dense", "D", "Dense e5-small"},
            {"hybrid:w=2", "M4", "Hybrid (RRF)"},
    };

    private EvalMain.Factory factory;
    private final Map<String, Retriever> retrievers = new LinkedHashMap<>();
    private final Map<String, String> unavailable = new LinkedHashMap<>();
    private final Map<Integer, Integer> luceneOf = new HashMap<>();
    private final List<Topic> topics = new ArrayList<>();
    private final Map<String, Map<Integer, Integer>> qrels = new HashMap<>();

    public synchronized void warmUp() throws Exception {
        if (factory != null) return;
        factory = new EvalMain.Factory();
        for (int d = 0; d < factory.corpus.n; d++) luceneOf.put(factory.corpus.idOf[d], d);
        boolean denseOk = Files.exists(Paths.get("models", "multilingual-e5-small", "model_int8.onnx"))
                && Files.exists(Paths.get("index", "ver1", "dense_e5small_int8_256.bin"));
        for (String[] s : SYSTEMS) {
            if ((s[0].startsWith("dense") || s[0].startsWith("hybrid")) && !denseOk) {
                unavailable.put(s[0], "thiếu models/multilingual-e5-small hoặc index/ver1/dense_e5small_int8_256.bin (eval.bat embed)");
                continue;
            }
            try {
                retrievers.put(s[0], factory.create(s[0]));
            } catch (Exception e) {
                unavailable.put(s[0], String.valueOf(e.getMessage()));
            }
        }
        Path tp = Paths.get("eval", "topics.tsv");
        if (Files.exists(tp)) topics.addAll(EvalIo.readTopics(tp, "all"));
        for (String split : new String[]{"dev", "test"}) {
            Path q = Paths.get("eval", "qrels", split + ".tsv");
            if (Files.exists(q)) qrels.putAll(EvalIo.readQrels(q));
        }
    }

    /** Danh sách mô hình (kèm lý do nếu không dùng được) và các truy vấn có nhãn. */
    public Map<String, Object> info() throws Exception {
        warmUp();
        List<Map<String, Object>> sys = new ArrayList<>();
        for (String[] s : SYSTEMS) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", s[0]);
            x.put("code", s[1]);
            x.put("label", s[2]);
            if (unavailable.containsKey(s[0])) x.put("unavailable", unavailable.get(s[0]));
            sys.add(x);
        }
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Topic t : topics) {
            if (!qrels.containsKey(t.qid)) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("qid", t.qid);
            x.put("split", t.split);
            x.put("type", t.type);
            x.put("text", t.text);
            x.put("narrative", t.narrative);
            x.put("relevant", Metrics.numRelevant(qrels.get(t.qid)));
            ts.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("systems", sys);
        out.put("topics", ts);
        return out;
    }

    /** Top-k của từng mô hình; {@code qid} (có thể null) để lấy nhãn phù hợp và tính nDCG@10. */
    // [DEMO:live-compare] so sánh trực tiếp trên web
    public synchronized Map<String, Object> compare(String query, String qid, List<String> systems, int k) throws Exception {
        warmUp();
        Map<Integer, Integer> rels = qid == null ? null : qrels.get(qid);
        List<Map<String, Object>> cols = new ArrayList<>();
        for (String id : systems) {
            Retriever r = retrievers.get(id);
            if (r == null) continue;
            long t0 = System.nanoTime();
            List<Scored> hits = r.search(query, Math.max(k, 10));
            double ms = (System.nanoTime() - t0) / 1e6;
            List<Integer> ranked = new ArrayList<>();
            for (Scored s : hits) ranked.add(s.id);
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < Math.min(k, hits.size()); i++) {
                int docId = hits.get(i).id;
                Integer ld = luceneOf.get(docId);
                Document d = ld == null ? null : factory.corpus.reader.storedFields().document(ld, Set.of("url", "host", "title"));
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("id", docId);
                h.put("title", d == null ? "" : d.get("title"));
                h.put("url", d == null ? "" : d.get("url"));
                h.put("host", d == null ? "" : d.get("host"));
                if (rels != null) h.put("rel", rels.get(docId));      // null = chưa gán nhãn
                list.add(h);
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id);
            for (String[] s : SYSTEMS) if (s[0].equals(id)) { c.put("code", s[1]); c.put("label", s[2]); }
            c.put("ms", Math.round(ms * 10) / 10.0);
            if (rels != null) {
                c.put("ndcg", Dashboard.r4(Metrics.ndcg(ranked, rels, 10)));
                c.put("judged", Dashboard.r4(Metrics.judgedAt(ranked, rels, 10)));
            }
            c.put("hits", list);
            cols.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("qid", rels == null ? null : qid);
        out.put("columns", cols);
        return out;
    }
}
