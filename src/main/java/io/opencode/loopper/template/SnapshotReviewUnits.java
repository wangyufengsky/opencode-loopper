package io.opencode.loopper.template;

import java.util.*;
import java.util.regex.Pattern;

/** Stable text units, with hunk/function hints and explicit continuation locations. No language call graph is inferred. */
public final class SnapshotReviewUnits {
    private static final int CAPACITY = 23000;
    private static final Pattern ENTRY = Pattern.compile("^(?:\\s*(?:public|protected|private|static|export|async|final)\\s+)*(?:class|interface|record|enum|function|def|async def|func)\\s+.*|^\\s*(?:public|protected|private)\\s+[^=;]+\\([^;]*\\)\\s*(?:throws[^{}]*)?\\{.*");
    private SnapshotReviewUnits() { }
    public static List<SnapshotReview.Unit> compile(String id, String[] change, String source, String limitation) {
        return compile(id, change, source, limitation, false);
    }
    public static List<SnapshotReview.Unit> compact(String id, String[] change, String source, String limitation) {
        return compile(id, change, source, limitation, true);
    }
    private static List<SnapshotReview.Unit> compile(String id, String[] change, String source, String limitation, boolean compact) {
        List<SnapshotReview.Unit> result = new ArrayList<>();
        String[] lines = source.split("(?<=\\n)");
        StringBuilder chunk = new StringBuilder();
        String hunk = ""; int start = 1, line = 1;
        for (String value : lines) {
            boolean diff = !change[0].equals("FULL");
            boolean boundary = !compact && (diff ? value.startsWith("@@ ") : ENTRY.matcher(value.stripTrailing()).matches());
            if (!chunk.isEmpty() && (boundary || chunk.length() + value.length() > CAPACITY)) {
                result.add(unit(id, result.size(), change, chunk.toString(), limitation, start, line - 1, hunk)); chunk.setLength(0); start = line;
            }
            if (diff && value.startsWith("@@ ")) hunk = value.strip();
            if (value.length() > CAPACITY) {
                for (int offset = 0; offset < value.length(); offset += CAPACITY)
                    result.add(unit(id, result.size(), change, value.substring(offset, Math.min(value.length(), offset + CAPACITY)),
                            "超长行按字符分段，须结合相邻单元检查完整行为", line, line, hunk));
                start = line + 1;
            } else chunk.append(value);
            line++;
        }
        if (!chunk.isEmpty() || result.isEmpty()) result.add(unit(id, result.size(), change, chunk.toString(), limitation, start, Math.max(start, line - 1), hunk));
        return List.copyOf(result);
    }
    private static SnapshotReview.Unit unit(String id, int part, String[] change, String content, String limitation, int first, int last, String hunk) {
        String location = change[0].equals("FULL") ? "目标文件行 " + first + "–" + last : "差异材料行 " + first + "–" + last + "；所属 hunk：" + hunk;
        String excerpt = content.isEmpty() ? "" : "文件：" + change[2] + "；" + location + "\n" + content;
        return new SnapshotReview.Unit(id + ":" + part, change[2], change[1], change[0], excerpt, limitation);
    }
}
