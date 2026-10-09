package vn.hust.ir.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Xuất kết quả:
 *  - urls_valid.txt: các URL đạt yêu cầu (có nội dung), mỗi dòng một URL;
 *  - urls_invalid.tsv: URL bị loại kèm lý do;
 *  - updates/urls_added_*.txt, urls_removed_*.txt: chênh lệch so với lần xuất trước (cơ chế cập nhật);
 *  - update_log.tsv: nhật ký từng lần chạy.
 */
public final class Exporter {
    private Exporter() {}

    public static void export(Map<String, UrlRecord> records, Path outDir) throws IOException {
        Files.createDirectories(outDir.resolve("updates"));
        Path validFile = outDir.resolve("urls_valid.txt");

        TreeSet<String> valid = new TreeSet<>();
        List<UrlRecord> invalid = new ArrayList<>();
        int pending = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (UrlRecord r : records.values()) {
            if (r.state == UrlRecord.VALID) valid.add(r.url);
            else if (r.state == UrlRecord.INVALID) {
                invalid.add(r);
                String key = r.reason.contains(":") && !r.reason.startsWith("error") && !r.reason.startsWith("http")
                        ? r.reason.substring(0, r.reason.indexOf(':')) : r.reason;
                reasons.merge(key, 1, Integer::sum);
            } else pending++;
        }
        invalid.sort((a, b) -> a.url.compareTo(b.url));

        TreeSet<String> previous = new TreeSet<>();
        boolean hadPrevious = Files.exists(validFile);
        if (hadPrevious) previous.addAll(Files.readAllLines(validFile, StandardCharsets.UTF_8));

        TreeSet<String> added = new TreeSet<>(valid);
        added.removeAll(previous);
        TreeSet<String> removed = new TreeSet<>(previous);
        removed.removeAll(valid);

        writeAtomic(validFile, new ArrayList<>(valid));
        List<String> inv = new ArrayList<>();
        inv.add("url\thttp_status\treason");
        for (UrlRecord r : invalid) inv.add(r.url + "\t" + r.httpStatus + "\t" + r.reason);
        writeAtomic(outDir.resolve("urls_invalid.tsv"), inv);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        if (!added.isEmpty()) writeAtomic(outDir.resolve("updates").resolve("urls_added_" + ts + ".txt"), new ArrayList<>(added));
        if (!removed.isEmpty()) writeAtomic(outDir.resolve("updates").resolve("urls_removed_" + ts + ".txt"), new ArrayList<>(removed));

        Path log = outDir.resolve("update_log.tsv");
        if (!Files.exists(log))
            Files.write(log, Collections.singletonList("time\tvalid\tinvalid\tpending\tadded\tremoved"), StandardCharsets.UTF_8);
        Files.write(log, Collections.singletonList(String.join("\t", ts, String.valueOf(valid.size()),
                String.valueOf(invalid.size()), String.valueOf(pending), String.valueOf(added.size()),
                String.valueOf(removed.size()))), StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Crawler.log("Xuất %s: %d URL hợp lệ (+%d mới, -%d bị loại so với lần trước); %d URL không hợp lệ; %d đang chờ",
                validFile, valid.size(), added.size(), removed.size(), invalid.size(), pending);
        Crawler.log("Lý do loại: %s", reasons);
    }

    private static void writeAtomic(Path target, List<String> lines) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        String body = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";   // LF cố định, không phụ thuộc hệ điều hành
        Files.write(tmp, body.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
