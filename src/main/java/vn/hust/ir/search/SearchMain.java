package vn.hust.ir.search;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import vn.hust.ir.eval.LiveCompare;

/** Lệnh: dataset | index | serve | query. Xem scripts\search.bat. */
public final class SearchMain {
    private static final String USAGE = String.join("\n",
            "Sử dụng: search <lệnh> [tuỳ chọn]",
            "  dataset            Dựng bộ dữ liệu từ output/urls_valid.txt + data/index  -> dataset/<version>/",
            "  index              Dựng chỉ mục tìm kiếm từ bộ dữ liệu                   -> index/<version>/",
            "  serve              Chạy giao diện web (mặc định http://localhost:8080); so sánh mô hình tại /compare.html (--no-compare 1 để tắt)",
            "  query <từ khóa>    Truy vấn nhanh trên dòng lệnh",
            "Tuỳ chọn: --version v1 --port 8080 --web-dir DIR --urls FILE --crawl-index DIR --size N");

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        if (args.length == 0) {
            System.out.println(USAGE);
            return;
        }
        Map<String, String> o = new HashMap<>();
        StringBuilder rest = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) o.put(args[i].substring(2), args[++i]);
            else rest.append(rest.length() > 0 ? " " : "").append(args[i]);
        }
        String version = o.getOrDefault("version", "ver1");
        Path datasetDir = Paths.get("dataset", version);
        Path indexDir = Paths.get("index", version);

        switch (args[0]) {
            case "dataset":
                DatasetBuilder.build(Paths.get(o.getOrDefault("urls", "output/urls_valid.txt")),
                        Paths.get(o.getOrDefault("crawl-index", "data/index")), datasetDir, version);
                break;
            case "index":
                SearchIndexer.build(datasetDir, indexDir);
                break;
            case "query": {
                try (SearchEngine e = new SearchEngine(indexDir)) {
                    SearchEngine.Result r = e.search(rest.toString(), o.get("host"), o.get("type"), 1,
                            Integer.parseInt(o.getOrDefault("size", "10")));
                    System.out.printf("%d kết quả (%d ms)%s%n", r.total, r.tookMs, r.relaxed ? " [nới điều kiện]" : "");
                    int i = 0;
                    for (SearchEngine.Hit h : r.hits)
                        System.out.printf("%2d. %.2f  %s%n    %s%n", ++i, h.score, h.title, h.url);
                }
                break;
            }
            case "serve": {
                SearchEngine engine = new SearchEngine(indexDir);
                int port = Integer.parseInt(o.getOrDefault("port", "8080"));
                Path web = o.containsKey("web-dir") ? Paths.get(o.get("web-dir")) : null;
                LiveCompare cmp = o.containsKey("no-compare") ? null : new LiveCompare();
                new WebServer(engine, datasetDir.resolve("manifest.json"), web).withCompare(cmp).start(port);
                System.out.println("Đang chạy tại http://localhost:" + port + "  (Ctrl+C để dừng)");
                if (cmp != null) {
                    System.out.println("So sánh mô hình: http://localhost:" + port + "/compare.html  (đang nạp mô hình ở nền...)");
                    Thread t = new Thread(() -> {
                        try {
                            cmp.warmUp();
                            System.out.println("So sánh mô hình: sẵn sàng.");
                        } catch (Exception e) {
                            System.out.println("So sánh mô hình: lỗi khi nạp - " + e.getMessage());
                        }
                    }, "compare-warmup");
                    t.setDaemon(true);
                    t.start();
                }
                break;
            }
            default:
                System.out.println(USAGE);
        }
    }
}
