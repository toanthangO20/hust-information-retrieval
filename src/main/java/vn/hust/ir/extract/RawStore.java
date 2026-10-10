package vn.hust.ir.extract;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Kho HTML/tài liệu thô: mỗi URL một tệp {@code <host>/<sha1(url)>.gz} và một dòng trong {@code manifest.jsonl}.
 * Cho phép chạy lại bóc tách nhiều lần mà không phải tải lại từ server.
 */
public final class RawStore implements Closeable {
    /** Một dòng của manifest. */
    public static final class Entry {
        public String url;
        public String host;
        public String file;             // đường dẫn tương đối so với thư mục kho; null nếu không có body
        public int status;
        public String finalUrl;         // URL sau khi theo chuyển hướng
        public List<String> redirects = new ArrayList<>();
        public String contentType = "";
        public String charset;
        public String kind;             // HTML | DOCUMENT | OTHER
        public long bytes;
        public String lastModified;
        public long fetchedAt;
        public String error;
        public String bodySha1;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path dir;
    private final Path manifest;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private BufferedWriter out;

    public RawStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        this.manifest = dir.resolve("manifest.jsonl");
        if (Files.exists(manifest)) {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Entry e = JSON.readValue(line, Entry.class);
                entries.put(e.url, e);          // dòng sau ghi đè dòng trước (tải lại)
            }
        }
    }

    public synchronized Entry get(String url) {
        return entries.get(url);
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public static String sha1(String s) {
        return sha1(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha1(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void put(Entry e, byte[] body) throws IOException {
        if (body != null) {
            String id = sha1(e.url);
            Path p = dir.resolve(e.host).resolve(id + ".gz");
            Files.createDirectories(p.getParent());
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(p))) {
                os.write(body);
            }
            e.file = e.host + "/" + id + ".gz";
            e.bytes = body.length;
            e.bodySha1 = sha1(body);
        }
        entries.put(e.url, e);
        if (out == null)
            out = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        out.write(JSON.writeValueAsString(e));
        out.newLine();
        out.flush();
    }

    public byte[] read(Entry e) throws IOException {
        if (e.file == null) return null;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(dir.resolve(e.file)))) {
            return in.readAllBytes();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (out != null) out.close();
    }
}
