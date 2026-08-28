package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/** Parses controlled Designer Markdown into exact-source EARS/Gherkin-style facts. */
final class DesignerDesignFactExtractor {
    private static final int MAX_FACTS = 128;
    private static final int MAX_SCENARIOS = 64;
    private static final List<String> REQUIRED_CONTROLLED_SECTIONS = List.of(
            "目标与范围", "影响与交付", "验收场景", "验收约束", "阶段与依赖");
    private static final List<String> OPTIONAL_CONTROLLED_SECTIONS = List.of("可选人工评审项", "人工评审项");
    private final Parser parser = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final DesignerEvidenceIndexer evidenceIndexer;

    DesignerDesignFactExtractor(DesignerEvidenceIndexer evidenceIndexer) {
        this.evidenceIndexer = evidenceIndexer;
    }

    Catalog extract(String workPackageId, int designRevision, String markdown) {
        return extract(workPackageId, designRevision, markdown, CONTRACT_VERSION_V5);
    }

    Catalog extract(String workPackageId, int designRevision, String markdown, String contractVersion) {
        String source = markdown == null ? "" : markdown.replace("\r\n", "\n");
        Document root = (Document) parser.parse(source);
        DesignerEvidenceIndexer.Index lineIndex = evidenceIndexer.index(source);
        List<Fact> facts = new ArrayList<>();
        List<StageHint> stageHints = new ArrayList<>();
        Map<String, Integer> controlledSectionCounts = new LinkedHashMap<>();
        String section = "";
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) {
                section = text(heading).trim();
                if (REQUIRED_CONTROLLED_SECTIONS.contains(section)
                        || OPTIONAL_CONTROLLED_SECTIONS.contains(section)) {
                    controlledSectionCounts.merge(section, 1, Integer::sum);
                }
                continue;
            }
            if (node instanceof TableBlock table) {
                parseTable(section, table, source, lineIndex, facts, stageHints);
            } else if (section.contains("验收约束")) {
                String value = text(node).trim();
                if (!value.isEmpty()) {
                    String excerpt = sourceLine(source, List.of(value));
                    add(facts, FactKind.POLICY, "验收约束", null, null, null, null, value,
                            sourceRef(lineIndex, excerpt), excerpt);
                }
            }
        }
        boolean usesControlledSections = !controlledSectionCounts.isEmpty();
        if (usesControlledSections) validateControlledSections(controlledSectionCounts);
        boolean controlled = usesControlledSections;
        if (!controlled && facts.stream().noneMatch(fact -> fact.kind() == FactKind.SCENARIO)) {
            fallbackScenarios(root, source, lineIndex, facts);
        }
        int scenarios = (int) facts.stream().filter(fact -> fact.kind() == FactKind.SCENARIO).count();
        if (scenarios == 0) {
            throw new BadRequestException("MISSING_ACCEPTANCE_INTENT", "设计稿缺少可观察的验收场景");
        }
        if (scenarios > MAX_SCENARIOS || facts.size() > MAX_FACTS) {
            throw new BadRequestException("DESIGN_ACCEPTANCE_FACT_LIMIT_EXCEEDED",
                    "Acceptance design supports at most 64 scenarios and 128 total facts");
        }
        List<String> issues = controlled ? List.of() : List.of("LEGACY_MARKDOWN_FALLBACK");
        return new Catalog(contractVersion, workPackageId, designRevision, sha256(source), controlled,
                reindex(facts), List.copyOf(stageHints), issues);
    }

    private void parseTable(String section, TableBlock table, String source, DesignerEvidenceIndexer.Index lineIndex,
                            List<Fact> facts, List<StageHint> stageHints) {
        List<List<String>> rows = tableRows(table);
        if (rows.size() < 2) return;
        List<String> headers = rows.getFirst().stream().map(DesignerDesignFactExtractor::key).toList();
        for (List<String> row : rows.subList(1, rows.size())) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < Math.min(headers.size(), row.size()); index++) {
                values.put(headers.get(index), row.get(index).trim());
            }
            String excerpt = sourceLine(source, row);
            String ref = sourceRef(lineIndex, excerpt);
            if (section.contains("验收场景")) {
                add(facts, FactKind.SCENARIO, first(values, "场景", "scenario"),
                        first(values, "前置触发", "前置条件", "触发", "givenwhen"),
                        first(values, "操作", "when", "action"),
                        first(values, "可观察结果", "结果", "then", "expected"),
                        first(values, "保持不变", "不变量", "and", "invariant"), null, ref, excerpt);
            } else if (section.contains("人工评审项")) {
                add(facts, FactKind.REVIEW, first(values, "评审项", "review"), null, null, null, null,
                        first(values, "判断标准", "criteria") + "；仅人工原因："
                                + first(values, "仅人工原因", "reason"), ref, excerpt);
            } else if (section.contains("影响与交付")) {
                String type = first(values, "类型", "type");
                String target = first(values, "相对路径或符号", "路径或符号", "target");
                FactKind kind = type.contains("范围") ? FactKind.SCOPE : FactKind.DELIVERABLE;
                add(facts, kind, target, null, null, null, null,
                        type + "：" + first(values, "说明", "description"), ref, excerpt);
            } else if (section.contains("阶段与依赖")) {
                String title = first(values, "阶段建议", "阶段", "stage");
                String objective = first(values, "目标", "objective");
                String responsiblePaths = first(values, "负责路径", "责任路径", "ownedpaths", "paths");
                String included = first(values, "包含场景评审交付", "包含场景交付", "包含场景或交付", "包含场景", "scope");
                String dependencies = first(values, "前置阶段", "依赖", "depends");
                stageHints.add(new StageHint(title, objective, references(included),
                        noDependency(dependencies) ? List.of() : references(dependencies), List.of(), List.of(),
                        references(responsiblePaths)));
                add(facts, FactKind.DEPENDENCY, title, null, null, null, null,
                        "前置阶段：" + dependencies, ref, excerpt);
            }
        }
    }

    private void fallbackScenarios(Document root, String source, DesignerEvidenceIndexer.Index lineIndex,
                                   List<Fact> facts) {
        for (Node node = root.getFirstChild(); node != null && facts.size() < MAX_SCENARIOS; node = node.getNext()) {
            if (node instanceof Heading || node instanceof TableBlock || node instanceof FencedCodeBlock) continue;
            String value = text(node).trim();
            if (value.length() < 4 || engineeringMeta(value)) continue;
            String excerpt = sourceLine(source, List.of(value));
            add(facts, FactKind.SCENARIO, bounded(value, 120), "满足设计前置条件", "执行该业务路径",
                    value, "设计声明的其他输入与副作用不变", null, sourceRef(lineIndex, excerpt), excerpt);
        }
    }

    private void add(List<Fact> facts, FactKind kind, String title, String condition, String action,
                     String expected, String invariant, String detail, String ref, String excerpt) {
        if (blank(title) && blank(expected) && blank(detail)) return;
        String actualTitle = blank(title) ? (!blank(expected) ? expected : detail) : title;
        facts.add(new Fact(facts.size(), kind, bounded(actualTitle, 500), bounded(condition, 1_000),
                bounded(action, 1_000), bounded(expected, 2_000), bounded(invariant, 1_000),
                bounded(detail, 2_000), ref, bounded(excerpt, 4_000), sha256(excerpt)));
    }

    private static List<Fact> reindex(List<Fact> facts) {
        List<Fact> result = new ArrayList<>();
        for (Fact fact : facts) result.add(new Fact(result.size(), fact.kind(), fact.title(), fact.condition(),
                fact.action(), fact.expected(), fact.invariant(), fact.detail(), fact.sourceRef(),
                fact.sourceExcerpt(), fact.sourceSha256()));
        return List.copyOf(result);
    }

    private static List<List<String>> tableRows(TableBlock block) {
        List<List<String>> rows = new ArrayList<>();
        for (Node container = block.getFirstChild(); container != null; container = container.getNext()) {
            for (Node node = container.getFirstChild(); node != null; node = node.getNext()) {
                if (!(node instanceof TableRow row)) continue;
                List<String> cells = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell) cells.add(text(cell).trim());
                }
                if (!cells.isEmpty()) rows.add(List.copyOf(cells));
            }
        }
        return rows;
    }

    private static String text(Node node) {
        StringBuilder result = new StringBuilder();
        collectText(node, result);
        return result.toString().replaceAll("\\s+", " ").trim();
    }

    private static void collectText(Node node, StringBuilder output) {
        if (node instanceof Text value) output.append(value.getLiteral());
        else if (node instanceof Code value) output.append(value.getLiteral());
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) collectText(child, output);
    }

    private static String sourceLine(String source, List<String> cells) {
        List<String> meaningful = cells.stream().filter(value -> !blank(value)).toList();
        for (String raw : source.split("\n", -1)) {
            String line = raw.strip();
            String normalized = evidenceKey(line);
            if (!line.isEmpty() && meaningful.stream().map(DesignerDesignFactExtractor::evidenceKey)
                    .filter(value -> !value.isEmpty()).allMatch(normalized::contains)) return line;
        }
        for (String raw : source.split("\n", -1)) {
            String line = raw.strip();
            String normalized = evidenceKey(line);
            if (!line.isEmpty() && meaningful.stream().map(DesignerDesignFactExtractor::evidenceKey)
                    .filter(value -> value.length() >= 8)
                    .anyMatch(value -> normalized.contains(value) || value.contains(normalized))) return line;
        }
        return meaningful.isEmpty() ? "设计稿验收事实" : String.join(" | ", meaningful);
    }

    private static String evidenceKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
                .replaceAll("[^a-z0-9\\p{IsHan}]", "");
    }

    private static String sourceRef(DesignerEvidenceIndexer.Index index, String excerpt) {
        return index.lines().entrySet().stream()
                .filter(entry -> entry.getValue().equals(excerpt) || entry.getValue().contains(excerpt)
                        || excerpt.contains(entry.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(index.lines().keySet().stream().findFirst().orElse("DS-L001"));
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String requested : keys) {
            String value = values.get(key(requested));
            if (!blank(value)) return value;
        }
        return "";
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s/|（）()、_-]", "");
    }

    private static List<String> references(String value) {
        if (blank(value)) return List.of();
        return java.util.Arrays.stream(value.split("[；;]", -1)).map(String::trim)
                .filter(item -> !item.isBlank()).toList();
    }

    private static boolean noDependency(String value) {
        return blank(value) || Set.of("无", "none", "n/a").contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static void validateControlledSections(Map<String, Integer> counts) {
        List<String> missing = REQUIRED_CONTROLLED_SECTIONS.stream()
                .filter(section -> counts.getOrDefault(section, 0) == 0).toList();
        if (!missing.isEmpty()) {
            throw new BadRequestException("MISSING_CONTROLLED_DESIGN_SECTION",
                    "Controlled package design is missing required sections: " + missing);
        }
        List<String> duplicated = counts.entrySet().stream().filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey).toList();
        if (!duplicated.isEmpty()) {
            throw new BadRequestException("DUPLICATED_CONTROLLED_DESIGN_SECTION",
                    "Controlled package design repeats sections: " + duplicated);
        }
    }

    private static boolean engineeringMeta(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("阶段") || lower.contains("依赖顺序") || lower.contains("限制说明")
                || lower.contains("mvn ") || lower.contains("npm ") || lower.contains("pytest");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String bounded(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
