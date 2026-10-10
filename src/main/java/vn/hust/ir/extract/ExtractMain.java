package vn.hust.ir.extract;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import vn.hust.ir.crawler.Config;

/** Lệnh: snapshot | ... Xem scripts\extract.bat. */
public final class ExtractMain {
    private static final String USAGE = String.join("\n",
            "Sử dụng: extract <lệnh> [tuỳ chọn]",
            "  snapshot   Tải lại các URL trong urls_valid.txt, lưu HTML/tài liệu thô vào data/raw",
            "Tuỳ chọn: --urls FILE --raw DIR --threads N --delay-ms N --global-delay-ms N --limit N --only-hosts h1,h2");

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        for (String n : new String[]{"org.apache.pdfbox", "org.apache.fontbox", "org.apache.poi", "org.apache.tika"})
            Logger.getLogger(n).setLevel(Level.OFF);
        if (args.length == 0) {
            System.out.println(USAGE);
            return;
        }
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i < args.length; i++)
            if (args[i].startsWith("--") && i + 1 < args.length) o.put(args[i].substring(2), args[++i]);
        Path raw = Paths.get(o.getOrDefault("raw", "data/raw"));
        switch (args[0]) {
            case "snapshot": {
                Config cfg = new Config();
                if (o.containsKey("delay-ms")) cfg.delayMs = Long.parseLong(o.get("delay-ms"));
                try (RawStore store = new RawStore(raw)) {
                    Snapshot snap = new Snapshot(cfg, store, Long.parseLong(o.getOrDefault("global-delay-ms", "60")));
                    snap.perHostWorkers = Integer.parseInt(o.getOrDefault("host-workers", "4"));
                    snap.run(Paths.get(o.getOrDefault("urls", "output/urls_valid.txt")),
                                    Integer.parseInt(o.getOrDefault("threads", "16")),
                                    Integer.parseInt(o.getOrDefault("limit", "0")),
                                    o.containsKey("only-hosts") ? new HashSet<>(Arrays.asList(o.get("only-hosts").split(",")))
                                            : new HashSet<>());
                }
                break;
            }
            case "sheet": {   // tạo phiếu gán nhãn tập vàng
                try (RawStore store = new RawStore(raw)) {
                    java.util.List<RawStore.Entry> pages = GoldSheet.sample(store, Integer.parseInt(o.getOrDefault("n", "100")),
                            Long.parseLong(o.getOrDefault("seed", "7")), Integer.parseInt(o.getOrDefault("max-blocks", "150")));
                    Path dir = Paths.get(o.getOrDefault("out", "eval/extract/sheets"));
                    java.nio.file.Files.createDirectories(dir);
                    java.util.List<RawStore.Entry> dev = new java.util.ArrayList<>(), test = new java.util.ArrayList<>();
                    for (int i = 0; i < pages.size(); i++) (i % 2 == 0 ? dev : test).add(pages.get(i));
                    GoldSheet.write(store, dev, dir.resolve("dev.txt"));
                    GoldSheet.write(store, test, dir.resolve("test.txt"));
                    System.out.println("dev=" + dev.size() + " test=" + test.size() + " -> " + dir);
                }
                break;
            }
            case "eval": case "eval-meta": {     // đánh giá trên tập vàng
                String gold = o.getOrDefault("gold", "eval/extract/gold_dev.tsv");
                String rep = o.getOrDefault("report", args[0].equals("eval") ? "eval/extract/report_blocks_dev.md" : "eval/extract/report_meta_dev.md");
                Extractor.Strategy st = new Extractor.Strategy();
                if (o.containsKey("algo")) st.primary = o.get("algo");
                if (o.containsKey("keep-ratio")) st.keepRatio = Double.parseDouble(o.get("keep-ratio"));
                if (o.containsKey("max-gap")) st.maxGap = Integer.parseInt(o.get("max-gap"));
                if (o.containsKey("template-ratio")) st.templateRatio = Double.parseDouble(o.get("template-ratio"));
                if (o.containsKey("template-min-df")) st.templateMinDf = Integer.parseInt(o.get("template-min-df"));
                try (RawStore store = new RawStore(raw)) {
                    CssRules cr = CssRules.load(o.containsKey("rules") ? Paths.get(o.get("rules")) : null);
                    if (args[0].equals("eval")) BlockEval.run(store, Paths.get(gold), Paths.get(rep), cr, st);
                    else BlockEval.runMeta(store, Paths.get(gold), Paths.get(rep), cr, st);
                }
                break;
            }
            case "index": {    // P7: chỉ mục thử nghiệm có nội dung chính + văn bản liên kết
                ExtractIndexer.build(Paths.get(o.getOrDefault("dataset", "dataset/ver1")), Paths.get(o.getOrDefault("out", "data/extract/v1")),
                        Paths.get(o.getOrDefault("index", "index/ver1x")));
                break;
            }
            case "train-blocks": {   // huấn luyện bộ phân loại khối trên tập vàng dev
                Extractor.Strategy st = new Extractor.Strategy();
                try (RawStore store = new RawStore(raw)) {
                    BlockTrainer.run(store, Paths.get(o.getOrDefault("gold", "eval/extract/gold_dev.tsv")),
                            Paths.get(o.getOrDefault("model", "src/main/resources/extract/block_model.json")),
                            CssRules.load(null), st, Double.parseDouble(o.getOrDefault("l2", "0.01")),
                            Integer.parseInt(o.getOrDefault("epochs", "600")), Double.parseDouble(o.getOrDefault("lr", "0.5")));
                }
                break;
            }
            case "show": {      // chẩn đoán một URL trong kho thô: khối, xác suất nội dung, metadata
                try (RawStore store = new RawStore(raw)) {
                    RawStore.Entry e = store.get(o.get("url"));
                    Extractor.Strategy st = new Extractor.Strategy();
                    Extractor ex = new Extractor(store, st, CssRules.load(null));
                    java.util.List<RawStore.Entry> pages = new java.util.ArrayList<>();
                    for (RawStore.Entry x : store.all())
                        if (x.host.equals(e.host) && x.status == 200 && "HTML".equals(x.kind) && x.file != null) pages.add(x);
                    Extractor.HostStats hs = pages.size() >= 3 ? ex.hostStats(pages, 6) : null;
                    PageModel m = Pages.model(store, e);
                    LogReg model = LogReg.loadResource("/extract/block_model.json");
                    new BlockFinder.Learned(model, hs).apply(m);
                    for (PageModel.Block b : m.blocks)
                        System.out.printf("%3d %s w%-4d l%.1f %-10s %s%n", b.id, b.content ? "#" : ".", b.words, b.linkDensity(),
                                b.container == null ? "?" : b.container.normalName(), b.text.length() > 100 ? b.text.substring(0, 100) : b.text);
                    Extractor.Output out = ex.process(e, hs);
                    for (String k2 : new String[]{"title", "publishedAt", "author", "sourceRef"}) System.out.println(k2 + " = " + out.page.get(k2));
                }
                break;
            }
            case "graph": {    // P5/P6: đồ thị liên kết cuối cùng
                new GraphBuilder().build(Paths.get(o.getOrDefault("out", "data/extract/v1")),
                        Paths.get(o.getOrDefault("invalid", "output/urls_invalid.tsv")), raw.resolve("manifest.jsonl"));
                break;
            }
            case "load": {     // P6: nạp MongoDB
                MongoLoader.load(Paths.get(o.getOrDefault("out", "data/extract/v1")), o.getOrDefault("uri", "mongodb://localhost:27017"),
                        o.getOrDefault("db", "hust_ir"), !o.containsKey("keep"));
                break;
            }
            case "run": {     // P2–P5: bóc tách toàn bộ kho
                Extractor.Strategy st = new Extractor.Strategy();
                if (o.containsKey("algo")) st.primary = o.get("algo");
                if (o.containsKey("no-css")) st.css = false;
                if (o.containsKey("no-template")) st.template = false;
                if (o.containsKey("template-ratio")) st.templateRatio = Double.parseDouble(o.get("template-ratio"));
                if (o.containsKey("template-min-df")) st.templateMinDf = Integer.parseInt(o.get("template-min-df"));
                try (RawStore store = new RawStore(raw)) {
                    new Extractor(store, st, CssRules.load(o.containsKey("rules") ? Paths.get(o.get("rules")) : null))
                            .run(Paths.get(o.getOrDefault("out", "data/extract/v1")), Integer.parseInt(o.getOrDefault("threads", "6")),
                                    o.containsKey("only-hosts") ? new HashSet<>(Arrays.asList(o.get("only-hosts").split(","))) : new HashSet<>());
                }
                break;
            }
            default:
                System.out.println(USAGE);
        }
        System.exit(0);
    }
}
