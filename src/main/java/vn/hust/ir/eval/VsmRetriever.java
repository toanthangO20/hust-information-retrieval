package vn.hust.ir.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;

/**
 * M1 — Mô hình không gian vec-tơ TF-IDF theo ký hiệu SMART (Ch04): tài liệu `lnc` (log tf, không idf, chuẩn hóa cosine),
 * truy vấn `ltc` (log tf, idf, chuẩn hóa cosine). Văn bản của tài liệu là tiêu đề + nội dung (một trường duy nhất như trong slide);
 * dùng cùng bộ tách âm tiết/bỏ dấu với chỉ mục Lucene.
 */
public final class VsmRetriever implements Retriever {
    private static final class Postings {
        int[] docs;
        int[] tfs;
    }

    private final LuceneCorpus corpus;
    private final Map<String, Postings> index = new HashMap<>();
    private final double[] docNorm;

    public VsmRetriever(LuceneCorpus corpus) throws IOException {
        this.corpus = corpus;
        load("content");
        load("title_t");
        docNorm = new double[corpus.n];
        for (Postings p : index.values())
            for (int i = 0; i < p.docs.length; i++) {
                double w = 1 + Math.log10(p.tfs[i]);
                docNorm[p.docs[i]] += w * w;
            }
        for (int d = 0; d < docNorm.length; d++) docNorm[d] = Math.sqrt(docNorm[d]);
    }

    private void load(String field) throws IOException {
        Terms terms = MultiTerms.getTerms(corpus.reader, field);
        if (terms == null) return;
        TermsEnum te = terms.iterator();
        PostingsEnum pe = null;
        BytesRef t;
        while ((t = te.next()) != null) {
            pe = te.postings(pe, PostingsEnum.FREQS);
            int cnt = 0;
            int[] docs = new int[Math.max(1, te.docFreq())], tfs = new int[docs.length];
            int d;
            while ((d = pe.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                docs[cnt] = d;
                tfs[cnt++] = pe.freq();
            }
            String key = t.utf8ToString();
            Postings old = index.get(key);
            Postings p = new Postings();
            if (old == null) {
                p.docs = java.util.Arrays.copyOf(docs, cnt);
                p.tfs = java.util.Arrays.copyOf(tfs, cnt);
            } else {
                merge(old, docs, tfs, cnt, p);
            }
            index.put(key, p);
        }
    }

    /** Gộp hai danh sách posting đã sắp theo doc, cộng tf nếu trùng doc (coi tiêu đề là phần đầu của văn bản). */
    private static void merge(Postings a, int[] bd, int[] bt, int bn, Postings out) {
        int[] d = new int[a.docs.length + bn], t = new int[d.length];
        int i = 0, j = 0, k = 0;
        while (i < a.docs.length || j < bn) {
            if (j >= bn || (i < a.docs.length && a.docs[i] < bd[j])) {
                d[k] = a.docs[i]; t[k++] = a.tfs[i++];
            } else if (i >= a.docs.length || bd[j] < a.docs[i]) {
                d[k] = bd[j]; t[k++] = bt[j++];
            } else {
                d[k] = a.docs[i]; t[k++] = a.tfs[i++] + bt[j++];
            }
        }
        out.docs = java.util.Arrays.copyOf(d, k);
        out.tfs = java.util.Arrays.copyOf(t, k);
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        Map<String, Integer> qtf = new HashMap<>();
        for (String t : corpus.tokens(query)) qtf.merge(t, 1, Integer::sum);
        Map<String, Double> qw = new HashMap<>();
        // [DEMO:vsm-lnc-ltc] log tf, cosine; idf phía truy vấn
        double qnorm = 0;
        for (Map.Entry<String, Integer> e : qtf.entrySet()) {
            Postings p = index.get(e.getKey());
            if (p == null) continue;
            double w = (1 + Math.log10(e.getValue())) * Math.log10((double) corpus.n / p.docs.length);
            qw.put(e.getKey(), w);
            qnorm += w * w;
        }
        double[] score = new double[corpus.n];
        if (qnorm == 0) return new ArrayList<>();
        qnorm = Math.sqrt(qnorm);
        for (Map.Entry<String, Double> e : qw.entrySet()) {
            Postings p = index.get(e.getKey());
            double wq = e.getValue() / qnorm;
            for (int i = 0; i < p.docs.length; i++) {
                int d = p.docs[i];
                score[d] += wq * (1 + Math.log10(p.tfs[i])) / docNorm[d];
            }
        }
        return corpus.topK(score, k);
    }
}
