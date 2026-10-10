package vn.hust.ir.search;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** Lệnh: dataset | index | serve | query. Xem scripts\search.bat. */
public final class SearchMain {
    private static final String USAGE = String.join("\n",
            "Sử dụng: search <lệnh> [tuỳ chọn]",
            "  dataset            Dựng bộ dữ liệu từ output/urls_valid.txt + data/index  -> dataset/<version>/",
            "  index              Dựng chỉ mục tìm kiếm từ bộ dữ liệu                   -> index/<version>/",
            "  serve              Chạy giao diện web (mặc định http://localhost:8080)",
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
                new WebServer(engine, datasetDir.resolve("manifest.json"), web).start(port);
                System.out.println("Đang chạy tại http://localhost:" + port + "  (Ctrl+C để dừng)");
                break;
            }
            default:
                System.out.println(USAGE);
        }
    }
}
