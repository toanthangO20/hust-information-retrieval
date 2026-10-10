package vn.hust.ir.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Dựng chỉ mục tìm kiếm (Lucene, BM25) từ documents.jsonl của bộ dữ liệu. Luôn dựng lại từ đầu. */
public final class SearchIndexer {
    private SearchIndexer() {}

    public static void build(Path datasetDir, Path indexDir) throws IOException {
        if (Files.exists(indexDir)) {
            try (Stream<Path> s = Files.walk(indexDir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(indexDir);
        ObjectMapper json = new ObjectMapper();
        long t0 = System.currentTimeMillis();
        int n = 0, skipped = 0;
        IndexWriterConfig cfg = new IndexWriterConfig(ViAnalyzer.forIndex());
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        try (FSDirectory dir = FSDirectory.open(indexDir);
             IndexWriter w = new IndexWriter(dir, cfg);
             BufferedReader r = Files.newBufferedReader(datasetDir.resolve("documents.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode j = json.readTree(line);
                String url = j.get("url").asText();
                String host = j.get("host").asText();
                String type = j.get("doc_type").asText();
                String title = j.get("title").asText();
                String text = j.get("text").asText();
                String quality = j.get("quality").asText();
                if (!quality.equals("ok") && !quality.equals("repaired")) { skipped++; continue; }

                Document d = new Document();
                d.add(new StoredField("id", j.get("id").asInt()));
                d.add(new StringField("url", url, Field.Store.YES));
                d.add(new StringField("host", host, Field.Store.YES));
                d.add(new StringField("type", type, Field.Store.YES));
                d.add(new SortedDocValuesField("host_dv", new BytesRef(host)));
                d.add(new SortedDocValuesField("type_dv", new BytesRef(type)));
                d.add(new StoredField("title", title));
                d.add(new StoredField("words", j.get("word_count").asInt()));
                d.add(new TextField("title_t", title, Field.Store.NO));
                d.add(new TextField("url_t", url.replaceAll("^https?://", "").replaceAll("[^\\p{L}\\p{N}]+", " "), Field.Store.NO));
                d.add(new TextField("content", text, Field.Store.YES));
                d.add(new TextField("title_x", title, Field.Store.NO));
                d.add(new TextField("content_x", text, Field.Store.NO));
                w.addDocument(d);
                if (++n % 5000 == 0) System.out.println("  đã đánh chỉ mục " + n);
            }
            w.forceMerge(1);
        }
        System.out.printf("Đã dựng chỉ mục %d tài liệu (bỏ qua %d tài liệu chất lượng văn bản kém) trong %.1f s -> %s%n",
                n, skipped, (System.currentTimeMillis() - t0) / 1000.0, indexDir);
    }
}
