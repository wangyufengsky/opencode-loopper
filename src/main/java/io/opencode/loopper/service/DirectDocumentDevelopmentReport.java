package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Reports approved design scenarios against original sources without inventing an extracted requirement baseline. */
@Service
public final class DirectDocumentDevelopmentReport {
    private final DocumentTemplateMapper documents;
    private final DocumentRequirementReportService reports;
    private final ObjectMapper json;
    public DirectDocumentDevelopmentReport(DocumentTemplateMapper documents, DocumentRequirementReportService reports, ObjectMapper json) {
        this.documents = documents; this.reports = reports; this.json = json;
    }
    public void render(DocumentTemplateRunRow run, DocumentDevelopmentEvidence.Snapshot proof) {
        var sources = new ArrayList<Map<String, Object>>();
        var limitations = new ArrayList<String>();
        var rows = new LinkedHashMap<String, Map<String, Object>>();
        var matrix = new StringBuilder("# 设计场景验收矩阵\n\n场景来自正式批准设计；文档来源编号不代表预先提取的需求条目。\n\n| 场景 | 原文来源 | 阶段 | 结果 |\n|---|---|---|---|\n");
        for (var file : documents.sourceFiles(run.id(), run.sourceRevision())) {
            String ref = "DOC-" + (file.ordinal() + 1);
            var fileLimits = List.of(json.readValue(file.limitationsJson(), String[].class));
            sources.add(Map.of("sourceRef", ref, "fileId", file.id(), "filename", file.filename(), "sha256", file.sha256(),
                    "sectionCount", file.sectionCount(), "limitations", fileLimits));
            fileLimits.forEach(value -> limitations.add(file.filename() + "：" + value));
        }
        proof.requirements().forEach((source, mappings) -> {
            for (var mapping : mappings) {
                if (!mapping.appliesToCurrentRevision()) continue;
                String key = mapping.packageKey() + "/" + mapping.designRevision() + "/" + mapping.scenarioKey();
                var row = rows.computeIfAbsent(key, ignored -> new LinkedHashMap<>(Map.of("scenarioKey", key,
                        "title", mapping.scenario(), "sourceRefs", new ArrayList<String>(), "evidence", new ArrayList<>(),
                        "conclusion", "ACCEPTED_BY_EXECUTION_AND_DUAL_JUDGES")));
                add(row, source, mapping);
                matrix.append('|').append(cell(mapping.scenario())).append('|').append(source).append('|')
                        .append(mapping.stageId()).append("|服务端测试验证与同批双评审通过|\n");
            }
        });
        if (rows.isEmpty()) throw new ConflictException("DOCUMENT_REPORT_MAPPING_MISSING", "缺少批准设计与实际测试的关联");
        String summary = "# " + cell(run.title()) + "\n\n需求开发执行及自动验收已完成。\n\n"
                + "- 原文版本：" + run.sourceRevision() + "\n- 原文文档数：" + sources.size()
                + "\n- 已验收设计场景数：" + rows.size() + "\n- 执行任务：" + proof.execution().taskId()
                + "\n- 双评审批次：" + proof.execution().reviewBatchId()
                + "\n\n验收依据是正式设计、实际测试记录、服务端验证与需求/风险双评审。读取原文只证明读取范围；不代表语义无遗漏或测试穷尽。结果处置由关联任务独立管理。\n\n"
                + "## 提取局限\n\n" + (limitations.isEmpty() ? "解析器未登记额外提取局限。" : String.join("\n", limitations));
        reports.save(run, "summary.md", "REQUIREMENT_REPORT", summary);
        reports.save(run, "matrix.md", "REQUIREMENT_REPORT", matrix.toString());
        reports.save(run, "matrix.json", "REQUIREMENT_MATRIX", json.writeValueAsString(Map.of(
                "templateVersion", run.templateVersion(), "sourceRevision", run.sourceRevision(), "sourceKind", "DOCUMENT_SOURCE",
                "developmentCompleted", true, "sources", sources, "scenarios", rows.values(), "execution", proof.execution(), "limitations", limitations)));
    }
    @SuppressWarnings("unchecked")
    private static void add(Map<String, Object> row, String source, DocumentDevelopmentEvidence.Mapping mapping) {
        var refs = (List<String>) row.get("sourceRefs"); if (!refs.contains(source)) refs.add(source);
        var evidence = (List<DocumentDevelopmentEvidence.Mapping>) row.get("evidence"); if (!evidence.contains(mapping)) evidence.add(mapping);
    }
    private static String cell(String value) { return value.replace("|", "\\|").replace('\n', ' ').replace('\r', ' '); }
}
