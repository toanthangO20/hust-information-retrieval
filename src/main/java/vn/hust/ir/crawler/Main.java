package vn.hust.ir.crawler;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {
    private static final String USAGE = String.join("\n",
            "Sử dụng: hust-crawler <lệnh> [tuỳ chọn]",
            "  crawl | update   Crawl lần đầu hoặc cập nhật (tải URL mới + URL đã cũ), sau đó xuất danh sách",
            "  export           Xuất lại urls_valid.txt từ kho URL, không tải thêm",
            "  stats            Thống kê kho URL",
            "Tuỳ chọn: --threads N --delay-ms N --max-pages N --max-per-host N --min-chars N --min-words N",
            "          --revisit-days N --hub-revisit-hours N --seeds url1,url2 --only-hosts h1,h2 --recheck no-content,noindex --no-sitemap --drop-near-dup --out DIR --data DIR");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println(USAGE);
            return;
        }
        for (String n : new String[]{"org.apache.pdfbox", "org.apache.fontbox", "org.apache.poi", "org.apache.tika"})
            Logger.getLogger(n).setLevel(Level.OFF);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        if (args[0].equals("inspect")) {      // chẩn đoán một URL: hiển thị phần văn bản trích được
            Config c = new Config();
            Fetcher.Result r = new Fetcher(c).get(args[1]);
            System.out.println("status=" + r.status + " type=" + r.contentType + " kind=" + r.kind + " err=" + r.error);
            if (r.body != null) {
                ContentExtractor.Result ex = r.kind == Fetcher.Kind.HTML
                        ? new ContentExtractor().extractHtml(r.body, r.charset, args[1])
                        : new ContentExtractor().extractDocument(r.body, r.contentType, args[1]);
                String[] tok = SimHash.tokens(ex.text);
                System.out.println("title=" + ex.title + "\nchars=" + ex.text.length() + " words=" + tok.length
                        + " links=" + ex.links.size() + " noindex=" + ex.noindex + " canonical=" + ex.canonical);
                System.out.println("-----\n" + ex.text.substring(0, Math.min(600, ex.text.length())));
            }
            System.exit(0);
        }
        Config cfg = Config.parse(args, 1);

        try (UrlStore store = new UrlStore(cfg.dataDir.resolve("index"))) {
            Map<String, UrlRecord> records = store.loadAll();
            switch (args[0]) {
                case "crawl":
                case "update": {
                    Crawler c = new Crawler(cfg, store, records);
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            store.commit();
                        } catch (Exception ignored) { }
                    }));
                    c.run();
                    Exporter.export(records, cfg.outDir);
                    break;
                }
                case "export":
                    Exporter.export(records, cfg.outDir);
                    break;
                case "stats": {
                    Map<String, int[]> perHost = new TreeMap<>();
                    for (UrlRecord r : records.values()) {
                        int[] a = perHost.computeIfAbsent(r.host, h -> new int[3]);
                        a[r.state]++;
                    }
                    System.out.printf("%-34s %8s %8s %8s%n", "host", "pending", "valid", "invalid");
                    for (Map.Entry<String, int[]> e : perHost.entrySet())
                        System.out.printf("%-34s %8d %8d %8d%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
                    break;
                }
                default:
                    System.out.println(USAGE);
            }
        }
        System.exit(0);     // các luồng nền của HttpClient có thể giữ JVM sống
    }
}
