package vn.hust.ir.search;

import java.text.Normalizer;

/** Tiện ích văn bản dùng chung cho bước dựng bộ dữ liệu, chỉ mục và truy vấn. */
final class Text {
    private Text() {}

    /** Chuẩn hóa Unicode về NFC để dấu tiếng Việt luôn ở dạng tổ hợp sẵn. */
    static String nfc(String s) {
        return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** Phân loại tài liệu hiển thị cho người dùng từ Content-Type và đuôi URL. */
    static String docType(String contentType, String url) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        String u = url.toLowerCase();
        if (ct.contains("pdf") || u.endsWith(".pdf")) return "pdf";
        if (ct.contains("wordprocessing") || ct.contains("msword") || u.endsWith(".doc") || u.endsWith(".docx")) return "word";
        if (ct.contains("spreadsheet") || ct.contains("ms-excel") || u.endsWith(".xls") || u.endsWith(".xlsx")) return "excel";
        if (ct.contains("presentation") || ct.contains("powerpoint") || u.endsWith(".ppt") || u.endsWith(".pptx")) return "powerpoint";
        if (ct.contains("html") || ct.isEmpty()) return "html";
        return "khac";
    }

    private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile("https?://\\S+");
    private static final java.util.regex.Pattern ISO_TIME = java.util.regex.Pattern
            .compile("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?(?:[+-]\\d\\d:\\d\\d|Z)?");

    /**
     * Làm sạch văn bản đã trích: bỏ địa chỉ URL và dấu thời gian ISO-8601 (siêu dữ liệu của CMS lẫn vào nội dung,
     * chiếm ~2% ký tự), gộp khoảng trắng liên tiếp nhưng giữ ngắt đoạn.
     */
    static String clean(String s) {
        s = URL.matcher(s).replaceAll(" ");
        s = ISO_TIME.matcher(s).replaceAll(" ");
        // NBSP (U+00A0) được giữ lại: là một byte của chuỗi UTF-8 bị đọc nhầm, cần cho bước sửa chữ vỡ; bỏ ở normalizeSpace
        s = s.replaceAll("[ \\t]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    static String normalizeSpace(String s) {
        return s.replace(' ', ' ').replaceAll("[ \\t]+", " ").strip();
    }

    /** Văn bản dùng font TCVN3/VNI cũ khi trích ra là các ký hiệu Latin-1 (¸ µ § ¨...) thay cho chữ có dấu. */
    static boolean legacyFont(String s) {
        int sym = 0, nonSpace = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            nonSpace++;
            if (c >= 0xA1 && c <= 0xBF && c != 0xAB && c != 0xBB && c != 0xB0 && c != 0xB2 && c != 0xB3 && c != 0xB7 && c != 0xA9)
                sym++;
        }
        return nonSpace > 0 && sym * 100.0 / nonSpace > 1.0;
    }

    static int wordCount(String s) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            boolean w = !Character.isWhitespace(s.charAt(i));
            if (w && !in) n++;
            in = w;
        }
        return n;
    }

    /** Tiêu đề dự phòng khi trang/tài liệu không có tiêu đề: dòng đầu văn bản hoặc tên tệp trong URL. */
    static String fallbackTitle(String title, String text, String url) {
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty()) return t;
        String first = text.strip();
        int nl = first.indexOf('\n');
        if (nl > 0) first = first.substring(0, nl).strip();
        if (first.length() > 120) first = first.substring(0, 120).replaceAll("\\s+\\S*$", "") + "…";
        if (first.length() >= 8) return first;
        int q = url.indexOf('?');
        String path = q > 0 ? url.substring(0, q) : url;
        String last = path.substring(path.lastIndexOf('/') + 1);
        try {
            last = java.net.URLDecoder.decode(last.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) { }
        return last.isEmpty() ? url : last;
    }
}
