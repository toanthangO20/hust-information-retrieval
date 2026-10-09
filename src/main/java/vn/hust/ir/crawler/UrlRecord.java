package vn.hust.ir.crawler;

/** Trạng thái của một URL trong kho (được lưu trong chỉ mục Lucene). */
public final class UrlRecord {
    public static final int PENDING = 0, VALID = 1, INVALID = 2;

    public String url;
    public String host;
    public int state = PENDING;
    public int httpStatus;
    public String reason = "";
    public String title = "";
    public String contentType = "";
    public String contentHash = "";
    public String nearDup = "";
    public long simhash;
    public int textLen;
    public int outlinks;
    public int priority = 1;
    public long firstSeen;
    public long lastCrawled;
    public long lastChanged;
    public long lastmod;        // lastmod ghi trong sitemap (epoch ms), 0 nếu không có

    public UrlRecord(String url, String host, int priority) {
        this.url = url;
        this.host = host;
        this.priority = priority;
        this.firstSeen = System.currentTimeMillis();
    }

    public boolean isTransientFailure() {
        return state == INVALID && (httpStatus >= 500 || httpStatus == 429 || httpStatus == 421 || reason.startsWith("error:"));
    }
}
