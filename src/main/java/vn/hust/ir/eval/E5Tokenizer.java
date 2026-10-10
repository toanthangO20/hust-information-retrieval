package vn.hust.ir.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bộ tách từ SentencePiece-Unigram của XLM-R (dùng cho multilingual-e5), đọc từ tokenizer.json.
 * Chuẩn hóa NFKC + gộp khoảng trắng, thêm tiền tố "▁", tách bằng Viterbi theo điểm log-xác suất của từng mảnh.
 * (Bảng "precompiled charsmap" gốc xấp xỉ NFKC; với văn bản tiếng Việt/Anh khác biệt không đáng kể.)
 */
public final class E5Tokenizer {
    public static final int BOS = 0, PAD = 1, EOS = 2, UNK = 3;
    private static final int MAX_PIECE = 24;           // độ dài tối đa (code point) của một mảnh
    private static final long BASIS = 0xcbf29ce484222325L, PRIME = 0x100000001b3L;
    private static final int CAP = 1 << 20;

    private final long[] keys = new long[CAP];
    private final int[] vals = new int[CAP];
    private final float[] score;
    private final float unkScore;

    public E5Tokenizer(Path tokenizerJson) throws IOException {
        Arrays.fill(vals, -1);
        JsonNode vocab = new ObjectMapper().readTree(Files.readAllBytes(tokenizerJson)).get("model").get("vocab");
        score = new float[vocab.size()];
        float min = 0;
        for (int i = 0; i < vocab.size(); i++) {
            String piece = vocab.get(i).get(0).asText();
            score[i] = (float) vocab.get(i).get(1).asDouble();
            if (i > 3) min = Math.min(min, score[i]);
            put(hash(piece.codePoints().toArray(), 0, piece.codePointCount(0, piece.length())), i);
        }
        unkScore = min - 10f;
    }

    private static long hash(int[] cps, int from, int len) {
        long h = BASIS;
        for (int i = from; i < from + len; i++) h = (h ^ cps[i]) * PRIME;
        return h;
    }

    private void put(long h, int id) {
        int p = (int) ((h ^ (h >>> 29)) & (CAP - 1));
        while (vals[p] >= 0 && keys[p] != h) p = (p + 1) & (CAP - 1);
        keys[p] = h;
        vals[p] = id;
    }

    private int get(long h) {
        int p = (int) ((h ^ (h >>> 29)) & (CAP - 1));
        while (vals[p] >= 0) {
            if (keys[p] == h) return vals[p];
            p = (p + 1) & (CAP - 1);
        }
        return -1;
    }

    /** Mã hóa văn bản thành [BOS] + các mảnh (tối đa maxTokens - 2) + [EOS]. */
    public int[] encode(String text, int maxTokens) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
        if (s.isEmpty()) return new int[] {BOS, EOS};
        // chỉ cần đủ ký tự cho maxTokens mảnh; cắt sớm để Viterbi không chạy trên cả văn bản dài
        if (s.length() > maxTokens * 8) s = s.substring(0, maxTokens * 8);
        int[] cps = ("▁" + s.replace(' ', '▁')).codePoints().toArray();
        int n = cps.length;
        float[] best = new float[n + 1];
        int[] backLen = new int[n + 1], backId = new int[n + 1];
        Arrays.fill(best, Float.NEGATIVE_INFINITY);
        best[0] = 0;
        for (int i = 0; i < n; i++) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue;
            long h = BASIS;
            boolean single = false;
            for (int len = 1; len <= MAX_PIECE && i + len <= n; len++) {
                h = (h ^ cps[i + len - 1]) * PRIME;
                int id = get(h);
                if (id < 0) continue;
                if (len == 1) single = true;
                float c = best[i] + score[id];
                if (c > best[i + len]) { best[i + len] = c; backLen[i + len] = len; backId[i + len] = id; }
            }
            if (!single) {
                float c = best[i] + unkScore;
                if (c > best[i + 1]) { best[i + 1] = c; backLen[i + 1] = 1; backId[i + 1] = UNK; }
            }
        }
        List<Integer> rev = new ArrayList<>();
        for (int p = n; p > 0; p -= backLen[p]) rev.add(backId[p]);
        List<Integer> ids = new ArrayList<>();
        for (int i = rev.size() - 1; i >= 0; i--) {
            int id = rev.get(i);
            if (id == UNK && !ids.isEmpty() && ids.get(ids.size() - 1) == UNK) continue;   // gộp các <unk> liền nhau
            ids.add(id);
        }
        int keep = Math.min(ids.size(), maxTokens - 2);
        int[] out = new int[keep + 2];
        out[0] = BOS;
        for (int i = 0; i < keep; i++) out[i + 1] = ids.get(i);
        out[keep + 1] = EOS;
        return out;
    }
}
