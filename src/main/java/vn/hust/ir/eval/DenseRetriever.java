package vn.hust.ir.eval;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Truy hồi vec-tơ dày (dense): mỗi tài liệu được nhúng một lần bằng multilingual-e5-small từ "tiêu đề. nội dung"
 * (chỉ phần đầu, tối đa `maxTokens` mảnh), truy vấn được nhúng lúc chạy; xếp hạng theo tích vô hướng (vec-tơ đã chuẩn hóa L2 = cosine).
 * Vec-tơ lưu theo thứ tự doc Lucene trong một tệp nhị phân.
 */
public final class DenseRetriever implements Retriever {
    private static final int MAGIC = 0xE5E50001;
    private final LuceneCorpus corpus;
    private final E5Encoder encoder;
    private final float[] vec;       // n * DIM

    public DenseRetriever(LuceneCorpus corpus, E5Encoder encoder, Path storeFile) throws IOException {
        this.corpus = corpus;
        this.encoder = encoder;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(storeFile), 1 << 20))) {
            if (in.readInt() != MAGIC) throw new IOException("Tệp vec-tơ không hợp lệ: " + storeFile);
            int n = in.readInt(), dim = in.readInt();
            in.readInt();    // maxTokens
            if (n != corpus.n || dim != E5Encoder.DIM) throw new IOException("Tệp vec-tơ không khớp chỉ mục (n=" + n + ")");
            vec = new float[n * dim];
            byte[] buf = new byte[dim * 4];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            for (int i = 0; i < n; i++) {
                in.readFully(buf);
                bb.rewind();
                bb.asFloatBuffer().get(vec, i * dim, dim);
            }
        }
    }

    /** Nhúng toàn bộ tài liệu của chỉ mục và ghi ra tệp. */
    public static void build(LuceneCorpus corpus, E5Encoder encoder, int maxTokens, Path out) throws Exception {
        int n = corpus.n;
        int[][] ids = new int[n][];
        long t0 = System.currentTimeMillis();
        for (int d = 0; d < n; d++) {
            String content = corpus.content(d);
            if (content.length() > 3000) content = content.substring(0, 3000);
            ids[d] = encoder.tokenizer().encode("passage: " + corpus.title(d) + ". " + content, maxTokens);
        }
        System.out.printf("Đã tách từ %d tài liệu (%.1f s)%n", n, (System.currentTimeMillis() - t0) / 1000.0);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(ids[a].length, ids[b].length));
        float[][] all = new float[n][];
        int batch = 32;
        t0 = System.currentTimeMillis();
        for (int s = 0; s < n; s += batch) {
            int e = Math.min(n, s + batch);
            int[][] chunk = new int[e - s][];
            for (int i = s; i < e; i++) chunk[i - s] = ids[order[i]];
            float[][] emb = encoder.embed(chunk);
            for (int i = s; i < e; i++) all[order[i]] = emb[i - s];
            if ((s / batch) % 50 == 0)
                System.out.printf("  nhúng %d/%d (%.0f tài liệu/s)%n", e, n, e * 1000.0 / (System.currentTimeMillis() - t0 + 1));
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out), 1 << 20))) {
            o.writeInt(MAGIC);
            o.writeInt(n);
            o.writeInt(E5Encoder.DIM);
            o.writeInt(maxTokens);
            ByteBuffer bb = ByteBuffer.allocate(E5Encoder.DIM * 4);
            for (int i = 0; i < n; i++) {
                bb.clear();
                bb.asFloatBuffer().put(all[i]);
                o.write(bb.array());
            }
        }
        System.out.printf("Đã ghi %s (%.1f s)%n", out, (System.currentTimeMillis() - t0) / 1000.0);
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        float[] q;
        try {
            q = encoder.embedQuery(query);
        } catch (Exception e) {
            throw new IOException(e);
        }
        int dim = E5Encoder.DIM;
        double[] s = new double[corpus.n];
        for (int d = 0; d < corpus.n; d++) {
            double dot = 0;
            int o = d * dim;
            for (int j = 0; j < dim; j++) dot += q[j] * vec[o + j];
            s[d] = dot + 1.0;           // dịch về (0, 2] để dùng chung topK; trừ lại bên dưới
        }
        List<Scored> out = new ArrayList<>();
        for (Scored sc : corpus.topK(s, k)) out.add(new Scored(sc.id, sc.score - 1f));
        return out;
    }

}
