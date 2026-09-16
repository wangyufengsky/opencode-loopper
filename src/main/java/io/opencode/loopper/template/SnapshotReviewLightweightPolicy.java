package io.opencode.loopper.template;

import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;

/** Capacity-based execution batches, independent of semantic function counts or model planning. */
public final class SnapshotReviewLightweightPolicy {
    public static final int MAX_CHARACTERS = 48000;
    public static final int MAX_UNITS = 64;
    private SnapshotReviewLightweightPolicy() { }

    public static boolean applies(String version) { return "2".equals(version); }

    public static Plan plan(List<Unit> units) {
        List<Group> groups = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        int size = 0;
        // Stable path order keeps nearby files together; source order within each file is preserved.
        for (var unit : units.stream().filter(u -> u.limitation() == null || !u.excerpt().isBlank()).sorted(Comparator.comparing(Unit::path)).toList()) {
            if (!current.isEmpty() && (size + unit.excerpt().length() > MAX_CHARACTERS || current.size() == MAX_UNITS)) {
                groups.add(group(groups.size(), current)); current.clear(); size = 0;
            }
            if (unit.excerpt().length() > MAX_CHARACTERS) throw new IllegalArgumentException("单元超过轻量审查容量");
            current.add(unit); size += unit.excerpt().length();
        }
        if (!current.isEmpty()) groups.add(group(groups.size(), current));
        return new Plan(List.copyOf(groups), List.of());
    }

    private static Group group(int ordinal, List<Unit> units) {
        var paths = units.stream().map(Unit::path).distinct().toList();
        return new Group(units.getFirst().id(), "代码分析 " + (ordinal + 1),
                "检查本批代码的具体缺陷，按需补读调用方、被调用方、配置和测试；关联检查在本会话完成",
                units.stream().map(Unit::id).toList(), paths);
    }

    public static Input analysis(List<Unit> units, Group group) {
        var ids = new HashSet<>(group.unitIds());
        return new Input("ANALYSIS", units.stream().filter(u -> ids.contains(u.id())).toList(), List.of(group), List.of(),
                List.of(), group.title() + "：" + group.objective(), null, SnapshotReview.LIGHTWEIGHT);
    }

    public static Input review(String batchId, Input source, Analysis analysis) {
        Set<String> paths = new HashSet<>();
        analysis.findings().forEach(f -> f.evidence().forEach(r -> paths.add(r.path())));
        var units = source.units().stream().filter(u -> paths.contains(u.path()) || paths.contains(u.beforePath())).toList();
        return new Input("REVIEW", units, List.of(), List.of(), List.of(batchId),
                "问题复核：" + source.objective(), batchId, SnapshotReview.LIGHTWEIGHT);
    }
}
