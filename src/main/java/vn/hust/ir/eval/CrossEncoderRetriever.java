package vn.hust.ir.eval;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * M6 — Xếp hạng lại bằng cross-encoder (bge-reranker-v2-m3, ONNX lượng tử hóa).
 * Lấy top-`depth` của hệ thống nền (mặc định hybrid), chấm từng cặp (truy vấn, tiêu đề + đầu nội dung) bằng một lần
 * chạy mô hình chung truy vấn và tài liệu (khác bi-encoder: có chú ý chéo giữa hai bên), rồi xếp lại.
 * `blend` > 0: hợp nhất RRF giữa thứ hạng nền và thứ hạng của reranker (blend = trọng số của nền); 0 = chỉ reranker.
 * Điểm cặp được ghi vào tệp cache để chỉnh tham số trộn không phải chạy lại mô hình.
 */
public final class CrossEncoderRetriever implements Retriever, AutoCloseable {
    private final Retriever base;
    private final LuceneCorpus corpus;
    private final E5Tokenizer tok;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final int depth, maxTokens, batch;
    private final double blend;
    private final int rrfK;
    private final Map<Integer, Integer> docOfId = new HashMap<>();
    private final Map<String, Float> cache = new HashMap<>();
    private final Path cacheFile;

    public CrossEncoderRetriever(Retriever base, LuceneCorpus corpus, Path modelFile, Path tokenizerJson, Path cacheFile,
                                 int depth, int maxTokens, double blend, int rrfK, int threads) throws Exception {
        this.base = base;
        this.corpus = corpus;
        this.tok = new E5Tokenizer(tokenizerJson);          // cùng từ vựng XLM-R (250k) với multilingual-e5
        this.depth = depth;
        this.maxTokens = maxTokens;
        this.blend = blend;
        this.rrfK = rrfK;
        this.batch = 4;
        this.cacheFile = cacheFile;
        for (int d = 0; d < corpus.n; d++) docOfId.put(corpus.idOf[d], d);
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(threads);
        session = env.createSession(modelFile.toString(), so);
        if (Files.exists(cacheFile)) {
            for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                int t = line.lastIndexOf('\t');
                if (t > 0) cache.put(line.substring(0, t), Float.parseFloat(line.substring(t + 1)));
            }
        }
    }

    private String key(String query, int id) {
        return maxTokens + "|" + id + "|" + query.replace('\t', ' ').replace('\n', ' ');
    }

    /** [BOS] q [EOS][EOS] d [EOS], tổng không quá maxTokens (truy vấn tối đa 64 mảnh). */
    private int[] pair(int[] q, int[] d) {
        int ql = Math.min(q.length - 2, 64);
        int dl = Math.min(d.length - 2, maxTokens - ql - 4);
        int[] out = new int[ql + dl + 4];
        out[0] = E5Tokenizer.BOS;
        System.arraycopy(q, 1, out, 1, ql);
        out[ql + 1] = E5Tokenizer.EOS;
        out[ql + 2] = E5Tokenizer.EOS;
        System.arraycopy(d, 1, out, ql + 3, dl);
        out[ql + dl + 3] = E5Tokenizer.EOS;
        return out;
    }

    private float[] score(int[][] batchIds) throws Exception {
        int b = batchIds.length, len = 0;
        for (int[] x : batchIds) len = Math.max(len, x.length);
        long[][] input = new long[b][len], mask = new long[b][len];
        for (int i = 0; i < b; i++) {
            java.util.Arrays.fill(input[i], E5Tokenizer.PAD);
            for (int j = 0; j < batchIds[i].length; j++) {
                input[i][j] = batchIds[i][j];
                mask[i][j] = 1;
            }
        }
        Map<String, OnnxTensor> feed = new HashMap<>();
        try {
            feed.put("input_ids", OnnxTensor.createTensor(env, input));
            feed.put("attention_mask", OnnxTensor.createTensor(env, mask));
            try (OrtSession.Result r = session.run(feed)) {
                float[][] logits = (float[][]) r.get(0).getValue();
                float[] out = new float[b];
                for (int i = 0; i < b; i++) out[i] = logits[i][0];
                return out;
            }
        } finally {
            for (OnnxTensor t : feed.values()) t.close();
        }
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        List<Scored> all = base.search(query, Math.max(k, depth));
        List<Scored> cand = new ArrayList<>(all.subList(0, Math.min(depth, all.size())));
        try {
            int[] qIds = tok.encode(query, 66);
            List<Integer> todo = new ArrayList<>();
            for (int i = 0; i < cand.size(); i++) if (!cache.containsKey(key(query, cand.get(i).id))) todo.add(i);
            List<String> newLines = new ArrayList<>();
            for (int s = 0; s < todo.size(); s += batch) {
                int e = Math.min(todo.size(), s + batch);
                int[][] ids = new int[e - s][];
                for (int i = s; i < e; i++) {
                    int docId = cand.get(todo.get(i)).id;
                    int ld = docOfId.get(docId);
                    String content = corpus.content(ld);
                    if (content.length() > 2500) content = content.substring(0, 2500);
                    ids[i - s] = pair(qIds, tok.encode(corpus.title(ld) + ". " + content, maxTokens));
                }
                float[] sc = score(ids);
                for (int i = s; i < e; i++) {
                    String kk = key(query, cand.get(todo.get(i)).id);
                    cache.put(kk, sc[i - s]);
                    newLines.add(kk + "\t" + sc[i - s]);
                }
            }
            if (!newLines.isEmpty()) {
                Files.createDirectories(cacheFile.toAbsolutePath().getParent());
                try (BufferedWriter w = Files.newBufferedWriter(cacheFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
                    for (String l : newLines) {
                        w.write(l);
                        w.newLine();
                    }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        List<Scored> out = new ArrayList<>();
        if (blend <= 0) {
            for (Scored c : cand) out.add(new Scored(c.id, cache.get(key(query, c.id))));
        } else {
            List<Scored> byCe = new ArrayList<>();
            for (Scored c : cand) byCe.add(new Scored(c.id, cache.get(key(query, c.id))));
            byCe.sort((a, b) -> Float.compare(b.score, a.score));
            Map<Integer, Double> f = new HashMap<>();
            for (int r = 0; r < cand.size(); r++) f.merge(cand.get(r).id, blend / (rrfK + r + 1), Double::sum);
            for (int r = 0; r < byCe.size(); r++) f.merge(byCe.get(r).id, 1.0 / (rrfK + r + 1), Double::sum);
            for (Map.Entry<Integer, Double> e : f.entrySet()) out.add(new Scored(e.getKey(), e.getValue().floatValue()));
        }
        out.sort((a, b) -> b.score != a.score ? Float.compare(b.score, a.score) : Integer.compare(a.id, b.id));
        for (int r = cand.size(); r < all.size(); r++) out.add(new Scored(all.get(r).id, -1e6f - r));   // phần đuôi giữ thứ tự của hệ thống nền
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) { }
    }
}
