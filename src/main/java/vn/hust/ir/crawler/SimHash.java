package vn.hust.ir.crawler;

import java.util.Locale;

/**
 * SimHash 64-bit trên shingle 3-từ để phát hiện nội dung trùng lặp gần đúng (Chương 9:
 * "Phát hiện nội dung trùng lặp" - shingling, Jaccard, fingerprint).
 */
public final class SimHash {
    private SimHash() {}

    public static String[] tokens(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) return new String[0];
        return t.split("[^\\p{L}\\p{N}]+");
    }

    // [DEMO:simhash] shingle 3 từ, 64 bit
    public static long compute(String[] tok) {
        int[] v = new int[64];
        int n = Math.max(1, tok.length - 2);
        for (int i = 0; i < n; i++) {
            String sh = tok.length >= 3 ? tok[i] + ' ' + tok[i + 1] + ' ' + tok[i + 2] : String.join(" ", tok);
            long h = hash64(sh);
            for (int b = 0; b < 64; b++) v[b] += ((h >>> b) & 1L) == 1L ? 1 : -1;
        }
        long r = 0;
        for (int b = 0; b < 64; b++) if (v[b] > 0) r |= 1L << b;
        return r;
    }

    // [DEMO:hamming] khoảng cách Hamming
    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static long hash64(String s) {
        long h = 0xcbf29ce484222325L;               // FNV-1a
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL;     // finalizer kiểu murmur3 để tăng tính "avalanche"
        h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
