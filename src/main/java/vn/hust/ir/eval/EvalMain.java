package vn.hust.ir.eval;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.lucene.document.Document;

import vn.hust.ir.eval.EvalIo.Topic;
import vn.hust.ir.search.SearchEngine;

/**
 * Hạ tầng đánh giá: embed | run | pool | qrels | eval. Xem scripts\eval.bat và eval\README.md.
 *
 * Đặc tả hệ thống (--systems, ngăn cách bằng ';'): current | vsm | bm25[:k1=,b=] | prf[:k1=,b=,docs=,terms=,lambda=]
 *   | dense | hybrid[:k1=,b=,w=,rrf=]
 */
public final class EvalMain {
    private static Path INDEX = Paths.get("index", "ver1");
    private static final Path MODEL = Paths.get("models", "multilingual-e5-small");
    private static final Path DENSE = Paths.get("index", "ver1", "dense_e5small_int8_256.bin");

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        if (args.length == 0) {
            System.out.println("Sử dụng: eval <embed|run|pool|qrels|eval> [--tuỳ-chọn giá-trị]");
            return;
        }
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) o.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        if (o.containsKey("index")) INDEX = Paths.get(o.get("index"));   // chỉ mục khác (vd. index/ver1x có main_t, anchor_t)
        switch (args[0]) {
            case "embed": embed(o); break;
            case "run": run(o); break;
            case "pool": pool(o); break;
            case "qrels": qrels(o); break;
            case "eval": evaluate(o); break;
            default: System.out.println("Lệnh không hợp lệ: " + args[0]);
        }
    }

    // ------------------------------------------------------------------ embed

    private static void embed(Map<String, String> o) throws Exception {
        try (LuceneCorpus corpus = new LuceneCorpus(INDEX);
             E5Encoder enc = new E5Encoder(MODEL, Integer.parseInt(o.getOrDefault("threads", "8")))) {
            int maxTokens = Integer.parseInt(o.getOrDefault("max-tokens", "256"));
            DenseRetriever.build(corpus, enc, maxTokens, DENSE);
        }
    }

    // ------------------------------------------------------------------ run

    static String fileName(String spec) {
        return spec.replace(':', '_').replace(',', '_').replace('=', '-');
    }

    private static Map<String, String> params(String spec) {
        Map<String, String> p = new HashMap<>();
        int c = spec.indexOf(':');
        if (c < 0) return p;
        for (String kv : spec.substring(c + 1).split(",")) {
            String[] f = kv.split("=");
            p.put(f[0].trim(), f[1].trim());
        }
        return p;
    }

    private static float f(Map<String, String> p, String k, float d) {
        return p.containsKey(k) ? Float.parseFloat(p.get(k)) : d;
    }

    private static int i(Map<String, String> p, String k, int d) {
        return p.containsKey(k) ? Integer.parseInt(p.get(k)) : d;
    }

    private static final class Factory implements AutoCloseable {
        final LuceneCorpus corpus;
        E5Encoder enc;
        DenseRetriever dense;
        VsmRetriever vsm;
        SearchEngine engine;

        Factory() throws IOException {
            corpus = new LuceneCorpus(INDEX);
        }

        DenseRetriever dense() throws Exception {
            if (dense == null) {
                enc = new E5Encoder(MODEL, 8);
                dense = new DenseRetriever(corpus, enc, DENSE);
            }
            return dense;
        }

        Retriever create(String spec) throws Exception {
            String name = spec.contains(":") ? spec.substring(0, spec.indexOf(':')) : spec;
            Map<String, String> p = params(spec);
            switch (name) {
                case "current":
                    if (engine == null) engine = new SearchEngine(INDEX);
                    return new CurrentEngineRetriever(engine);
                case "vsm":
                    if (vsm == null) vsm = new VsmRetriever(corpus);
                    return vsm;
                case "bm25":
                    return new Bm25Retriever(corpus, f(p, "k1", 1.2f), f(p, "b", 0.75f));
                case "bm25x": {      // BM25 trên chỉ mục ver1x: ctx=full|main [+anchor], aw=trọng số văn bản liên kết, pr=tiên nghiệm PageRank
                    String ctx = p.getOrDefault("ctx", "full");
                    List<String> fl = new ArrayList<>(List.of("title_t", ctx.contains("main") ? "main_t" : "content", "url_t"));
                    List<Float> bl = new ArrayList<>(List.of(3.0f, 1.0f, 0.6f));
                    if (ctx.contains("anchorc")) {           // văn bản liên kết chỉ từ các liên kết nằm trong nội dung chính
                        fl.add("anchor_c");
                        bl.add(f(p, "aw", 1.5f));
                    } else if (ctx.contains("anchor")) {     // mọi văn bản liên kết (đã bỏ neo vô nghĩa / neo chung)
                        fl.add("anchor_t");
                        bl.add(f(p, "aw", 1.5f));
                    }
                    float[] bs = new float[bl.size()];
                    for (int q = 0; q < bs.length; q++) bs[q] = bl.get(q);
                    return new FieldBm25Retriever(corpus, f(p, "k1", 1.2f), f(p, "b", 0.75f), fl.toArray(new String[0]), bs, f(p, "pr", 0f));
                }
                case "prf":
                    return new PrfRetriever(corpus, f(p, "k1", 1.2f), f(p, "b", 0.75f), i(p, "docs", 10), i(p, "terms", 20),
                            f(p, "lambda", 0.5f));
                case "dense":
                    return dense();
                case "hybrid":
                    return new HybridRetriever(new Bm25Retriever(corpus, f(p, "k1", 1.2f), f(p, "b", 0.75f)), dense(), 1.0,
                            f(p, "w", 1.0f), i(p, "rrf", 60), 100);
                case "ce": {         // M6: cross-encoder xếp lại top-depth của hybrid (w=, depth=, len=, blend=)
                    Retriever nb = new HybridRetriever(new Bm25Retriever(corpus, 1.2f, 0.75f), dense(), 1.0, f(p, "w", 1.0f), 60, 100);
                    return new CrossEncoderRetriever(nb, corpus, Paths.get("models", "bge-reranker-v2-m3", "model_quantized.onnx"),
                            MODEL.resolve("tokenizer.json"), Paths.get("eval", "work", "ce_cache.tsv"), i(p, "depth", 30),
                            i(p, "len", 320), f(p, "blend", 0f), 60, 8);
                }
                case "drocchio": {   // M5: lai -> Rocchio dày -> lai lại
                    Retriever lex = "prf".equals(p.get("lex"))
                            ? new PrfRetriever(corpus, 1.2f, 0.75f, 5, 20, 0.5f)
                            : new Bm25Retriever(corpus, f(p, "k1", 1.2f), f(p, "b", 0.75f));
                    return new DenseRocchioRetriever(lex, dense(), i(p, "m", 5), f(p, "alpha", 0.5f), 1.0, f(p, "w", 1.0f),
                            f(p, "wo", 0f), i(p, "rrf", 60), 100);
                }
                default:
                    throw new IllegalArgumentException("Hệ thống không hợp lệ: " + spec);
            }
        }

        @Override
        public void close() throws Exception {
            corpus.close();
            if (enc != null) enc.close();
            if (engine != null) engine.close();
        }
    }

    private static void run(Map<String, String> o) throws Exception {
        List<Topic> topics = EvalIo.readTopics(Paths.get(o.getOrDefault("topics", "eval/topics.tsv")), o.getOrDefault("split", "all"));
        int k = Integer.parseInt(o.getOrDefault("k", "100"));
        Path outDir = Paths.get(o.getOrDefault("out", "eval/runs"));
        try (Factory fac = new Factory()) {
            for (String spec : o.get("systems").split(";")) {
                spec = spec.trim();
                if (spec.isEmpty()) continue;
                Retriever r = fac.create(spec);
                Map<String, List<Scored>> run = new LinkedHashMap<>();
                long t0 = System.nanoTime();
                for (Topic t : topics) run.put(t.qid, r.search(t.text, k));
                double ms = (System.nanoTime() - t0) / 1e6 / Math.max(1, topics.size());
                Path file = outDir.resolve(fileName(spec) + ".run");
                EvalIo.writeRun(file, spec, run);
                System.out.printf(Locale.ROOT, "%-44s %3d truy vấn, %.1f ms/truy vấn -> %s%n", spec, topics.size(), ms, file);
            }
        }
    }

    /** --runs nhận các tệp .run ngăn cách bằng ';' hoặc một thư mục (lấy mọi *.run, sắp theo tên). */
    private static List<Path> expandRuns(String spec) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String r : spec.split(";")) {
            if (r.isBlank()) continue;
            Path p = Paths.get(r.trim());
            if (Files.isDirectory(p)) {
                try (java.util.stream.Stream<Path> st = Files.list(p)) {
                    st.filter(x -> x.toString().endsWith(".run")).sorted().forEach(out::add);
                }
            } else out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ pool

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase();
    }

    /** Đoạn 300 ký tự chứa nhiều âm tiết truy vấn nhất (để người gán nhãn thấy ngữ cảnh khớp). */
    private static String snippet(String text, Set<String> qtoks) {
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= 300) return flat;
        String ft = fold(flat);
        if (ft.length() != flat.length()) return flat.substring(0, 300) + "…";
        int best = 0, bestScore = -1;
        for (int s = 0; s + 300 <= ft.length() + 50; s += 60) {
            String w = ft.substring(s, Math.min(ft.length(), s + 300));
            int sc = 0;
            for (String t : qtoks) if (t.length() > 1 && w.contains(t)) sc++;
            if (sc > bestScore) { bestScore = sc; best = s; }
        }
        String w = flat.substring(best, Math.min(flat.length(), best + 300));
        return (best > 0 ? "…" : "") + w + (best + 300 < flat.length() ? "…" : "");
    }

    private static void pool(Map<String, String> o) throws Exception {
        List<Topic> topics = EvalIo.readTopics(Paths.get(o.getOrDefault("topics", "eval/topics.tsv")), o.getOrDefault("split", "all"));
        int depth = Integer.parseInt(o.getOrDefault("depth", "15"));
        List<Path> runs = expandRuns(o.get("runs"));
        Map<String, Map<Integer, Set<String>>> pool = new LinkedHashMap<>();
        for (Path rp : runs) {
            String sys = rp.getFileName().toString().replaceFirst("\\.run$", "");
            for (Map.Entry<String, List<Integer>> e : EvalIo.readRun(rp).entrySet()) {
                Map<Integer, Set<String>> m = pool.computeIfAbsent(e.getKey(), x -> new TreeMap<>());
                for (int r = 0; r < Math.min(depth, e.getValue().size()); r++)
                    m.computeIfAbsent(e.getValue().get(r), x -> new TreeSet<>()).add(sys);
            }
        }
        String base = o.get("out");
        Files.createDirectories(Paths.get(base).toAbsolutePath().getParent());
        try (LuceneCorpus corpus = new LuceneCorpus(INDEX);
             PrintWriter tsv = new PrintWriter(Files.newBufferedWriter(Paths.get(base + ".tsv"), StandardCharsets.UTF_8));
             PrintWriter txt = new PrintWriter(Files.newBufferedWriter(Paths.get(base + ".txt"), StandardCharsets.UTF_8))) {
            Map<Integer, Integer> luceneOf = new HashMap<>();
            for (int d = 0; d < corpus.n; d++) luceneOf.put(corpus.idOf[d], d);
            int total = 0;
            for (Topic t : topics) {
                Map<Integer, Set<String>> m = pool.getOrDefault(t.qid, new TreeMap<>());
                Set<String> qtoks = new HashSet<>();
                for (String x : corpus.tokens(t.text)) qtoks.add(x);
                txt.printf("### %s | %s | %s | %d tài liệu%n", t.qid, t.split, t.type, m.size());
                txt.printf("TRUY VẤN: %s%nNHU CẦU: %s%n", t.text, t.narrative);
                for (Map.Entry<Integer, Set<String>> e : m.entrySet()) {
                    Document d = corpus.reader.storedFields().document(luceneOf.get(e.getKey()),
                            Set.of("url", "host", "title", "content"));
                    tsv.println(t.qid + "\t" + e.getKey() + "\t" + String.join(",", e.getValue()));
                    String url = d.get("url").replaceFirst("^https?://", "");
                    txt.printf("[%d] %s%n    %s%n    %s%n", e.getKey(), d.get("title"), url,
                            snippet(d.get("content"), qtoks));
                    total++;
                }
                txt.println();
            }
            System.out.printf("Pool: %d truy vấn, %d cặp (truy vấn, tài liệu) -> %s.{tsv,txt}%n", topics.size(), total, base);
        }
    }

    // ------------------------------------------------------------------ qrels

    /**
     * Gộp nhãn: tệp judgments có các dòng "qid<TAB>mức<TAB>id,id,id" (mức 1 hoặc 2); mọi tài liệu trong pool
     * không được liệt kê nhận mức 0.
     */
    private static void qrels(Map<String, String> o) throws Exception {
        Map<String, Map<Integer, Integer>> q = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Paths.get(o.get("pool")), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t");
            q.computeIfAbsent(f[0], k -> new TreeMap<>()).put(Integer.parseInt(f[1]), 0);
        }
        int bad = 0, extra = 0;
        for (String file : o.get("judgments").split(";")) {
            for (String line : Files.readAllLines(Paths.get(file.trim()), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                int rel = Integer.parseInt(f[1].trim());
                Map<Integer, Integer> m = q.get(f[0].trim());
                if (m == null) { System.out.println("Truy vấn không có trong pool: " + f[0]); bad++; continue; }
                for (String id : f[2].split("[,\\s]+")) {
                    if (id.isBlank()) continue;
                    int d = Integer.parseInt(id);
                    if (!m.containsKey(d)) extra++;     // tài liệu đã được xem ở ngữ cảnh truy vấn khác, nhưng không thuộc pool của truy vấn này
                    m.put(d, rel);
                }
            }
        }
        EvalIo.writeQrels(Paths.get(o.get("out")), q);
        int rel1 = 0, rel2 = 0, all = 0;
        for (Map<Integer, Integer> m : q.values())
            for (int v : m.values()) { all++; if (v == 1) rel1++; if (v == 2) rel2++; }
        System.out.printf("qrels: %d truy vấn, %d nhãn (mức 2: %d, mức 1: %d, mức 0: %d), %d lỗi -> %s%n",
                q.size(), all, rel2, rel1, all - rel1 - rel2, bad, o.get("out"));
        if (extra > 0) System.out.println("  (trong đó " + extra + " nhãn thuộc tài liệu ngoài pool của truy vấn, vẫn được giữ lại)");
    }

    // ------------------------------------------------------------------ eval

    private static final String[] METRICS = {"nDCG@10", "MAP", "MRR@10", "P@10", "R@100", "Judged@10"};

    private static double[] score(List<Integer> ranked, Map<Integer, Integer> rels) {
        return new double[] {Metrics.ndcg(ranked, rels, 10), Metrics.averagePrecision(ranked, rels),
                Metrics.reciprocalRank(ranked, rels, 10), Metrics.precisionAt(ranked, rels, 10),
                Metrics.recallAt(ranked, rels, 100), Metrics.judgedAt(ranked, rels, 10)};
    }

    /** Trung bình từng độ đo của một hệ thống trên các truy vấn của `qids`; mảng [truy vấn][độ đo]. */
    private static double[][] perQuery(Map<String, List<Integer>> run, Map<String, Map<Integer, Integer>> qrels, List<String> qids) {
        double[][] m = new double[qids.size()][];
        for (int i = 0; i < qids.size(); i++) {
            List<Integer> ranked = run.getOrDefault(qids.get(i), new ArrayList<>());
            Map<Integer, Integer> rels = qrels.getOrDefault(qids.get(i), new HashMap<>());
            if (CONDENSED) {                    // danh sách rút gọn: bỏ tài liệu chưa có nhãn (giảm thiên lệch pool với hệ thống mới)
                List<Integer> c = new ArrayList<>();
                for (int d : ranked) if (rels.containsKey(d)) c.add(d);
                ranked = c;
            }
            m[i] = score(ranked, rels);
        }
        return m;
    }

    private static double[] column(double[][] m, int c) {
        double[] x = new double[m.length];
        for (int i = 0; i < m.length; i++) x[i] = m[i][c];
        return x;
    }

    private static boolean CONDENSED = false;

    private static void evaluate(Map<String, String> o) throws Exception {
        CONDENSED = "true".equals(o.get("condensed"));
        List<Topic> topics = EvalIo.readTopics(Paths.get(o.getOrDefault("topics", "eval/topics.tsv")), o.getOrDefault("split", "all"));
        Map<String, Map<Integer, Integer>> qrels = EvalIo.readQrels(Paths.get(o.get("qrels")));
        // chỉ giữ truy vấn đã có nhãn
        List<Topic> use = new ArrayList<>();
        for (Topic t : topics) if (qrels.containsKey(t.qid) && Metrics.numRelevant(qrels.get(t.qid)) > 0) use.add(t);
        List<String> qids = new ArrayList<>();
        for (Topic t : use) qids.add(t.qid);
        int skipped = topics.size() - use.size();

        Map<String, Map<String, List<Integer>>> runs = new LinkedHashMap<>();
        for (Path p : expandRuns(o.get("runs")))
            runs.put(p.getFileName().toString().replaceFirst("\\.run$", ""), EvalIo.readRun(p));
        String baseline = o.getOrDefault("baseline", runs.keySet().iterator().next());
        int poolDepth = Integer.parseInt(o.getOrDefault("pool-depth", "15"));

        Map<String, double[][]> pq = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<Integer>>> e : runs.entrySet())
            pq.put(e.getKey(), perQuery(e.getValue(), qrels, qids));

        StringBuilder sb = new StringBuilder();
        sb.append("# Kết quả đánh giá\n\n");
        sb.append(String.format(Locale.ROOT, "- Tập truy vấn: `%s`, %d truy vấn có nhãn (bỏ qua %d truy vấn không có tài liệu phù hợp).%n",
                o.getOrDefault("split", "all"), qids.size(), skipped));
        sb.append("- Phù hợp nhị phân: mức >= 1. nDCG dùng độ lợi = mức phù hợp, chiết khấu theo slide Ch05 (r1 + Σ ri/log2 i), IDCG tính từ toàn bộ qrels.\n");
        sb.append(String.format(Locale.ROOT, "- Hệ thống tham chiếu để kiểm định: `%s`. Kiểm định hoán vị dấu ghép cặp theo truy vấn (20.000 vòng), hiệu chỉnh Holm; "
                + "dấu `*` = khác biệt có ý nghĩa ở mức 0,05 sau hiệu chỉnh.%n%n", baseline));

        // bảng tổng hợp
        sb.append("## Bảng tổng hợp\n\n| Hệ thống |");
        for (String m : METRICS) sb.append(' ').append(m).append(" |");
        sb.append("\n|---|").append("---:|".repeat(METRICS.length)).append('\n');
        List<String> names = new ArrayList<>(runs.keySet());
        double[] pAdj = new double[names.size()];
        double[] praw = new double[names.size()];
        double[] baseNdcg = column(pq.get(baseline), 0);
        for (int s = 0; s < names.size(); s++)
            praw[s] = names.get(s).equals(baseline) ? 1.0
                    : Significance.randomizationP(column(pq.get(names.get(s)), 0), baseNdcg, 20000, 42 + s);
        int others = names.size() - 1;
        double[] holmIn = new double[others];
        int[] map = new int[others];
        for (int s = 0, j = 0; s < names.size(); s++) if (!names.get(s).equals(baseline)) { holmIn[j] = praw[s]; map[j++] = s; }
        double[] holmOut = Significance.holm(holmIn);
        for (int j = 0; j < others; j++) pAdj[map[j]] = holmOut[j];
        for (int s = 0; s < names.size(); s++) {
            String n = names.get(s);
            double[][] m = pq.get(n);
            sb.append("| `").append(n).append("` |");
            for (int c = 0; c < METRICS.length; c++) {
                double v = Significance.mean(column(m, c));
                boolean sig = c == 0 && !n.equals(baseline) && pAdj[s] < 0.05;
                sb.append(String.format(Locale.ROOT, " %.4f%s |", v, sig ? "*" : ""));
            }
            sb.append('\n');
        }

        // so sánh ghép cặp với baseline
        sb.append("\n## So sánh với `").append(baseline).append("` (nDCG@10)\n\n| Hệ thống | Δ trung bình | KTC 95% (bootstrap) | p | p (Holm) | tốt hơn / kém hơn / bằng (số truy vấn) |\n|---|---:|---|---:|---:|---|\n");
        for (int s = 0; s < names.size(); s++) {
            String n = names.get(s);
            if (n.equals(baseline)) continue;
            double[] a = column(pq.get(n), 0);
            double[] ci = Significance.bootstrapCi(a, baseNdcg, 10000, 7);
            int win = 0, lose = 0, tie = 0;
            for (int i = 0; i < a.length; i++) {
                if (Math.abs(a[i] - baseNdcg[i]) < 1e-9) tie++; else if (a[i] > baseNdcg[i]) win++; else lose++;
            }
            sb.append(String.format(Locale.ROOT, "| `%s` | %+.4f | [%+.4f, %+.4f] | %.4f | %.4f | %d / %d / %d |%n", n,
                    Significance.mean(a) - Significance.mean(baseNdcg), ci[0], ci[1], praw[s], pAdj[s], win, lose, tie));
        }

        // theo loại truy vấn
        Map<String, List<Integer>> byType = new TreeMap<>();
        for (int i = 0; i < use.size(); i++) byType.computeIfAbsent(use.get(i).type, k -> new ArrayList<>()).add(i);
        sb.append("\n## nDCG@10 theo loại truy vấn\n\n| Hệ thống |");
        for (Map.Entry<String, List<Integer>> t : byType.entrySet()) sb.append(' ').append(t.getKey()).append(" (").append(t.getValue().size()).append(") |");
        sb.append("\n|---|").append("---:|".repeat(byType.size())).append('\n');
        for (String n : names) {
            sb.append("| `").append(n).append("` |");
            for (List<Integer> idx : byType.values()) {
                double s = 0;
                for (int i : idx) s += pq.get(n)[i][0];
                sb.append(String.format(Locale.ROOT, " %.4f |", s / idx.size()));
            }
            sb.append('\n');
        }

        // ổn định khi bỏ từng hệ thống khỏi pool (leave-one-system-out)
        sb.append("\n## Độ ổn định của pool (bỏ từng hệ thống khỏi pool)\n\n");
        sb.append(String.format(Locale.ROOT, "Pool = hợp top-%d của mọi hệ thống. Với mỗi hệ thống S, các tài liệu *chỉ* do S đóng góp vào pool bị coi như chưa gán nhãn; "
                + "so sánh thứ tự các hệ thống theo nDCG@10 (tau của Kendall so với thứ tự gốc).%n%n", poolDepth));
        sb.append("| Bỏ hệ thống | nDCG@10 của chính nó (gốc → sau khi bỏ) | tau |\n|---|---|---:|\n");
        double[] orig = new double[names.size()];
        for (int s = 0; s < names.size(); s++) orig[s] = Significance.mean(column(pq.get(names.get(s)), 0));
        for (int drop = 0; drop < names.size(); drop++) {
            Map<String, Map<Integer, Integer>> sub = new LinkedHashMap<>();
            for (String qid : qids) {
                Set<Integer> keep = new HashSet<>();
                for (int s = 0; s < names.size(); s++) {
                    if (s == drop) continue;
                    List<Integer> r = runs.get(names.get(s)).getOrDefault(qid, new ArrayList<>());
                    for (int j = 0; j < Math.min(poolDepth, r.size()); j++) keep.add(r.get(j));
                }
                Map<Integer, Integer> m = new HashMap<>();
                for (Map.Entry<Integer, Integer> e : qrels.get(qid).entrySet()) if (keep.contains(e.getKey())) m.put(e.getKey(), e.getValue());
                sub.put(qid, m);
            }
            double[] sc = new double[names.size()];
            for (int s = 0; s < names.size(); s++) sc[s] = Significance.mean(column(perQuery(runs.get(names.get(s)), sub, qids), 0));
            sb.append(String.format(Locale.ROOT, "| `%s` | %.4f → %.4f | %.3f |%n", names.get(drop), orig[drop], sc[drop], kendall(orig, sc)));
        }

        String out = o.getOrDefault("out", "eval/report/results.md");
        Files.createDirectories(Paths.get(out).toAbsolutePath().getParent());
        Files.writeString(Paths.get(out), sb.toString(), StandardCharsets.UTF_8);
        // chi tiết theo truy vấn
        Path csv = Paths.get(out.replaceFirst("\\.md$", "") + "_per_query.csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8))) {
            w.println("system,qid,type," + String.join(",", METRICS));
            for (String n : names)
                for (int i = 0; i < qids.size(); i++) {
                    StringBuilder line = new StringBuilder(n + "," + qids.get(i) + "," + use.get(i).type);
                    for (double v : pq.get(n)[i]) line.append(String.format(Locale.ROOT, ",%.4f", v));
                    w.println(line);
                }
        }
        System.out.println(sb);
        System.out.println("-> " + out + " , " + csv);
    }

    private static double kendall(double[] a, double[] b) {
        int c = 0, d = 0;
        for (int i = 0; i < a.length; i++)
            for (int j = i + 1; j < a.length; j++) {
                double x = Math.signum(a[i] - a[j]) * Math.signum(b[i] - b[j]);
                if (x > 0) c++; else if (x < 0) d++;
            }
        return c + d == 0 ? 1 : (double) (c - d) / (c + d);
    }
}
