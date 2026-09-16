package io.opencode.loopper.template;

import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import java.util.regex.Pattern;

/** Maps only complete lines actually delivered in each frozen excerpt to their source positions. */
public final class SnapshotReviewInitialEvidence {
    private static final Pattern LOCATION = Pattern.compile("(?:目标文件行|差异材料行) (\\d+)–(\\d+)");
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    private SnapshotReviewInitialEvidence() { }

    public static List<Unit> compile(List<Unit> units, String source, File before, File after) {
        String[] lines = source.split("\n", -1);
        List<Located> positions = positions(lines, units.getFirst().change().equals("FULL"));
        return units.stream().map(unit -> attach(unit, lines, positions, before, after)).toList();
    }

    /** Validate mappings against original blobs, including rename/path-filter cases containing more than one diff. */
    public static List<Unit> verified(List<Unit> units, Map<String, String> blobs) {
        return units.stream().map(u -> new Unit(u.id(), u.path(), u.beforePath(), u.change(), u.excerpt(), u.limitation(),
                u.initialEvidence().stream().filter(r -> {
                    String content = blobs.get(r.blob());
                    if (content == null) return false;
                    String[] lines = content.split("\n", -1);
                    return r.startLine() > 0 && r.endLine() <= lines.length
                            && String.join("\n", Arrays.copyOfRange(lines, r.startLine() - 1, r.endLine())).equals(r.quote());
                }).toList())).toList();
    }

    private static Unit attach(Unit unit, String[] lines, List<Located> positions, File before, File after) {
        List<Reference> references = new ArrayList<>();
        int header = unit.excerpt().indexOf('\n');
        var location = LOCATION.matcher(header < 0 ? "" : unit.excerpt().substring(0, header));
        if (location.find() && !"超长行按字符分段，须结合相邻单元检查完整行为".equals(unit.limitation())) {
            int first = Integer.parseInt(location.group(1)), last = Integer.parseInt(location.group(2));
            if (first > 0 && last <= lines.length) {
                collect(references, positions, first, last, before, false);
                collect(references, positions, first, last, after, true);
            }
        }
        return new Unit(unit.id(), unit.path(), unit.beforePath(), unit.change(), unit.excerpt(), unit.limitation(), List.copyOf(references));
    }

    private static void collect(List<Reference> result, List<Located> positions, int first, int last, File file, boolean target) {
        if (file == null || file.limitation() != null) return;
        int start = 0, end = 0; List<String> body = new ArrayList<>();
        for (Located line : positions) {
            if (line.material() < first || line.material() > last) continue;
            int number = target ? line.after() : line.before();
            if (number == 0) continue;
            if (start != 0 && number != end + 1) { add(result, file, start, end, body); body.clear(); start = 0; }
            if (start == 0) start = number;
            end = number; body.add(line.text());
        }
        if (start != 0) add(result, file, start, end, body);
    }

    private static void add(List<Reference> result, File file, int start, int end, List<String> body) {
        result.add(new Reference(file.version(), file.path(), file.blob(), start, end, String.join("\n", body)));
    }

    private static List<Located> positions(String[] lines, boolean full) {
        List<Located> result = new ArrayList<>(); int before = 0, after = 0; boolean hunk = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (full) { result.add(new Located(i + 1, 0, i + 1, line)); continue; }
            var match = HUNK.matcher(line);
            if (match.matches()) { before = Integer.parseInt(match.group(1)); after = Integer.parseInt(match.group(2)); hunk = true; continue; }
            if (!hunk || line.isEmpty() || line.startsWith("\\")) continue;
            char prefix = line.charAt(0);
            if (prefix == ' ') result.add(new Located(i + 1, before++, after++, line.substring(1)));
            else if (prefix == '+') result.add(new Located(i + 1, 0, after++, line.substring(1)));
            else if (prefix == '-') result.add(new Located(i + 1, before++, 0, line.substring(1)));
        }
        return result;
    }
    private record Located(int material, int before, int after, String text) { }
}
