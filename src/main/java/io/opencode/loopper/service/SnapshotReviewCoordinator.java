package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Version-oriented workflow. Legacy history orchestration never interprets these plans or candidates. */
@Service
public class SnapshotReviewCoordinator {
    private final SnapshotReviewStore store;
    private final SnapshotReviewEvidence evidence;
    private final TemplateTaskStateService states;
    private final TemplateRunEvidenceService contracts;
    private final SnapshotReviewBatches batches;
    private final LoopperMapper tasks;
    private final SnapshotReviewReports reports;
    private final SnapshotReviewLightweightCoordinator lightweight;
    public SnapshotReviewCoordinator(SnapshotReviewStore store, SnapshotReviewEvidence evidence, TemplateTaskStateService states,
            TemplateRunEvidenceService contracts, SnapshotReviewBatches batches, LoopperMapper tasks, SnapshotReviewReports reports, SnapshotReviewLightweightCoordinator lightweight) {
        this.store = store; this.evidence = evidence; this.states = states; this.contracts = contracts;
        this.batches = batches; this.tasks = tasks; this.reports = reports;
        this.lightweight = lightweight;
    }
    public void advance(String taskId) {
        var task = states.task(taskId);
        if (!task.state().equals("RUNNING")) return;
        var contract = contracts.contract(taskId);
        var cycle = tasks.activeTaskExecutionCycle(taskId).orElseThrow();
        if (contract.spec().limits().timeoutsEnabled() && Duration.between(Instant.parse(cycle.startedAt()), Instant.now()).toSeconds() > contract.spec().limits().maxDurationSeconds()) {
            states.waiting(taskId, "TEMPLATE_DURATION_EXHAUSTED", "已达到冻结审查时限，保留已完成结果"); return;
        }
        if (contract.spec().limits().timeoutsEnabled() && tasks.listAttempts(taskId).stream().anyMatch(a -> a.state().equals("RUNNING")
                && Duration.between(Instant.parse(a.createdAt()), Instant.now()).toSeconds() > contract.spec().limits().attemptTimeoutSeconds())) {
            states.waiting(taskId, "TEMPLATE_ATTEMPT_TIMEOUT", "审查阶段已达到冻结时限，保留已完成批次和版本"); return;
        }
        if (SnapshotReviewLightweightPolicy.applies(contract.definition().version())) { lightweight.advance(taskId, contract); return; }
        var stages = tasks.listStages(taskId);
        if (!stages.get(0).state().equals("SUCCEEDED")) {
            var attempt = states.attempt(taskId, 0); var snapshot = evidence.freeze(taskId);
            states.completeStage(attempt, "已冻结目标版本与 " + snapshot.units().size() + " 个审查单元"); return;
        }
        var snapshot = store.snapshot(taskId);
        if (!stages.get(1).state().equals("SUCCEEDED")) { plan(taskId, snapshot, states.attempt(taskId, 1), contract); return; }
        var analysisAttempt = stages.get(2).state().equals("SUCCEEDED") ? tasks.latestAttempt(stages.get(2).id()).orElseThrow() : states.attempt(taskId, 2);
        var reviewAttempt = states.attempt(taskId, 3);
        analyze(taskId, snapshot, analysisAttempt, reviewAttempt, contract);
    }
    private void plan(String taskId, Snapshot snapshot, AttemptRow attempt, TemplateTaskContractFactory.Frozen contract) {
        var slices = partition(snapshot.units()); var planning = new ArrayList<TemplateTaskBatchRow>();
        for (int i = 0; i < slices.size(); i++) planning.add(batches.create(attempt, i, "SNAPSHOT_PLAN",
                new Input("PLAN", slices.get(i), List.of(), List.of(), List.of(), "按行为与调用链组织本批必审单元；完整覆盖且标明组间关系", null)));
        if (!batches.advance(taskId, planning, contract, true)) return;
        var groups = new ArrayList<Group>(); var relations = new ArrayList<Relation>();
        for (var row : planning) {
            var result = batches.output(row, Plan.class); groups.addAll(result.groups());
            result.relations().forEach(r -> relations.add(new Relation(row.id() + ":" + r.key(), r.fromGroup(), r.toGroup(), r.question())));
        }
        Map<String, String> owner = new HashMap<>(); groups.forEach(g -> g.unitIds().forEach(id -> owner.put(id, g.key())));
        for (int i = 1; i < snapshot.units().size(); i++) {
            var before = snapshot.units().get(i - 1); var after = snapshot.units().get(i);
            if (before.path().equals(after.path()) && !owner.get(before.id()).equals(owner.get(after.id())))
                relations.add(new Relation("continuation-" + i, owner.get(before.id()), owner.get(after.id()),
                        "检查同文件相邻片段之间的完整函数、状态和接口约束：" + after.path()));
        }
        if (store.require(taskId).planJson() == null) store.plan(taskId, new Plan(List.copyOf(groups), List.copyOf(relations)), "主要功能分组冻结");
        var links = new ArrayList<TemplateTaskBatchRow>();
        for (int i = 0; i < groups.size(); i += 20) links.add(batches.create(attempt, i / 20, "SNAPSHOT_LINKS",
                new Input("LINKS", List.of(), List.copyOf(groups.subList(i, Math.min(i + 20, groups.size()))), List.of(),
                        planning.stream().map(TemplateTaskBatchRow::id).toList(), "读取全部功能组目录，检查分配组与其他组的接口、状态和公共依赖衔接，避免拆批漏审", null)));
        if (!batches.advance(taskId, links, contract, true)) return;
        for (var row : links) batches.output(row, Plan.class).relations().forEach(r -> relations.add(new Relation(row.id() + ":" + r.key(), r.fromGroup(), r.toGroup(), r.question())));
        store.plan(taskId, new Plan(List.copyOf(groups), List.copyOf(relations)), "跨功能关系冻结");
        states.completeStage(attempt, "功能分组和衔接关系已覆盖全部审查单元");
    }
    private void analyze(String taskId, Snapshot snapshot, AttemptRow analysisAttempt, AttemptRow reviewAttempt,
                         TemplateTaskContractFactory.Frozen contract) {
        var plan = store.plan(taskId); Map<String, Unit> units = new LinkedHashMap<>(); snapshot.units().forEach(u -> units.put(u.id(), u));
        List<TemplateTaskBatchRow> analyses = new ArrayList<>();
        int ordinal = 0;
        for (var group : plan.groups()) analyses.add(batches.create(analysisAttempt, ordinal++, "SNAPSHOT_ANALYSIS",
                new Input("ANALYSIS", group.unitIds().stream().map(units::get).toList(), List.of(group), List.of(), List.of(), group.title() + "：" + group.objective(), null)));
        expand(taskId, analysisAttempt, analyses, units);
        List<TemplateTaskBatchRow> reviewRows = new ArrayList<>();
        int reviewOrdinal = 0;
        for (var row : analyses) {
            if (row.state().equals("VALIDATED")) {
                var input = batches.input(row);
                reviewRows.add(batches.create(reviewAttempt, reviewOrdinal, "SNAPSHOT_REVIEW",
                        new Input("REVIEW", input.units(), input.groups(), input.relations(), List.of(row.id()), "独立复核：" + input.objective(), row.id())));
            }
            reviewOrdinal++;
        }
        // Relation analysis gets its own bounded source slices and independent reviewer, not just a prose summary.
        boolean mainDone = analyses.stream().allMatch(r -> r.state().equals("VALIDATED"));
        List<TemplateTaskBatchRow> relationAnalyses = new ArrayList<>();
        {
            int relOrdinal = 0;
            for (var relation : plan.relations()) {
                var related = plan.groups().stream().filter(g -> g.key().equals(relation.fromGroup()) || g.key().equals(relation.toGroup())).toList();
                var relatedUnits = related.stream().flatMap(g -> g.unitIds().stream()).distinct().map(units::get).toList();
                Set<String> relatedIds = new HashSet<>(relatedUnits.stream().map(Unit::id).toList());
                var dependencies = analyses.stream().filter(a -> batches.input(a).units().stream().anyMatch(u -> relatedIds.contains(u.id()))).toList();
                for (var slice : partition(relatedUnits)) {
                    int number = relOrdinal++;
                    if (dependencies.stream().allMatch(a -> a.state().equals("VALIDATED"))) relationAnalyses.add(batches.create(reviewAttempt, number, "SNAPSHOT_RELATION_ANALYSIS",
                            new Input("ANALYSIS", slice, related, List.of(relation), dependencies.stream().map(TemplateTaskBatchRow::id).toList(), "跨功能检查：" + relation.question(), null)));
                }
            }
            expand(taskId, reviewAttempt, relationAnalyses, units);
            int relationReviewBase = relOrdinal;
            for (var row : relationAnalyses) { int number = row.purpose().equals("SNAPSHOT_SUPPLEMENT") ? relationReviewBase + row.ordinal() : row.ordinal(); if (row.state().equals("VALIDATED")) {
                var input = batches.input(row);
                reviewRows.add(batches.create(reviewAttempt, number, "SNAPSHOT_RELATION_REVIEW",
                        new Input("REVIEW", input.units(), input.groups(), input.relations(), List.of(row.id()), "独立复核：" + input.objective(), row.id())));
            } }
        }
        var active = new ArrayList<>(analyses); active.addAll(relationAnalyses); active.addAll(reviewRows);
        boolean done = batches.advance(taskId, active, contract, true);
        if (mainDone && !tasks.findStage(analysisAttempt.stageId()).orElseThrow().state().equals("SUCCEEDED")) states.completeStage(analysisAttempt, "全部功能及补充分析完成");
        if (!done || !mainDone || reviewRows.size() != analyses.size() + relationAnalyses.size()) return;
        reports.publish(taskId, reviewAttempt, snapshot, plan, analyses, relationAnalyses, reviewRows);
        states.completeStage(reviewAttempt, "独立复核、范围校验和报告写入完成；静态审查不代表运行测试通过");
    }
    private void expand(String taskId, AttemptRow attempt, List<TemplateTaskBatchRow> analyses, Map<String, Unit> units) {
        // Supplements are persisted batch inputs. Only new context can extend a branch, preventing identical expansion loops.
        var existing = batches.rows(taskId, attempt).stream().filter(r -> r.purpose().equals("SNAPSHOT_SUPPLEMENT")).toList();
        analyses.addAll(existing);
        int supplementOrdinal = existing.stream().mapToInt(TemplateTaskBatchRow::ordinal).max().orElse(-1) + 1;
        for (var row : List.copyOf(analyses)) {
            if (!row.state().equals("VALIDATED")) continue;
            var input = batches.input(row); var output = batches.output(row, Analysis.class);
            for (var group : output.supplements()) {
                String identity = "补充检查：" + group.title();
                if (analyses.stream().anyMatch(r -> batches.input(r).dependencies().contains(row.id()) && batches.input(r).groups().stream().anyMatch(g -> g.key().equals(group.key())))) continue;
                List<String> contexts = new ArrayList<>(input.groups().stream().flatMap(g -> g.contextPaths().stream()).toList());
                contexts.addAll(group.contextPaths());
                var supplement = new Group(group.key(), group.title(), group.objective(), group.unitIds(), contexts.stream().distinct().toList());
                var next = new Input("ANALYSIS", group.unitIds().stream().map(units::get).toList(), List.of(supplement), List.of(), List.of(row.id()), identity + " · " + group.objective(), null);
                analyses.add(batches.supplement(attempt, supplementOrdinal++, next));
            }
        }
    }
    static List<List<Unit>> partition(List<Unit> units) {
        List<List<Unit>> result = new ArrayList<>(); List<Unit> current = new ArrayList<>(); int size = 0;
        for (Unit unit : units) {
            if (!current.isEmpty() && (size + unit.excerpt().length() > 48000 || current.size() >= 12)) { result.add(List.copyOf(current)); current.clear(); size = 0; }
            current.add(unit); size += unit.excerpt().length();
        }
        if (!current.isEmpty()) result.add(List.copyOf(current));
        return List.copyOf(result);
    }
}
