package io.opencode.loopper.template;

import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import java.util.stream.Collectors;

/** Pure candidate rules shared by frozen template batches and configurable workflow nodes. */
public final class SnapshotReviewClaims {
    private SnapshotReviewClaims() { }
    @FunctionalInterface public interface Evidence { void require(List<Reference> references, boolean targetRequired); }
    public static Analysis analysis(Input input, Analysis candidate, Evidence evidence, java.util.function.Consumer<List<String>> contextPaths) {
        if (candidate.coverage() == null || candidate.findings() == null || candidate.supplements() == null) throw invalid("分析结构不完整");
        if (input.lightweight() && !candidate.supplements().isEmpty())
            throw invalid("轻量审查不追加补充批次，请在当前会话补读关联代码，将未解决证据缺口写入 limitations，并将 supplements 设为空");
        limitations(candidate.limitations()); Set<String> covered = new HashSet<>(), expected = ids(input.units());
        for (Coverage coverage : candidate.coverage()) {
            if (!expected.contains(coverage.unitId()) || !covered.add(coverage.unitId())) throw invalid("分析包含重复或未知单元");
            text(coverage.conclusion(), input.compact() ? 160 : 4000); limitations(coverage.limitations());
            evidence.require( coverage.evidence(), false);
            var unit = input.units().stream().filter(u -> u.id().equals(coverage.unitId())).findFirst().orElseThrow();
            if ((!input.compact() || unit.initialEvidence().isEmpty()) && coverage.limitations().isEmpty() && coverage.evidence().stream().noneMatch(r -> r.path().equals(unit.path()) || r.path().equals(unit.beforePath())))
                throw invalid("单元结论需要该单元文件的实际代码证据或明确局限");
        }
        if (!covered.equals(expected)) throw invalid("分析遗漏本批必审单元");
        Set<String> keys = new HashSet<>();
        for (Finding finding : candidate.findings()) {
            text(finding.key(), 200); text(finding.title(), 300); text(finding.trigger(), 4000); text(finding.behavior(), 4000); text(finding.recommendation(), 2000);
            if (!keys.add(finding.key()) || finding.severity() == null || finding.attribution() == null) throw invalid("问题编号、级别或归因无效");
            evidence.require( finding.evidence(), true);
        }
        Set<String> supplementary = new HashSet<>();
        for (Group group : candidate.supplements()) {
            group(group, expected); contextPaths.accept(group.contextPaths());
            if (!supplementary.add(group.key())) throw invalid("补充批次编号重复");
            Set<String> existing = input.groups().stream().flatMap(g -> g.contextPaths().stream()).collect(Collectors.toSet());
            if (existing.containsAll(group.contextPaths())) throw invalid("补充计划必须包含新的冻结上下文路径；已有上下文请在本批补读");
            if (input.units().stream().filter(u -> group.unitIds().contains(u.id())).mapToLong(u -> u.excerpt().length()).sum() > 48000)
                throw invalid("补充批次超过容量，请拆分子行为");
        }
        return candidate;
    }
    public static Review review(Input input, Review candidate, List<Finding> findings, Evidence evidence, java.util.function.Predicate<String> externalDuplicate) {
        if (candidate.checkedUnitIds() == null || candidate.decisions() == null) throw invalid("复核结构不完整");
        var checked = new HashSet<>(candidate.checkedUnitIds());
        if (checked.size() != candidate.checkedUnitIds().size() || !checked.equals(ids(input.units()))) throw invalid("独立复核须完整覆盖分配单元");
        text(candidate.conclusion(), 6000); limitations(candidate.limitations());
        evidence.require( candidate.evidence(), false);
        if (!input.units().isEmpty() && candidate.evidence().isEmpty() && candidate.limitations().isEmpty()) throw invalid("复核必须独立读取或明确缺失证据");
        if (candidate.limitations().isEmpty()) for (Unit unit : input.units())
            if (candidate.evidence().stream().noneMatch(r -> r.path().equals(unit.path()) || r.path().equals(unit.beforePath())))
                throw invalid("无问题结论也须独立读取每个分配文件，无法读取时明确局限");
        Set<String> expected = new HashSet<>();
        findings.forEach(f -> expected.add(f.key()));
        Set<String> covered = new HashSet<>();
        for (Decision decision : candidate.decisions()) {
            if (!expected.contains(decision.findingKey()) || !covered.add(decision.findingKey()) || decision.verdict() == null) throw invalid("复核问题遗漏、重复或未知");
            text(decision.reason(), 4000);
            evidence.require( decision.evidence(), decision.verdict() == Verdict.SUPPORTED || decision.verdict() == Verdict.DISMISSED || decision.verdict() == Verdict.DUPLICATE);
            if (decision.verdict() == Verdict.DUPLICATE) {
                if (decision.duplicateOf() == null || decision.duplicateOf().equals(decision.findingKey()) || (!expected.contains(decision.duplicateOf()) && !externalDuplicate.test(decision.duplicateOf())))
                    throw invalid("重复问题必须关联本批不同问题，或已独立支持的其他复核批次ID/问题编号");
            } else if (decision.duplicateOf() != null) throw invalid("非重复结论不能携带合并目标");
        }
        if (!covered.equals(expected)) throw invalid("必须逐项复核全部候选问题");
        for (Decision decision : candidate.decisions()) if (decision.verdict() == Verdict.DUPLICATE && expected.contains(decision.duplicateOf())) {
            var target = candidate.decisions().stream().filter(d -> d.findingKey().equals(decision.duplicateOf())).findFirst().orElseThrow();
            if (target.verdict() != Verdict.SUPPORTED) throw invalid("重复问题只能合并到已支持问题，不能循环或隐藏待确认项");
        }
        return candidate;
    }
    public static void evidence(String target, List<Reference> refs, boolean targetRequired, java.util.function.Function<Reference,Optional<String>> contentFor) {
        boolean targetFound = false;
        if (refs == null || refs.size() > 64) throw invalid("证据数量无效");
        for (var ref : refs) {
            if (ref == null || ref.startLine() < 1 || ref.endLine() < ref.startLine() || ref.quote() == null || ref.quote().isBlank())
                throw invalid("引用缺少版本、位置或原文");
            var content = contentFor.apply(ref)
                    .orElseThrow(() -> invalid("引用必须来自本批初始证据或本角色实际读取的同版本同位置代码"));
            if (!content.contains(ref.quote())) throw invalid("引用原文与冻结读取内容不匹配");
            if (target.equals(ref.version())) targetFound = true;
        }
        if (targetRequired && !targetFound) throw invalid("当前缺陷必须引用目标版本代码证据");
    }
    public static Optional<String> initialContent(Input input, Reference ref) {
        if (!input.compact()) return Optional.empty();
        return input.units().stream().flatMap(u -> u.initialEvidence().stream())
                .filter(r -> Objects.equals(r.version(), ref.version()) && Objects.equals(r.path(), ref.path())
                        && Objects.equals(r.blob(), ref.blob()) && r.startLine() <= ref.startLine() && r.endLine() >= ref.endLine())
                .map(r -> String.join("\n", Arrays.copyOfRange(r.quote().split("\n", -1),
                        ref.startLine() - r.startLine(), ref.endLine() - r.startLine() + 1))).findFirst();
    }
    private static void group(Group group, Set<String> known) {
        text(group.key(), 200); text(group.title(), 300); text(group.objective(), 4000);
        if (group.unitIds() == null || group.unitIds().isEmpty() || !known.containsAll(group.unitIds())
                || new HashSet<>(group.unitIds()).size() != group.unitIds().size()) throw invalid("功能组须引用本批真实单元");
        if (!group.key().equals(group.unitIds().getFirst())) throw invalid("功能组 key 必须等于首个 unitId，避免跨批次编号冲突");
    }
    private static Set<String> ids(List<Unit> units) { return units.stream().map(Unit::id).collect(Collectors.toSet()); }
    private static void limitations(List<String> items) { if (items == null || items.size() > 64) throw invalid("局限列表无效"); items.forEach(value -> text(value, 2000)); }
    private static void text(String text, int maximum) { if (text == null || text.isBlank() || text.length() > maximum) throw invalid("文本字段为空或超过上限"); }
    public static String shape(String phase) {
        String reference = "reference={version:版本SHA,path:路径,blob:文件blob,startLine:起始行,endLine:结束行,quote:读取原文}";
        return switch (phase) {
            case "PLAN", "LINKS" -> "{groups:[{key:首个unitId,title:功能名,objective:检查目标,unitIds:[单元编号],contextPaths:[上下文路径]}],relations:[{key:关系编号,fromGroup:组key,toGroup:组key,question:衔接检查问题}]}。PLAN 必须覆盖全部单元；LINKS 的 groups 必须为空，只声明分配分组的出向关系，可调用 get_snapshot_review_groups 查看全部组。";
            case "ANALYSIS" -> "{coverage:[{unitId:编号,conclusion:检查结论,evidence:[reference],limitations:[]}],findings:[{key:本批问题编号,severity:CRITICAL|HIGH|MEDIUM|LOW,title:问题,trigger:支持输入触发条件,behavior:实际错误行为,recommendation:建议,attribution:CHANGE_RELATED|EXISTING|UNDETERMINED,evidence:[reference]}],supplements:[{key:首个unitId,title:补充主题,objective:所需补充检查,unitIds:[编号],contextPaths:[路径]}],limitations:[]}。" + reference;
            default -> "{checkedUnitIds:[本批全部单元编号],decisions:[{findingKey:原问题编号,verdict:SUPPORTED|UNDETERMINED|DISMISSED|DUPLICATE,reason:独立复核理由,duplicateOf:本批重复目标编号或其他已支持复核批次ID/问题编号或null,evidence:[reference]}],evidence:[reference],conclusion:独立复核结论,limitations:[]}。逐项复核依赖分析全部问题，不得添加未经分析的新问题，发现新疑点写 limitations。" + reference;
        };
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
