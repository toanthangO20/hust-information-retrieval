package vn.hust.ir.extract;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.hust.ir.search.ViAnalyzer;

/**
 * P7 — Chỉ mục thử nghiệm {@code index/ver1x}: cùng tài liệu và mã (id) như {@code index/ver1}, thêm
 * {@code main_t} (khối nội dung chính đã bóc), {@code anchor_t} (văn bản liên kết trỏ tới trang) và các giá trị
 * tiên nghiệm (PageRank) lưu kèm, để so sánh với hạ tầng eval hiện có.
 */
public final class ExtractIndexer {
    private ExtractIndexer() {}

    private static void appendAnchors(StringBuilder sb, Map<String, Integer> m, Map<String, java.util.Set<String>> dsts) {
        if (m == null) return;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            java.util.Set<String> ds = dsts.get(e.getKey().toLowerCase(java.util.Locale.ROOT));
            if (ds != null && ds.size() > 30) continue;           // neo chung (menu, "Giới thiệu chung"...)
            int c = Math.min(3, e.getValue());                   // giới hạn tần suất để tránh neo lặp chi phối
            for (int k = 0; k < c; k++) sb.append(e.getKey()).append(" . ");
        }
    }

    public static void build(Path datasetDir, Path extractDir, Path indexDir) throws IOException {
        ObjectMapper json = new ObjectMapper();
        // 1) đọc kết quả bóc tách theo URL
        Map<String, JsonNode> byUrl = new HashMap<>();
        List<double[]> prs = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(extractDir.resolve("pages.final.jsonl"), StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.isBlank()) continue;
                JsonNode p = json.readTree(l);
                if (!p.path("inDataset").asBoolean(false) || !"html".equals(p.path("kind").asText())) {
                    if (p.path("inDataset").asBoolean(false)) byUrl.put(p.get("url").asText(), p);
                    continue;
                }
                byUrl.put(p.get("url").asText(), p);
            }
        }
        // văn bản liên kết theo đích, tính lại từ đồ thị cuối: bỏ neo vô nghĩa và neo "chung" (trỏ tới > 30 đích khác nhau: menu...)
        Map<String, Map<String, Integer>> anchorsAll = new HashMap<>(), anchorsContent = new HashMap<>();
        Map<String, java.util.Set<String>> anchorDsts = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(extractDir.resolve("links.final.jsonl"), StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                JsonNode e = json.readTree(l);
                String kind = e.path("kind").asText();
                if (!kind.equals("a") && !kind.equals("img")) continue;
                String an = e.path("anchor").asText("").trim();
                if (GraphBuilder.isJunk(an)) continue;
                String dst = e.path("dst").asText();
                String key = an.toLowerCase(java.util.Locale.ROOT);
                anchorDsts.computeIfAbsent(key, x -> new java.util.HashSet<>()).add(dst);
                anchorsAll.computeIfAbsent(dst, x -> new HashMap<>()).merge(an, 1, Integer::sum);
                if (e.path("inContent").asBoolean(false)) anchorsContent.computeIfAbsent(dst, x -> new HashMap<>()).merge(an, 1, Integer::sum);
            }
        }
        // phân vị của log PageRank (nội dung) trong số các trang bộ dữ liệu
        List<Double> vals = new ArrayList<>();
        for (JsonNode p : byUrl.values()) vals.add(p.path("pagerankContent").asDouble(0));
        double[] sorted = vals.stream().mapToDouble(Double::doubleValue).sorted().toArray();

        if (Files.exists(indexDir)) {
            try (Stream<Path> s = Files.walk(indexDir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(indexDir);
        IndexWriterConfig cfg = new IndexWriterConfig(ViAnalyzer.forIndex());
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        int n = 0, withMain = 0, withAnchor = 0;
        try (FSDirectory dir = FSDirectory.open(indexDir);
             IndexWriter w = new IndexWriter(dir, cfg);
             BufferedReader r = Files.newBufferedReader(datasetDir.resolve("documents.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode j = json.readTree(line);
                String quality = j.get("quality").asText();
                if (!quality.equals("ok") && !quality.equals("repaired")) continue;
                String url = j.get("url").asText();
                String title = j.get("title").asText();
                String text = j.get("text").asText();
                JsonNode p = byUrl.get(url);

                String main = text;
                StringBuilder anchors = new StringBuilder(), anchorsC = new StringBuilder();
                float prz = 0f;
                int inl = 0;
                if (p != null) {
                    String mt = p.path("content").path("text").asText("");
                    int mw = p.path("content").path("words").asInt(0);
                    if (mw >= 20) {
                        main = mt;
                        withMain++;
                    }
                    String id = p.path("_id").asText();
                    appendAnchors(anchors, anchorsAll.get(id), anchorDsts);
                    appendAnchors(anchorsC, anchorsContent.get(id), anchorDsts);
                    if (anchors.length() > 0) withAnchor++;
                    double v = p.path("pagerankContent").asDouble(0);
                    int pos = Arrays.binarySearch(sorted, v);
                    if (pos < 0) pos = -pos - 1;
                    prz = sorted.length == 0 ? 0 : (float) pos / sorted.length;
                    inl = p.path("inlinksContent").asInt(0);
                }
                Document d = new Document();
                d.add(new StoredField("id", j.get("id").asInt()));
                d.add(new StringField("url", url, Field.Store.YES));
                d.add(new StoredField("title", title));
                d.add(new TextField("title_t", title, Field.Store.NO));
                d.add(new TextField("url_t", url.replaceAll("^https?://", "").replaceAll("[^\\p{L}\\p{N}]+", " "), Field.Store.NO));
                d.add(new TextField("content", text, Field.Store.NO));
                d.add(new TextField("main_t", main, Field.Store.NO));
                d.add(new TextField("anchor_t", anchors.toString(), Field.Store.NO));
                d.add(new TextField("anchor_c", anchorsC.toString(), Field.Store.NO));
                d.add(new StoredField("prz", prz));
                d.add(new StoredField("inl", inl));
                w.addDocument(d);
                n++;
            }
            w.forceMerge(1);
        }
        System.out.printf("Đã dựng %s: %d tài liệu (nội dung chính: %d, có văn bản liên kết: %d)%n", indexDir, n, withMain, withAnchor);
    }
}
