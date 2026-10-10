package vn.hust.ir.eval;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import vn.hust.ir.search.ViAnalyzer;

/** Chỉ mục Lucene dùng chung cho các mô hình: ánh xạ doc Lucene <-> mã tài liệu của dataset, phân tích truy vấn. */
public final class LuceneCorpus implements Closeable {
    public final FSDirectory dir;
    public final DirectoryReader reader;
    public final Analyzer analyzer = ViAnalyzer.forIndex();
    /** idOf[docLucene] = mã tài liệu trong dataset. */
    public final int[] idOf;
    public final int n;

    public LuceneCorpus(Path indexDir) throws IOException {
        dir = FSDirectory.open(indexDir);
        reader = DirectoryReader.open(dir);
        n = reader.maxDoc();
        idOf = new int[n];
        for (int i = 0; i < n; i++)
            idOf[i] = reader.storedFields().document(i, Set.of("id")).getField("id").numericValue().intValue();
    }

    /** Tách truy vấn thành các âm tiết đã bỏ dấu, đúng như lúc đánh chỉ mục. */
    public List<String> tokens(String q) throws IOException {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("content", q)) {
            CharTermAttribute a = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(a.toString());
            ts.end();
        }
        return out;
    }

    public String content(int luceneDoc) throws IOException {
        return reader.storedFields().document(luceneDoc, Set.of("content")).get("content");
    }

    public String title(int luceneDoc) throws IOException {
        return reader.storedFields().document(luceneDoc, Set.of("title")).get("title");
    }

    /** Chọn top-k điểm lớn nhất của mảng điểm theo doc Lucene (điểm <= 0 bị bỏ), xếp giảm dần. */
    public List<Scored> topK(double[] scores, int k) {
        int[] heap = new int[k];
        int size = 0;
        // heap nhỏ nhất ở gốc, lưu chỉ số doc
        for (int d = 0; d < scores.length; d++) {
            if (scores[d] <= 0) continue;
            if (size < k) {
                heap[size++] = d;
                siftUp(heap, size - 1, scores);
            } else if (scores[d] > scores[heap[0]]) {
                heap[0] = d;
                siftDown(heap, size, scores);
            }
        }
        List<Integer> docs = new ArrayList<>();
        for (int i = 0; i < size; i++) docs.add(heap[i]);
        docs.sort((a, b) -> {
            int c = Double.compare(scores[b], scores[a]);
            return c != 0 ? c : Integer.compare(idOf[a], idOf[b]);
        });
        List<Scored> out = new ArrayList<>();
        for (int d : docs) out.add(new Scored(idOf[d], (float) scores[d]));
        return out;
    }

    private static void siftUp(int[] h, int i, double[] s) {
        while (i > 0) {
            int p = (i - 1) / 2;
            if (s[h[p]] <= s[h[i]]) break;
            int t = h[p]; h[p] = h[i]; h[i] = t;
            i = p;
        }
    }

    private static void siftDown(int[] h, int size, double[] s) {
        int i = 0;
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            if (l < size && s[h[l]] < s[h[m]]) m = l;
            if (r < size && s[h[r]] < s[h[m]]) m = r;
            if (m == i) break;
            int t = h[m]; h[m] = h[i]; h[i] = t;
            i = m;
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
        dir.close();
    }
}
