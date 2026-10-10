package vn.hust.ir.eval;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Đọc/ghi topics (TSV), qrels và run theo định dạng TREC. */
public final class EvalIo {
    private EvalIo() {}

    public static final class Topic {
        public final String qid, split, type, text, narrative;

        Topic(String qid, String split, String type, String text, String narrative) {
            this.qid = qid;
            this.split = split;
            this.type = type;
            this.text = text;
            this.narrative = narrative;
        }
    }

    /** topics.tsv: qid, split (dev|test), type, truy vấn, mô tả nhu cầu. Dòng bắt đầu bằng # bị bỏ qua. */
    public static List<Topic> readTopics(Path file, String split) throws IOException {
        List<Topic> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 5) throw new IOException("Dòng topic thiếu cột: " + line);
            if (split.equals("all") || split.equals(f[1])) out.add(new Topic(f[0], f[1], f[2], f[3], f[4]));
        }
        return out;
    }

    /** qrels: "qid 0 docid rel" (TSV hoặc cách trắng). */
    public static Map<String, Map<Integer, Integer>> readQrels(Path file) throws IOException {
        Map<String, Map<Integer, Integer>> q = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.trim().split("\\s+");
            q.computeIfAbsent(f[0], k -> new TreeMap<>()).put(Integer.parseInt(f[2]), Integer.parseInt(f[3]));
        }
        return q;
    }

    public static void writeQrels(Path file, Map<String, Map<Integer, Integer>> q) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            for (Map.Entry<String, Map<Integer, Integer>> e : q.entrySet())
                for (Map.Entry<Integer, Integer> d : e.getValue().entrySet())
                    w.println(e.getKey() + "\t0\t" + d.getKey() + "\t" + d.getValue());
        }
    }

    public static void writeRun(Path file, String tag, Map<String, List<Scored>> run) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            for (Map.Entry<String, List<Scored>> e : run.entrySet()) {
                int r = 0;
                for (Scored s : e.getValue())
                    w.println(String.format(Locale.ROOT, "%s Q0 %d %d %.6f %s", e.getKey(), s.id, ++r, s.score, tag));
            }
        }
    }

    /** Đọc run TREC: qid -> danh sách mã tài liệu theo thứ hạng. */
    public static Map<String, List<Integer>> readRun(Path file) throws IOException {
        Map<String, List<Integer>> run = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] f = line.trim().split("\\s+");
            run.computeIfAbsent(f[0], k -> new ArrayList<>()).add(Integer.parseInt(f[2]));
        }
        return run;
    }
}
