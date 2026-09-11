package io.opencode.loopper.template;

import io.opencode.loopper.template.TemplateAnalysis.Side;
import io.opencode.loopper.template.TemplateAnalysis.SourceLine;
import io.opencode.loopper.template.TemplateAnalysis.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Lossless bounded model input. Each hunk line carries an address in its frozen before/after blob. */
public final class TemplateAnalysisPartitioner {
    private static final int UNIT_LIMIT = 24_000;
    private static final int BATCH_LIMIT = 48_000;
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    private TemplateAnalysisPartitioner() { }

    public static List<Unit> units(TemplateGitEvidence evidence) {
        List<Unit> result = new ArrayList<>();
        for (var commit : evidence.commits()) {
            if (commit.changes().isEmpty()) {
                result.add(new Unit(commit.sha() + ":empty", commit.sha(), commit.sha(), "", commit.disposition(), "", List.of()));
            }
            for (var change : commit.changes()) split(commit.sha(), change, result);
        }
        return List.copyOf(result);
    }

    public static List<List<Unit>> batches(List<Unit> units) {
        List<List<Unit>> result = new ArrayList<>();
        List<Unit> pending = new ArrayList<>();
        int size = 0;
        for (Unit unit : units) {
            if (!pending.isEmpty() && (size + unit.patch().length() > BATCH_LIMIT || pending.size() == 12)) {
                result.add(List.copyOf(pending)); pending.clear(); size = 0;
            }
            pending.add(unit); size += unit.patch().length();
        }
        if (!pending.isEmpty()) result.add(List.copyOf(pending));
        return List.copyOf(result);
    }

    private static void split(String sha, TemplateGitEvidence.Change change, List<Unit> target) {
        String patch = change.patch();
        List<LocatedText> lines = locate(patch);
        StringBuilder text = new StringBuilder();
        List<SourceLine> addresses = new ArrayList<>();
        int part = 0;
        for (LocatedText line : lines) {
            for (int offset = 0; offset < line.text().length();) {
                int length = Math.min(UNIT_LIMIT - text.length(), line.text().length() - offset);
                text.append(line.text(), offset, offset + length);
                addresses.addAll(line.addresses());
                offset += length;
                if (text.length() == UNIT_LIMIT) {
                    target.add(unit(sha, change, part++, text.toString(), addresses)); text.setLength(0); addresses.clear();
                }
            }
        }
        if (!text.isEmpty() || part == 0) target.add(unit(sha, change, part, text.toString(), addresses));
    }

    private static Unit unit(String sha, TemplateGitEvidence.Change change, int part, String text, List<SourceLine> lines) {
        return new Unit(change.evidenceId() + ":" + part, sha, change.evidenceId(), change.path(),
                change.exclusionReason() == null ? "ANALYZE" : change.exclusionReason(), text, lines.stream().distinct().toList());
    }

    private static List<LocatedText> locate(String patch) {
        List<LocatedText> result = new ArrayList<>();
        int before = 0, after = 0;
        boolean inHunk = false;
        for (String text : patch.split("(?<=\n)")) {
            var hunk = HUNK.matcher(text.stripTrailing());
            List<SourceLine> addresses = new ArrayList<>();
            if (hunk.matches()) {
                before = Integer.parseInt(hunk.group(1)); after = Integer.parseInt(hunk.group(2)); inHunk = true;
            } else if (inHunk && text.startsWith("-")) addresses.add(new SourceLine(Side.BEFORE, before++));
            else if (inHunk && text.startsWith("+")) addresses.add(new SourceLine(Side.AFTER, after++));
            else if (inHunk && text.startsWith(" ")) {
                addresses.add(new SourceLine(Side.BEFORE, before++)); addresses.add(new SourceLine(Side.AFTER, after++));
            }
            result.add(new LocatedText(text, List.copyOf(addresses)));
        }
        return result;
    }

    private record LocatedText(String text, List<SourceLine> addresses) { }
}
