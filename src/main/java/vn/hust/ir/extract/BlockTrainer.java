package vn.hust.ir.extract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import vn.hust.ir.extract.BlockEval.Gold;
import vn.hust.ir.extract.PageModel.Block;

/** Huấn luyện bộ phân loại khối (hồi quy logistic) trên tập vàng dev, kèm kiểm định chéo 5 phần theo trang. */
public final class BlockTrainer {
    private BlockTrainer() {}

    private static final class PageData {
        String url;
        List<double[]> x = new ArrayList<>();
        List<Integer> y = new ArrayList<>();
        List<Integer> words = new ArrayList<>();
    }

    static List<PageData> collect(RawStore store, List<Gold> gold, CssRules rules, Extractor.Strategy base) throws Exception {
        Map<String, RawStore.Entry> byUrl = new HashMap<>();
        for (RawStore.Entry e : store.all()) byUrl.put(e.url, e);
        Extractor ex = new Extractor(store, base, rules);
        Map<String, Extractor.HostStats> stats = new HashMap<>();
        List<PageData> out = new ArrayList<>();
        for (Gold g : gold) {
            RawStore.Entry e = byUrl.get(g.url);
            if (e == null || e.file == null) continue;
            Extractor.HostStats hs = stats.computeIfAbsent(e.host, h -> {
                List<RawStore.Entry> pages = new ArrayList<>();
                for (RawStore.Entry x : store.all())
                    if (x.host.equals(h) && x.status == 200 && "HTML".equals(x.kind) && x.file != null) pages.add(x);
                try {
                    return pages.size() >= 3 ? ex.hostStats(pages, 6) : null;
                } catch (Exception ex2) {
                    throw new RuntimeException(ex2);
                }
            });
            PageModel m = Pages.model(store, e);
            if (m.blocks.size() != g.nblocks) continue;
            boolean[] bte = new boolean[m.blocks.size()], rl = new boolean[m.blocks.size()];
            new BlockFinder.Bte(false).apply(m);
            for (int i = 0; i < bte.length; i++) bte[i] = m.blocks.get(i).content;
            new BlockFinder.Rules(false, 2, 0.5).apply(m);
            for (int i = 0; i < rl.length; i++) rl[i] = m.blocks.get(i).content;
            double[][] f = BlockFeatures.compute(m, hs, bte, rl);
            PageData pd = new PageData();
            pd.url = g.url;
            for (Block b : m.blocks) {
                pd.x.add(f[b.id]);
                pd.y.add(g.content.contains(b.id) ? 1 : 0);
                pd.words.add(b.words);
            }
            out.add(pd);
        }
        return out;
    }

    private static LogReg fit(List<PageData> pages, double l2, int epochs, double lr) {
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        for (PageData p : pages)
            for (int i = 0; i < p.x.size(); i++) {
                xs.add(p.x.get(i));
                ys.add(p.y.get(i));
                ws.add(Math.sqrt(1 + p.words.get(i)));
            }
        return LogReg.train(BlockFeatures.NAMES, xs, ys, ws, l2, epochs, lr);
    }

    /** Điểm F1 mức từ (micro, macro theo trang) của mô hình trên các trang. */
    private static double[] score(LogReg m, List<PageData> pages) {
        double tp = 0, pr = 0, gd = 0, sumF = 0;
        for (PageData p : pages) {
            double ptp = 0, ppr = 0, pgd = 0;
            for (int i = 0; i < p.x.size(); i++) {
                boolean pred = m.prob(p.x.get(i)) >= m.threshold;
                int w = p.words.get(i);
                if (p.y.get(i) == 1) pgd += w;
                if (pred) {
                    ppr += w;
                    if (p.y.get(i) == 1) ptp += w;
                }
            }
            tp += ptp;
            pr += ppr;
            gd += pgd;
            double pp = ppr == 0 ? (pgd == 0 ? 1 : 0) : ptp / ppr, rr = pgd == 0 ? (ppr == 0 ? 1 : 0) : ptp / pgd;
            sumF += pp + rr == 0 ? 0 : 2 * pp * rr / (pp + rr);
        }
        double mp = pr == 0 ? 0 : tp / pr, mr = gd == 0 ? 0 : tp / gd;
        return new double[]{mp + mr == 0 ? 0 : 2 * mp * mr / (mp + mr), sumF / pages.size()};
    }

    public static void run(RawStore store, Path goldFile, Path out, CssRules rules, Extractor.Strategy base,
                           double l2, int epochs, double lr) throws Exception {
        List<Gold> gold = BlockEval.loadGold(goldFile);
        List<PageData> pages = collect(store, gold, rules, base);
        System.out.println("Trang huấn luyện: " + pages.size() + ", khối: " + pages.stream().mapToInt(p -> p.x.size()).sum());
        // kiểm định chéo 5 phần
        int k = 5;
        double mf = 0, af = 0;
        for (int f = 0; f < k; f++) {
            List<PageData> tr = new ArrayList<>(), te = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) (i % k == f ? te : tr).add(pages.get(i));
            LogReg m = fit(tr, l2, epochs, lr);
            double[] s = score(m, te);
            mf += s[0];
            af += s[1];
        }
        System.out.printf(Locale.ROOT, "CV%d: F1 micro=%.3f, F1 macro=%.3f (l2=%.3f, epochs=%d, lr=%.2f)%n", k, mf / k, af / k, l2, epochs, lr);
        LogReg full = fit(pages, l2, epochs, lr);
        double[] s = score(full, pages);
        System.out.printf(Locale.ROOT, "Huấn luyện: F1 micro=%.3f, F1 macro=%.3f%n", s[0], s[1]);
        full.save(out);
        System.out.println("Đã lưu mô hình -> " + out);
        for (int j = 0; j < full.w.length; j++) System.out.printf(Locale.ROOT, "  %-18s %+.3f%n", full.names[j], full.w[j]);
    }
}
