package io.opencode.loopper.service;

import static io.opencode.loopper.service.SnapshotReviewReads.invalid;
import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Phase-specific candidate compiler. Models own semantic claims, not source identity or coverage scope. */
@Service
public class SnapshotReviewProtocol {
    private final ObjectMapper json;
    private final SnapshotReviewReads reads;
    private final SnapshotReviewStore store;
    private final TemplateTaskMapper batches;
    public SnapshotReviewProtocol(ObjectMapper json, SnapshotReviewReads reads, SnapshotReviewStore store, TemplateTaskMapper batches) {
        this.json = json; this.reads = reads; this.store = store; this.batches = batches;
    }
    public String prompt(TemplateTaskBatchRow row) {
        var input = input(row); var snapshot = store.snapshot(row.taskId());
        String introduction = RolePromptResources.read("snapshot.review.standard");
        if (input.compact()) introduction = RolePromptResources.read("snapshot.review.compact");
        return introduction + "\n目标版本：" + snapshot.targetSha() + "\n基线版本：" + Objects.toString(snapshot.baselineSha(), "无")
                + (input.lightweight() ? RolePromptResources.read("snapshot.review.lightweight") : "")
                + "\n本批目标：" + input.objective() + "\n阶段：" + input.phase() + "\n候选结构：\n" + shape(input.phase())
                + "\n冻结本批证据：" + json.writeValueAsString(new Input(input.phase(), promptUnits(input), input.groups(), input.relations(),
                        input.dependencies().stream().limit(50).toList(), input.objective(), input.analysisBatchId(), input.policy()))
                + RolePromptResources.read("snapshot.review.more-dependencies");
    }
    private List<Unit> promptUnits(Input input) {
        if (!input.compact()) return input.units();
        return input.units().stream().map(u -> new Unit(u.id(), u.path(), u.beforePath(), u.change(), u.excerpt(), u.limitation(),
                u.initialEvidence().stream().map(r -> new Reference(r.version(), r.path(), r.blob(), r.startLine(), r.endLine(), "见 excerpt 原文")).toList())).toList();
    }
    public String validate(TemplateTaskBatchRow row, String body) {
        if (body == null || body.length() > 200000) throw invalid("候选为空或超出本批容量");
        var input = input(row);
        try {
            Object candidate = switch (input.phase()) {
                case "PLAN" -> plan(input, parse(body, Plan.class), false, row.taskId());
                case "LINKS" -> plan(input, parse(body, Plan.class), true, row.taskId());
                case "ANALYSIS" -> analysis(row, input, parse(body, Analysis.class));
                case "REVIEW", "RELATION_REVIEW" -> review(row, input, parse(body, Review.class));
                default -> throw invalid("未知审查阶段");
            };
            return json.writeValueAsString(candidate);
        } catch (BadRequestException failure) { throw failure; }
        catch (IllegalArgumentException failure) { throw invalid(failure.getMessage()); }
        catch (RuntimeException failure) { throw invalid("候选字段、引用或枚举无效，请查询提交合同并修正"); }
    }
    private Plan plan(Input input, Plan candidate, boolean links, String taskId) {
        if (candidate.groups() == null || candidate.relations() == null) throw invalid("规划必须包含 groups 与 relations");
        Set<String> expected = ids(input.units()), assigned = new HashSet<>(), groups = new HashSet<>();
        if (links) {
            if (!candidate.groups().isEmpty()) throw invalid("衔接规划不能重写主要分组");
            store.plan(taskId).groups().forEach(g -> groups.add(g.key()));
        }
        for (Group group : candidate.groups()) {
            group(group, expected);
            if (!groups.add(group.key())) throw invalid("功能组编号重复");
            for (String id : group.unitIds()) if (!assigned.add(id)) throw invalid("每个必审单元只能有一个主要归属");
            contextPaths(taskId, group.contextPaths());
            long size = input.units().stream().filter(u -> group.unitIds().contains(u.id())).mapToLong(u -> u.excerpt().length()).sum();
            if (size > 48000) throw invalid("功能组超过初始证据容量，请按子行为拆分并保留关系");
        }
        if (!links && !assigned.equals(expected)) throw invalid("规划遗漏必审单元，不允许静默排除");
        Set<String> relationIds = new HashSet<>();
        for (Relation relation : candidate.relations()) {
            text(relation.key(), 200); text(relation.question(), 2000);
            if (!relationIds.add(relation.key()) || !groups.contains(relation.fromGroup()) || !groups.contains(relation.toGroup()))
                throw invalid("跨组关系必须引用真实分组且编号唯一");
            if (links && input.groups().stream().noneMatch(g -> g.key().equals(relation.fromGroup())))
                throw invalid("衔接批次只能声明其负责分组的出向关系");
        }
        return candidate;
    }
    private Analysis analysis(TemplateTaskBatchRow row, Input input, Analysis candidate) {
        return io.opencode.loopper.template.SnapshotReviewClaims.analysis(input, candidate,
                (refs, target) -> reads.evidence(row.id(), refs, target), paths -> contextPaths(row.taskId(), paths));
    }
    private Review review(TemplateTaskBatchRow row, Input input, Review candidate) {
        List<Finding> findings = List.of();
        if (input.analysisBatchId() != null) {
            var source = batches.findBatch(input.analysisBatchId()).orElseThrow();
            if (!source.taskId().equals(row.taskId()) || !source.state().equals("VALIDATED")) throw invalid("分析依赖身份无效");
            findings = parse(source.outputJson(), Analysis.class).findings();
        }
        return io.opencode.loopper.template.SnapshotReviewClaims.review(input, candidate, findings,
                (refs, target) -> reads.evidence(row.id(), refs, target), identity -> externalDuplicate(row, identity));
    }
    private boolean externalDuplicate(TemplateTaskBatchRow caller, String identity) {
        int split = identity.indexOf('/');
        if (split <= 0) return false;
        var source = batches.findBatch(identity.substring(0, split)).orElse(null);
        if (source == null || !source.taskId().equals(caller.taskId()) || !source.state().equals("VALIDATED")
                || !Set.of("SNAPSHOT_REVIEW", "SNAPSHOT_RELATION_REVIEW").contains(source.purpose())) return false;
        return parse(source.outputJson(), Review.class).decisions().stream()
                .anyMatch(d -> d.findingKey().equals(identity.substring(split + 1)) && d.verdict() == Verdict.SUPPORTED);
    }
    private void contextPaths(String taskId, List<String> paths) {
        Set<String> known = store.snapshot(taskId).files().stream().map(File::path).collect(Collectors.toSet());
        if (paths == null || paths.size() > 100 || new HashSet<>(paths).size() != paths.size() || !known.containsAll(paths)) throw invalid("上下文路径必须属于冻结代码目录");
    }
    private static void group(Group group, Set<String> known) {
        text(group.key(), 200); text(group.title(), 300); text(group.objective(), 4000);
        if (group.unitIds() == null || group.unitIds().isEmpty() || !known.containsAll(group.unitIds())
                || new HashSet<>(group.unitIds()).size() != group.unitIds().size()) throw invalid("功能组须引用本批真实单元");
        if (!group.key().equals(group.unitIds().getFirst())) throw invalid("功能组 key 必须等于首个 unitId，避免跨批次编号冲突");
    }
    private <T> T parse(String value, Class<T> type) { return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value); }
    private static Set<String> ids(List<Unit> units) { return units.stream().map(Unit::id).collect(Collectors.toSet()); }
    private static void limitations(List<String> items) { if (items == null || items.size() > 64) throw invalid("局限列表无效"); items.forEach(value -> text(value, 2000)); }
    private static void text(String text, int maximum) { if (text == null || text.isBlank() || text.length() > maximum) throw invalid("文本字段为空或超过上限"); }
    private Input input(TemplateTaskBatchRow row) { return json.readValue(row.inputJson(), TemplateBatchExecution.Input.class).snapshot(); }
    public static String shape(String phase) { return io.opencode.loopper.template.SnapshotReviewClaims.shape(phase); }
}
