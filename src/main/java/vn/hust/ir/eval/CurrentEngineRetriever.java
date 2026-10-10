package vn.hust.ir.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import vn.hust.ir.search.SearchEngine;

/** M0 (tham chiếu) — công cụ tìm kiếm đang chạy của ứng dụng: BM25 + thưởng cụm từ/đúng dấu + nới AND->OR. */
public final class CurrentEngineRetriever implements Retriever {
    private final SearchEngine engine;

    public CurrentEngineRetriever(SearchEngine engine) {
        this.engine = engine;
    }

    @Override
    public List<Scored> search(String query, int k) throws IOException {
        List<Scored> out = new ArrayList<>();
        for (int page = 1; out.size() < k; page++) {
            SearchEngine.Result r = engine.search(query, null, null, page, 50);
            for (SearchEngine.Hit h : r.hits) out.add(new Scored(h.id, h.score));
            if (r.hits.size() < 50) break;
        }
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }
}
