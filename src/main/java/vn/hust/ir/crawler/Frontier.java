package vn.hust.ir.crawler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Hàng đợi URL (URL frontier) theo kiến trúc Mercator (Chương 10):
 * - hàng đợi phía trước: 3 mức ưu tiên (0 cao nhất) cho mỗi host;
 * - hàng đợi phía sau: mỗi host một hàng đợi, và một heap theo thời điểm te (thời điểm sớm nhất
 *   được phép gửi request kế tiếp tới host) để đảm bảo tính lịch thiệp;
 * - mỗi host chỉ có tối đa một request đang xử lý tại một thời điểm.
 */
public final class Frontier {
    public static final class Task {
        public final String url;
        public final String host;
        public final int priority;

        Task(String url, String host, int priority) {
            this.url = url;
            this.host = host;
            this.priority = priority;
        }
    }

    private static final class HostQ {
        final String host;
        @SuppressWarnings("unchecked")
        final Deque<Task>[] q = new Deque[3];
        long nextAt;
        boolean inFlight;
        boolean inHeap;
        int size;

        HostQ(String host) {
            this.host = host;
            for (int i = 0; i < q.length; i++) q[i] = new ArrayDeque<>();
        }
    }

    private final Map<String, HostQ> hosts = new HashMap<>();
    private final PriorityQueue<HostQ> heap = new PriorityQueue<>((a, b) -> Long.compare(a.nextAt, b.nextAt));
    private final Set<String> queued = new HashSet<>();
    private int inFlight;
    private int total;
    private boolean closed;

    public synchronized boolean add(String url, int priority) {
        if (closed || !queued.add(url)) return false;
        String host = UrlNormalizer.host(url);
        HostQ h = hosts.computeIfAbsent(host, HostQ::new);
        int p = Math.max(0, Math.min(2, priority));
        h.q[p].addLast(new Task(url, host, p));
        h.size++;
        total++;
        if (!h.inFlight && !h.inHeap) {
            h.inHeap = true;
            heap.add(h);
        }
        notifyAll();
        return true;
    }

    /** Lấy URL kế tiếp; chặn cho tới khi có host đủ điều kiện. Trả về null khi hết việc hoặc đã đóng. */
    public synchronized Task take() throws InterruptedException {
        while (true) {
            if (closed) return null;
            HostQ h = heap.peek();
            if (h == null) {
                if (inFlight == 0) {
                    notifyAll();
                    return null;
                }
                wait();
                continue;
            }
            long now = System.currentTimeMillis();
            if (h.nextAt > now) {
                wait(h.nextAt - now);
                continue;
            }
            heap.poll();
            h.inHeap = false;
            Task t = null;
            for (Deque<Task> d : h.q) {
                if (!d.isEmpty()) {
                    t = d.pollFirst();
                    break;
                }
            }
            h.size--;
            total--;
            h.inFlight = true;
            inFlight++;
            queued.remove(t.url);
            return t;
        }
    }

    /** Báo đã xử lý xong; host chỉ được lấy tiếp sau delayMs. */
    public synchronized void release(Task t, long delayMs) {
        HostQ h = hosts.get(t.host);
        h.inFlight = false;
        inFlight--;
        h.nextAt = System.currentTimeMillis() + delayMs;
        if (h.size > 0 && !closed) {
            h.inHeap = true;
            heap.add(h);
        }
        notifyAll();
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized int size() {
        return total;
    }
}
