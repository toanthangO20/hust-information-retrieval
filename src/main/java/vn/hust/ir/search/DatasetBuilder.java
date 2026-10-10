package vn.hust.ir.search;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Dựng bộ dữ liệu phiên bản 1 từ danh sách URL đã crawl (output/urls_valid.txt) và văn bản đã trích
 * được lưu trong kho Lucene của crawler (data/index). Không tải lại trang nào.
 *
 * Đầu ra: documents.jsonl (một tài liệu mỗi dòng, sắp theo URL) và manifest.json (mô tả, thống kê, SHA-256).
 */
public final class DatasetBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private DatasetBuilder() {}

    public static void build(Path urlList, Path crawlIndex, Path outDir, String version) throws Exception {
        Set<String> wanted = new HashSet<>();
        for (String line : Files.readAllLines(urlList, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (!line.isEmpty()) wanted.add(line);
        }
        int listed = wanted.size();
        System.out.println("URL trong danh sách: " + listed);

        Map<String, Map<String, Object>> byUrl = new TreeMap<>();
        int noText = 0;
        try (FSDirectory dir = FSDirectory.open(crawlIndex); DirectoryReader reader = DirectoryReader.open(dir)) {
            Bits live = MultiBits.getLiveDocs(reader);
            StoredFields sf = reader.storedFields();
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (live != null && !live.get(i)) continue;
                Document d = sf.document(i);
                String url = d.get("url");
                if (!"1".equals(d.get("state")) || !wanted.contains(url)) continue;
                String text = Text.clean(Text.nfc(d.get("content")));
                if (text.isEmpty()) { noText++; continue; }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("url", url);
                r.put("host", d.get("host"));
                r.put("title", Text.nfc(Text.fallbackTitle(d.get("title"), text, url)));
                r.put("doc_type", Text.docType(d.get("ctype"), url));
                r.put("content_type", d.get("ctype"));
                r.put("text", text);
                r.put("text_len", text.length());
                r.put("word_count", Text.wordCount(text));
                String nd = d.get("neardup");
                r.put("near_dup", nd == null || nd.isEmpty() ? null : nd);
                r.put("first_seen", iso(d.get("first")));
                r.put("last_crawled", iso(d.get("crawled")));
                byUrl.put(url, r);
            }
        }
        int missing = listed - byUrl.size();
        Map<String, Integer> quality = fixQuality(byUrl);
        System.out.println("Không lấy được văn bản: " + missing + " (kho có bản ghi nhưng văn bản rỗng: " + noText + ")");

        Files.createDirectories(outDir);
        Path docs = outDir.resolve("documents.jsonl");
        Map<String, Integer> perHost = new TreeMap<>(), perType = new TreeMap<>();
        long chars = 0, words = 0;
        int id = 0;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (BufferedWriter w = Files.newBufferedWriter(docs, StandardCharsets.UTF_8)) {
            for (Map<String, Object> r : byUrl.values()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", ++id);
                out.putAll(r);
                String line = JSON.writeValueAsString(out) + "\n";
                w.write(line);
                sha.update(line.getBytes(StandardCharsets.UTF_8));
                perHost.merge((String) r.get("host"), 1, Integer::sum);
                perType.merge((String) r.get("doc_type"), 1, Integer::sum);
                chars += (Integer) r.get("text_len");
                words += (Integer) r.get("word_count");
            }
        }

        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format("%02x", b));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "hust-ir-dataset");
        m.put("version", version);
        m.put("created", Instant.now().toString());
        m.put("description", "Văn bản các trang hợp lệ (HTTP 200, có nội dung, không trùng) của hust.edu.vn và subdomain, trích bằng jsoup + Tika.");
        m.put("source_url_list", urlList.getFileName().toString());
        m.put("source_urls", listed);
        m.put("documents", byUrl.size());
        m.put("skipped_without_text", missing);
        m.put("hosts", perHost.size());
        m.put("total_chars", chars);
        m.put("total_words", words);
        m.put("avg_words_per_doc", byUrl.isEmpty() ? 0 : words / byUrl.size());
        m.put("by_quality", quality);
        m.put("by_doc_type", perType);
        m.put("by_host", sortByValueDesc(perHost));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", "số thứ tự, theo thứ tự URL tăng dần");
        fields.put("url", "địa chỉ chuẩn hóa");
        fields.put("host", "tên miền");
        fields.put("title", "tiêu đề trang (nếu thiếu: dòng đầu văn bản hoặc tên tệp)");
        fields.put("doc_type", "html | pdf | word | excel | powerpoint | khac");
        fields.put("text", "văn bản chính đã loại menu/footer, Unicode NFC, tối đa 100.000 ký tự");
        fields.put("quality", "ok | repaired (đã sửa chữ vỡ UTF-8) | mojibake (chữ vỡ chưa sửa được) | legacy-font (font TCVN3/VNI cũ, văn bản vô nghĩa); chỉ ok và repaired được đưa vào chỉ mục");
        fields.put("near_dup", "URL có nội dung gần giống (SimHash) hoặc null");
        fields.put("first_seen, last_crawled", "thời điểm thấy lần đầu / tải gần nhất (ISO-8601)");
        m.put("fields", fields);
        m.put("sha256_documents_jsonl", hex.toString());
        PRETTY.writeValue(outDir.resolve("manifest.json").toFile(), m);

        System.out.printf("Đã ghi %d tài liệu, %d host, %,d từ -> %s%n", byUrl.size(), perHost.size(), words, docs);
    }

    /** Gắn nhãn chất lượng; sửa chữ vỡ UTF-8 theo token khi có thể. Trả về số tài liệu theo từng nhãn. */
    private static Map<String, Integer> fixQuality(Map<String, Map<String, Object>> docs) {
        java.util.regex.Pattern word = java.util.regex.Pattern.compile("\\p{L}+");
        Map<String, Integer> vocab = new java.util.HashMap<>();
        Set<Map<String, Object>> broken = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Map<String, Object> r : docs.values()) {
            String t = (String) r.get("text");
            boolean suspicious = false;
            for (String tok : t.split("\\s+"))
                if (Mojibake.suspiciousToken(tok)) { suspicious = true; break; }
            if (suspicious) {
                broken.add(r);
                continue;
            }
            java.util.regex.Matcher m = word.matcher(t.toLowerCase());
            while (m.find()) vocab.merge(m.group(), 1, Integer::sum);
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> r : docs.values()) {
            String q = "ok";
            if (broken.contains(r)) {
                Mojibake.Result fx = Mojibake.repair((String) r.get("text"), vocab);
                r.put("text", fx.text);
                Mojibake.Result ft = Mojibake.repair((String) r.get("title"), vocab);
                if (ft.unfixed == 0) r.put("title", ft.text);
                if (fx.unfixed > 0.03 * fx.tokens) q = "mojibake";      // phần lớn văn bản vẫn vỡ
                else if (fx.suspicious > fx.unfixed) q = "repaired";
            }
            r.put("text", Text.normalizeSpace((String) r.get("text")));
            String title = Text.normalizeSpace((String) r.get("title"));
            if (title.matches(".*%[0-9A-Fa-f]{2}.*")) {      // tiêu đề là tên tệp còn mã hóa phần trăm
                try {
                    title = java.net.URLDecoder.decode(title.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) { }
            }
            if (Text.legacyFont(title)) title = Text.fallbackTitle("", (String) r.get("text"), (String) r.get("url"));   // tiêu đề dùng font cũ
            r.put("title", title);
            if (q.equals("ok") && Text.legacyFont((String) r.get("text"))) q = "legacy-font";
            r.put("quality", q);
            r.put("text_len", ((String) r.get("text")).length());
            r.put("word_count", Text.wordCount((String) r.get("text")));
            counts.merge(q, 1, Integer::sum);
        }
        System.out.println("Chất lượng văn bản: " + counts);
        return counts;
    }

    private static Map<String, Integer> sortByValueDesc(Map<String, Integer> in) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(in.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static String iso(String epochMs) {
        try {
            long v = Long.parseLong(epochMs);
            return v <= 0 ? null : Instant.ofEpochMilli(v).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
