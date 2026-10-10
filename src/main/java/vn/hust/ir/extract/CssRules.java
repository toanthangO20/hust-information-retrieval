package vn.hust.ir.extract;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Quy tắc CSS theo host (Ch10: xử lý thủ công trên DOM với XPath/CSS). Tệp TSV: {@code host-regex<TAB>chọn1;;chọn2<TAB>loại1;;loại2}.
 * Quy tắc khớp đầu tiên theo thứ tự trong tệp được dùng; dòng bắt đầu bằng # là chú thích.
 */
public final class CssRules {
    private static final class Rule {
        Pattern host;
        List<String> select = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
    }

    private final List<Rule> rules = new ArrayList<>();

    public static CssRules load(Path file) throws Exception {
        CssRules r = new CssRules();
        if (file != null && Files.exists(file)) r.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        else try (InputStream in = CssRules.class.getResourceAsStream("/extract/css_rules.tsv")) {
            if (in != null) {
                List<String> lines = new ArrayList<>();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) lines.add(l);
                }
                r.parse(lines);
            }
        }
        return r;
    }

    private void parse(List<String> lines) {
        for (String l : lines) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] p = l.split("\t", -1);
            if (p.length < 2) continue;
            Rule r = new Rule();
            r.host = Pattern.compile(p[0].trim());
            for (String s : p[1].split(";;")) if (!s.isBlank()) r.select.add(s.trim());
            if (p.length > 2) for (String s : p[2].split(";;")) if (!s.isBlank()) r.exclude.add(s.trim());
            rules.add(r);
        }
    }

    /** Bộ tìm khối theo quy tắc của host, hoặc null nếu host không có quy tắc. */
    public BlockFinder.Css forHost(String host) {
        for (Rule r : rules)
            if (r.host.matcher(host).matches()) return new BlockFinder.Css(r.select, r.exclude, 20);
        return null;
    }
}
