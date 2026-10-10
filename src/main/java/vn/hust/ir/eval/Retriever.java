package vn.hust.ir.eval;

import java.io.IOException;
import java.util.List;

/** Giao diện chung cho mọi mô hình truy vấn so sánh: nhận truy vấn, trả về top-k đã xếp hạng giảm dần theo điểm. */
public interface Retriever {
    List<Scored> search(String query, int k) throws IOException;
}
