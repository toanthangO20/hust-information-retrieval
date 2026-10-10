package vn.hust.ir.extract;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Tạo phiếu gán nhãn tập vàng: danh sách khối của các trang mẫu (phân tầng theo host) để người gán nhãn đánh dấu nội dung chính. */
public final class GoldSheet {
    private GoldSheet() {}

    public static List<RawStore.Entry> sample(RawStore store, int n, long seed, int maxBlocks) throws Exception {
        Map<String, List<RawStore.Entry>> byHost = new LinkedHashMap<>();
        for (RawStore.Entry e : store.all())
            if (e.status == 200 && "HTML".equals(e.kind) && e.file != null)
                byHost.computeIfAbsent(e.host, h -> new ArrayList<>()).add(e);
        Random rnd = new Random(seed);
        List<String> hosts = new ArrayList<>(byHost.keySet());
        Collections.sort(hosts);
        for (List<RawStore.Entry> l : byHost.values()) Collections.shuffle(l, rnd);
        List<RawStore.Entry> out = new ArrayList<>();
        int[] idx = new int[hosts.size()];
        boolean progress = true;
        while (out.size() < n && progress) {          // vòng tròn qua các host
            progress = false;
            for (int h = 0; h < hosts.size() && out.size() < n; h++) {
                List<RawStore.Entry> l = byHost.get(hosts.get(h));
                while (idx[h] < l.size()) {
                    RawStore.Entry e = l.get(idx[h]++);
                    PageModel m = Pages.model(store, e);
                    if (m == null || m.blocks.size() < 2 || m.blocks.size() > maxBlocks) continue;
                    out.add(e);
                    progress = true;
                    break;
                }
            }
        }
        return out;
    }

    public static void write(RawStore store, List<RawStore.Entry> pages, Path file) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            int k = 0;
            for (RawStore.Entry e : pages) {
                byte[] body = store.read(e);
                Document d = Jsoup.parse(new java.io.ByteArrayInputStream(body), e.charset, e.finalUrl != null ? e.finalUrl : e.url);
                w.write("##### " + (++k) + " " + e.url + "\n");
                w.write("title: " + d.title() + "\n");
                w.write("og:title: " + d.select("meta[property=og:title]").attr("content") + "\n");
                w.write("pub: " + d.select("meta[property=article:published_time]").attr("content")
                        + " | author: " + d.select("meta[name=author]").attr("content") + "\n");
                Element h1 = d.selectFirst("h1");
                w.write("h1: " + (h1 == null ? "" : h1.text()) + "\n");
                Element time = d.selectFirst("time");
                if (time != null) w.write("time: " + time.attr("datetime") + " / " + time.text() + "\n");
                PageModel m = new PageModel(d);
                for (PageModel.Block b : m.blocks) {
                    String t = b.text.length() > 110 ? b.text.substring(0, 110) + "…" : b.text;
                    w.write(String.format("%d|w%d|l%.1f|%s|%s%n", b.id, b.words, b.linkDensity(),
                            b.container == null ? "?" : b.container.normalName(), t));
                }
                w.write("\n");
            }
        }
    }
}
