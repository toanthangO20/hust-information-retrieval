package vn.hust.ir.search;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phát hiện và sửa văn bản "chữ vỡ" (mojibake): các byte UTF-8 của tiếng Việt bị đọc nhầm như Windows-1252/1250,
 * ví dụ "Ä‘iá»�u" thay cho "điều". Một số trang của ctt.hust.edu.vn lưu sẵn văn bản ở dạng này.
 *
 * Cách sửa: mã hóa ngược từng ký tự về byte theo bảng mã đoán được, giải mã lại bằng UTF-8 nghiêm ngặt.
 * Byte 0x81/0x8D/0x8F/0x90/0x9D không có trong Windows-125x nên đã mất thành U+FFFD; các ứng viên được chọn
 * theo tần suất từ trong phần văn bản sạch của chính bộ dữ liệu.
 */
final class Mojibake {
    private static final Charset[] CHARSETS = {Charset.forName("windows-1252"), Charset.forName("windows-1250")};
    private static final int[] LOST = {0x81, 0x8D, 0x8F, 0x90, 0x9D};
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Pattern TAIL_WORD = Pattern.compile("\\p{L}+$");
    private static final Pattern HEAD_WORD = Pattern.compile("^\\p{L}+");
    private static final Pattern SPACE = Pattern.compile("\\s+|\\S+");
    private static final Pattern LATIN1_JUNK = Pattern.compile("Â[\u00A0-\u00BF]");
    private static final Pattern LEAD_JUNK = Pattern.compile("[ÃÄÅÆĂ][\u0080-\u00BF\u0152\u0153\u0160\u0161\u0178\u017D\u017E\u0192\u02C6\u02DC\u2013-\u203A\u20AC\u2122\uFFFD]");

    private Mojibake() {}

    /** Kết quả sửa: văn bản mới và số token nghi chữ vỡ trước / sau khi sửa. */
    static final class Result {
        String text;
        int suspicious, unfixed, tokens;
    }

    /**
     * Dấu hiệu chữ vỡ. "Ã" là chữ cái tiếng Việt hợp lệ (MÃ, ĐÃ) nên chỉ tính khi theo sau là ký tự thuộc dải
     * byte tiếp theo của UTF-8 (U+0080-U+00BF và các dấu câu của Windows-125x), hoặc khi cuối từ mà ngay trước là chữ thường.
     */
    static boolean suspiciousToken(String t) {
        if (LEAD_JUNK.matcher(t).find() || t.contains("á»") || t.contains("áº")
                || LATIN1_JUNK.matcher(t).find()) return true;
        int n = t.length();
        if (n == 0) return false;
        char last = t.charAt(n - 1);
        if (last == 'Ă' || last == 'Ä') return true;
        return last == 'Ã' && n > 1 && Character.isLowerCase(t.charAt(n - 2));
    }

    /**
     * Sửa từng token nghi chữ vỡ tại chỗ, giữ nguyên phần còn lại. Bảng mã (1252/1250) chọn theo tài liệu.
     * Token không sửa được vẫn giữ nguyên và được đếm vào unfixed.
     */
    static Result repair(String text, Map<String, Integer> vocab) {
        List<String> tokens = new ArrayList<>();
        Matcher m = SPACE.matcher(text);
        while (m.find()) tokens.add(m.group());

        Result r = new Result();
        r.tokens = tokens.size();
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!suspiciousToken(t)) {
                out.append(t);
                continue;
            }
            r.suspicious++;
            String fixed = decode(t, vocab);
            // Byte 0xA0 (NBSP) cuối chuỗi UTF-8 có thể đã bị bước trích văn bản đổi thành dấu cách thường
            if (fixed == null && i + 2 < tokens.size() && tokens.get(i + 1).equals(" ")) {
                fixed = decode(t + "\u00A0", vocab);
                if (fixed != null) {
                    // dấu cách có thể chính là byte 0xA0 (nối từ: "Đ"+"à"+"o") hoặc là dấu cách thật; nối nếu ra từ có trong từ vựng
                    if (joinsToKnownWord(fixed, tokens.get(i + 2), vocab)) i++;
                    else {
                        out.append(fixed);
                        continue;
                    }
                }
            }
            if (fixed != null) out.append(fixed);
            else {
                r.unfixed++;
                out.append(t);
            }
        }
        r.text = out.toString();
        return r;
    }

    private static boolean joinsToKnownWord(String left, String right, Map<String, Integer> vocab) {
        Matcher a = TAIL_WORD.matcher(left);
        Matcher b = HEAD_WORD.matcher(right);
        if (!a.find() || !b.find()) return false;
        Integer f = vocab.get((a.group() + b.group()).toLowerCase());
        return f != null && f > 0;
    }

    /** Byte gốc của ký tự theo Windows-1252, nếu không có thì theo Windows-1250 (một số trang trộn cả hai); -1 nếu không có. */
    private static int byteOf(char c) {
        for (Charset cs : CHARSETS) {
            byte[] b = String.valueOf(c).getBytes(cs);
            if (b.length == 1 && b[0] != '?') return b[0] & 0xFF;
        }
        return -1;
    }

    /** Giải mã một token; null nếu không thể (ký tự không thuộc bảng mã, hoặc không phải UTF-8 hợp lệ). */
    private static String decode(String t, Map<String, Integer> vocab) {
        byte[] bytes = new byte[t.length()];
        List<Integer> lostAt = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x80) bytes[i] = (byte) c;
            else if (c >= 0x80 && c <= 0x9F) bytes[i] = (byte) c;      // ký tự điều khiển: byte gốc
            else if (c == '�') {
                lostAt.add(i);
            } else {
                int b = byteOf(c);
                if (b < 0) return null;
                bytes[i] = (byte) b;
            }
        }
        if (lostAt.isEmpty()) return strict(bytes);
        if (lostAt.size() > 3) return null;

        String bestStr = null;
        double bestScore = -1;
        int combos = (int) Math.pow(LOST.length, lostAt.size());
        for (int k = 0; k < combos; k++) {
            int x = k;
            for (int pos : lostAt) {
                bytes[pos] = (byte) LOST[x % LOST.length];
                x /= LOST.length;
            }
            String s = strict(bytes);
            if (s == null) continue;
            double score = vocab == null ? 0 : score(s, vocab);
            if (bestStr == null || score > bestScore) {
                bestStr = s;
                bestScore = score;
            }
        }
        return bestStr;
    }

    private static double score(String s, Map<String, Integer> vocab) {
        double sc = 0;
        Matcher m = WORD.matcher(s.toLowerCase());
        while (m.find()) {
            Integer f = vocab.get(m.group());
            sc += f == null ? 0 : Math.log(1 + f);
        }
        return sc;
    }

    private static String strict(byte[] bytes) {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = d.decode(ByteBuffer.wrap(bytes));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
