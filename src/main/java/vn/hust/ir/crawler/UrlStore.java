package vn.hust.ir.crawler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;

/**
 * Kho URL bền vững dựa trên chỉ mục Lucene. Mỗi URL là một document (khóa = trường "url");
 * cập nhật bằng updateDocument nên các lần chạy sau (cập nhật) đọc lại được trạng thái cũ.
 * Văn bản của trang hợp lệ cũng được đánh chỉ mục (trường "content") để dùng cho các bước truy hồi sau này.
 */
public final class UrlStore implements Closeable {
    private static final int MAX_STORED_TEXT = 100_000;
    private static final Set<String> META_FIELDS = Set.of("url", "host", "state", "http", "reason", "title", "ctype",
            "hash", "neardup", "simhash", "textlen", "outlinks", "prio", "first", "crawled", "changed", "lastmod");

    private final Directory dir;
    private final IndexWriter writer;

    public UrlStore(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        dir = FSDirectory.open(indexDir);
        IndexWriterConfig cfg = new IndexWriterConfig(new StandardAnalyzer());
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        writer = new IndexWriter(dir, cfg);
    }

    /** Nạp toàn bộ bản ghi (không nạp văn bản) vào bộ nhớ. */
    public Map<String, UrlRecord> loadAll() throws IOException {
        Map<String, UrlRecord> out = new ConcurrentHashMap<>();
        if (!DirectoryReader.indexExists(dir)) return out;
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            Bits live = MultiBits.getLiveDocs(reader);
            StoredFields sf = reader.storedFields();
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (live != null && !live.get(i)) continue;
                Document d = sf.document(i, new HashSet<>(META_FIELDS));
                UrlRecord r = new UrlRecord(d.get("url"), d.get("host"), Integer.parseInt(d.get("prio")));
                r.state = Integer.parseInt(d.get("state"));
                r.httpStatus = Integer.parseInt(d.get("http"));
                r.reason = d.get("reason");
                r.title = d.get("title");
                r.contentType = d.get("ctype");
                r.contentHash = d.get("hash");
                r.nearDup = d.get("neardup");
                r.simhash = Long.parseLong(d.get("simhash"));
                r.textLen = Integer.parseInt(d.get("textlen"));
                r.outlinks = Integer.parseInt(d.get("outlinks"));
                r.firstSeen = Long.parseLong(d.get("first"));
                r.lastCrawled = Long.parseLong(d.get("crawled"));
                r.lastChanged = Long.parseLong(d.get("changed"));
                r.lastmod = Long.parseLong(d.get("lastmod"));
                out.put(r.url, r);
            }
        }
        return out;
    }

    /** Ghi (thay thế) bản ghi của một URL. text chỉ truyền cho URL hợp lệ. */
    public void save(UrlRecord r, String text) {
        Document d = new Document();
        d.add(new StringField("url", r.url, Field.Store.YES));
        d.add(new StringField("host", r.host, Field.Store.YES));
        d.add(new StringField("valid", r.state == UrlRecord.VALID ? "1" : "0", Field.Store.NO));
        d.add(new StoredField("state", String.valueOf(r.state)));
        d.add(new StoredField("http", String.valueOf(r.httpStatus)));
        d.add(new StoredField("reason", nz(r.reason)));
        d.add(new StoredField("title", nz(r.title)));
        d.add(new StoredField("ctype", nz(r.contentType)));
        d.add(new StoredField("hash", nz(r.contentHash)));
        d.add(new StoredField("neardup", nz(r.nearDup)));
        d.add(new StoredField("simhash", String.valueOf(r.simhash)));
        d.add(new StoredField("textlen", String.valueOf(r.textLen)));
        d.add(new StoredField("outlinks", String.valueOf(r.outlinks)));
        d.add(new StoredField("prio", String.valueOf(r.priority)));
        d.add(new StoredField("first", String.valueOf(r.firstSeen)));
        d.add(new StoredField("crawled", String.valueOf(r.lastCrawled)));
        d.add(new StoredField("changed", String.valueOf(r.lastChanged)));
        d.add(new StoredField("lastmod", String.valueOf(r.lastmod)));
        if (r.title != null && !r.title.isEmpty()) d.add(new TextField("title_t", r.title, Field.Store.NO));
        if (text != null && !text.isEmpty()) {
            String t = text.length() > MAX_STORED_TEXT ? text.substring(0, MAX_STORED_TEXT) : text;
            d.add(new TextField("content", t, Field.Store.YES));
        }
        try {
            writer.updateDocument(new Term("url", r.url), d);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void commit() throws IOException {
        writer.commit();
    }

    @Override
    public void close() throws IOException {
        writer.close();
        dir.close();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
