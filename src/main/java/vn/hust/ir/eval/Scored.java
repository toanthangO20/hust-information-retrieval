package vn.hust.ir.eval;

/** Một tài liệu (mã `id` trong dataset) kèm điểm xếp hạng. */
public final class Scored {
    public final int id;
    public final float score;

    public Scored(int id, float score) {
        this.id = id;
        this.score = score;
    }
}
