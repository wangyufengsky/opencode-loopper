package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Development reports share the immutable requirement index and expose actual test identities separately from design intent. */
@Service
public final class DocumentDevelopmentReport {
    private final DocumentDevelopmentEvidence evidence;
    private final DocumentRequirementMapper requirements;
    private final DocumentTemplateMapper runs;
    private final DocumentRequirementReportService reports;
    private final ObjectMapper json;
    private final DocumentRequirementClarifications clarifications;
    private final DirectDocumentDevelopmentReport direct;
    public DocumentDevelopmentReport(DocumentDevelopmentEvidence evidence, DocumentRequirementMapper requirements,
            DocumentTemplateMapper runs, DocumentRequirementReportService reports, ObjectMapper json, DocumentRequirementClarifications clarifications, DirectDocumentDevelopmentReport direct) {
        this.evidence = evidence; this.requirements = requirements; this.runs = runs; this.reports = reports; this.json = json; this.clarifications = clarifications; this.direct = direct;
    }
    public void render(DocumentTemplateRunRow run) {
        if (!run.templateId().equals("REQUIREMENT_DEVELOPMENT") || !run.state().equals("REPORTING"))
            throw new ConflictException("DOCUMENT_REPORT_STATE_CHANGED", "需求开发报告状态已变化");
        var snapshot = evidence.read(run);
        if (run.directDocuments()) { direct.render(run, snapshot); return; }
        var matrix = new StringBuilder("# 需求验收矩阵\n\n| 需求 | 验收结果 | 对应阶段 |\n|---|---|---|\n");
        var rows = new ArrayList<Map<String, Object>>(); int after = -1;
        while (true) {
            var page = requirements.page(run.id(), run.basisRevision(), after, 100);
            for (var item : page) {
                var links = snapshot.requirements().get(item.requirementKey());
                if (links == null || links.stream().noneMatch(DocumentDevelopmentEvidence.Mapping::appliesToCurrentRevision))
                    throw new ConflictException("DOCUMENT_REPORT_MAPPING_MISSING", "需求缺少本版冻结验收关联");
                var row = Map.<String, Object>of("requirementKey", item.requirementKey(), "title", item.title(),
                        "statement", item.statement(), "sources", json.readTree(item.sourcesJson()), "acceptance", json.readTree(item.acceptanceJson()),
                        "conclusion", "ACCEPTED_BY_EXECUTION_AND_DUAL_JUDGES", "evidence", links);
                rows.add(row);
                matrix.append('|').append(item.requirementKey()).append(' ').append(cell(item.title())).append("|执行验证与同批双评审通过|")
                        .append(String.join(", ", links.stream().filter(DocumentDevelopmentEvidence.Mapping::appliesToCurrentRevision)
                                .map(DocumentDevelopmentEvidence.Mapping::stageId).distinct().toList())).append("|\n");
                reports.save(run, "requirements/" + item.requirementKey() + ".json", "REQUIREMENT_MATRIX", json.writeValueAsString(row));
            }
            if (page.size() < 100) break; after = page.getLast().ordinal();
        }
        var limitations = new ArrayList<String>();
        for (var file : runs.files(run.id())) for (String limitation : json.readValue(file.limitationsJson(), String[].class))
            limitations.add(file.filename() + "：" + limitation);
        String summary = "# " + cell(run.title()) + "\n\n需求开发执行及自动验收已完成。结果处置由关联任务独立管理，未自动提交、推送或发布。\n\n"
                + "- 需求版本：" + snapshot.requirementRevision() + "\n- 需求项数：" + rows.size()
                + "\n- 执行任务：" + snapshot.execution().taskId() + "\n- 执行周期：" + snapshot.execution().cycleId()
                + "\n- 双评审批次：" + snapshot.execution().reviewBatchId()
                + "\n\n逐项结论以冻结的来源映射、服务端验证器及同批需求/风险评审为依据；设计场景与测试记录分别列出，不代表穷尽全部可能行为。\n\n"
                + "## 文档提取局限\n\n" + (limitations.isEmpty() ? "本次解析器未登记额外提取局限。\n" : String.join("\n", limitations.stream().map(value -> "- " + value).toList()));
        var answers = clarifications.answers(run.id(), run.basisRevision());
        if (!answers.isEmpty()) summary += "\n\n## 业务澄清依据\n\n" + String.join("\n\n", answers.stream()
                .map(answer -> "原需求版本 " + answer.sourceRevision() + " / " + answer.requirementKey() + "：" + answer.statement() + "\n\n用户回答：" + answer.answer()).toList());
        reports.save(run, "summary.md", "REQUIREMENT_REPORT", summary);
        reports.save(run, "matrix.md", "REQUIREMENT_REPORT", matrix.toString());
        reports.save(run, "matrix.json", "REQUIREMENT_MATRIX", json.writeValueAsString(Map.of(
                "templateVersion", run.templateVersion(), "requirementRevision", run.basisRevision(),
                "developmentCompleted", true, "requirements", rows, "execution", snapshot.execution(), "limitations", limitations, "clarifications", answers)));
    }
    private static String cell(String text) { return text.replace("|", "\\|").replace("\n", " ").replace("\r", " "); }
}
