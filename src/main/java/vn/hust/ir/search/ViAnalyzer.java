package vn.hust.ir.search;

import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Bộ phân tích cho tiếng Việt: tách từ theo âm tiết (UAX#29), hạ chữ thường, bỏ dấu (đ thành d).
 * Nhờ bỏ dấu ở cả lúc đánh chỉ mục và lúc truy vấn, "tuyen sinh" tìm được "tuyển sinh".
 * Không dùng danh sách từ dừng: nhiều âm tiết tiếng Việt vừa là từ dừng vừa có nghĩa ("là", "đi", "có").
 *
 * Bỏ dấu làm trùng một số âm tiết (ký/kỳ, xá/xã), nên chỉ mục còn có các trường "_x" giữ nguyên dấu
 * (xem {@link #exact()}); chúng chỉ dùng để thưởng điểm cho kết quả khớp đúng dấu.
 */
public final class ViAnalyzer extends Analyzer {
    private static final Analyzer EXACT = new Analyzer() {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            // [DEMO:vi-analyzer] tách âm tiết + bỏ dấu
            StandardTokenizer src = new StandardTokenizer();
            return new TokenStreamComponents(src, new LowerCaseFilter(src));
        }
    };

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        StandardTokenizer src = new StandardTokenizer();
        TokenStream ts = new LowerCaseFilter(src);
        ts = new ASCIIFoldingFilter(ts);
        return new TokenStreamComponents(src, ts);
    }

    /** Bộ phân tích giữ nguyên dấu (chỉ hạ chữ thường). */
    public static Analyzer exact() {
        return EXACT;
    }

    /** Bộ phân tích cho cả chỉ mục lẫn truy vấn: trường *_x giữ dấu, các trường còn lại bỏ dấu. */
    public static Analyzer forIndex() {
        return new PerFieldAnalyzerWrapper(new ViAnalyzer(), Map.of("title_x", EXACT, "content_x", EXACT));
    }
}
