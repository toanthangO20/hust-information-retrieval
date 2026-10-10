package vn.hust.ir.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchEngineTest {
    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder();
    private static SearchEngine engine;

    private static Map<String, Object> doc(int id, String url, String host, String type, String title, String text, String quality) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("url", url);
        m.put("host", host);
        m.put("title", title);
        m.put("doc_type", type);
        m.put("text", text);
        m.put("word_count", text.split("\\s+").length);
        m.put("quality", quality);
        return m;
    }

    @BeforeClass
    public static void build() throws Exception {
        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(doc(1, "https://hust.edu.vn/tuyen-sinh", "hust.edu.vn", "html", "Thông tin tuyển sinh đại học chính quy",
                "Đại học Bách khoa Hà Nội tuyển sinh theo nhiều phương thức xét tuyển.", "ok"));
        docs.add(doc(2, "https://sdh.hust.edu.vn/hoc-bong", "sdh.hust.edu.vn", "pdf", "Quy định học bổng",
                "Học bổng khuyến khích học tập dành cho sinh viên có kết quả học tập tốt.", "ok"));
        docs.add(doc(3, "https://sdh.hust.edu.vn/tuyen-sinh-sau-dai-hoc", "sdh.hust.edu.vn", "html", "Tuyển sinh sau đại học",
                "Thông báo tuyển sinh thạc sĩ và tiến sĩ.", "ok"));
        docs.add(doc(4, "https://x.hust.edu.vn/rac", "x.hust.edu.vn", "pdf", "Bμi gi¶ng", "tuyển sinh tuyển sinh tuyển sinh", "legacy-font"));

        Path ds = tmp.newFolder("dataset").toPath();
        ObjectMapper json = new ObjectMapper();
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> d : docs) lines.add(json.writeValueAsString(d));
        Files.write(ds.resolve("documents.jsonl"), lines, StandardCharsets.UTF_8);
        Path idx = tmp.getRoot().toPath().resolve("index");
        SearchIndexer.build(ds, idx);
        engine = new SearchEngine(idx);
    }

    @Test
    public void foldsDiacriticsBothWays() throws Exception {
        assertEquals(2, engine.search("tuyen sinh", null, null, 1, 10).total);
        assertEquals(2, engine.search("TUYỂN SINH", null, null, 1, 10).total);
        assertEquals(1, engine.search("hoc bong khuyen khich", null, null, 1, 10).total);
    }

    @Test
    public void titleMatchOutranksBodyMatch() throws Exception {
        SearchEngine.Result r = engine.search("tuyển sinh đại học", null, null, 1, 10);
        assertEquals("https://hust.edu.vn/tuyen-sinh", r.hits.get(0).url);
    }

    @Test
    public void excludesPoorQualityDocuments() throws Exception {
        assertEquals(3, engine.numDocs());
        for (SearchEngine.Hit h : engine.search("tuyển sinh", null, null, 1, 10).hits) assertFalse(h.url.contains("/rac"));
    }

    @Test
    public void filtersAndFacets() throws Exception {
        SearchEngine.Result r = engine.search("tuyển sinh", "sdh.hust.edu.vn", null, 1, 10);
        assertEquals(1, r.total);
        // facet của chiều host bỏ qua chính bộ lọc host nên vẫn thấy cả hai host
        Map<String, Integer> hosts = new HashMap<>();
        for (SearchEngine.Facet f : r.hosts) hosts.put(f.value, f.count);
        assertEquals(Integer.valueOf(1), hosts.get("hust.edu.vn"));
        assertEquals(Integer.valueOf(1), hosts.get("sdh.hust.edu.vn"));
        assertEquals(1, engine.search("học bổng", null, "pdf", 1, 10).total);
        assertEquals(0, engine.search("khuyến khích", null, "html", 1, 10).total);
    }

    @Test
    public void relaxesWhenNoDocumentHasAllTerms() throws Exception {
        SearchEngine.Result r = engine.search("tuyển sinh học bổng", null, null, 1, 10);
        assertTrue(r.relaxed);
        assertTrue(r.total >= 2);
    }

    @Test
    public void highlightsAndEscapesHtml() throws Exception {
        SearchEngine.Result r = engine.search("bách khoa", null, null, 1, 10);
        assertNotNull(r.hits.get(0).snippet);
        assertTrue(r.hits.get(0).snippet.contains("<mark>"));
        assertEquals(0, engine.search("", null, null, 1, 10).total);
    }

    @Test
    public void repairsUtf8MisreadAsWindows125x() {
        Map<String, Integer> vocab = new HashMap<>();
        vocab.put("điều", 5);
        Mojibake.Result fx = Mojibake.repair("Ä‘iá»�u chỉnh", vocab);
        assertEquals("điều chỉnh", fx.text);
        assertEquals(0, fx.unfixed);
        assertFalse(Mojibake.suspiciousToken("MÃ"));       // "Ã" là chữ cái tiếng Việt hợp lệ
        assertFalse(Mojibake.suspiciousToken("ĐÃ"));
    }
}
