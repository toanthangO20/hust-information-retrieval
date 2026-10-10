package vn.hust.ir.extract;

import java.io.ByteArrayInputStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Tiện ích đọc trang từ kho thô. */
public final class Pages {
    private Pages() {}

    public static Document parse(RawStore store, RawStore.Entry e) throws Exception {
        byte[] body = store.read(e);
        if (body == null) return null;
        String base = e.finalUrl != null ? e.finalUrl : e.url;
        return Jsoup.parse(new ByteArrayInputStream(body), e.charset, base);
    }

    /** Mô hình trang mới (đã bỏ script/style) hoặc null. */
    public static PageModel model(RawStore store, RawStore.Entry e) throws Exception {
        Document d = parse(store, e);
        return d == null ? null : new PageModel(d);
    }
}
