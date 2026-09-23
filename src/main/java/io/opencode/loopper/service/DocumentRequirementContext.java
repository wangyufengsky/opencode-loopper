package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** A versioned source envelope is expanded for server validation, but model prompts use scoped paged reads. */
final class DocumentRequirementContext {
    static final String FINAL_REGRESSION = "POLICY-FINAL-REGRESSION";
    static final String REGRESSION_RULE = "最后一个开发包必须在全部本期变更之上执行整体回归：覆盖跨包协作、受影响功能和核心业务流程。"
            + "保留每包已有行为测试；明确仓库原生测试目标和可观察业务断言，由正式验证器执行并保存结果。";
    private static final String INDEX = "LOOPPER_DOCUMENT_REQUIREMENT_INDEX_V1\n";
    private static final String SOURCES = "LOOPPER_DOCUMENT_REQUIREMENT_SOURCES_V1\n";
    private static final ObjectMapper JSON = new ObjectMapper();
    private DocumentRequirementContext() { }
    static String model(DocumentDesignContextMapper mapper, String designer, String fallback) {
        return TemplateDevelopmentAuthorization.model(mapper, designer, fallback);
    }
    static java.time.Duration attemptTimeout(DocumentDesignContextMapper mapper, String designer, java.time.Duration fallback) {
        return TemplateDevelopmentAuthorization.timeout(mapper, designer, fallback);
    }
    static String index(DocumentTemplateRunRow run, String manifest, int count) {
        return INDEX + JSON.writeValueAsString(Map.of("runId", run.id(), "revision", run.basisRevision(),
                "manifestSha256", manifest, "sourceCount", count, "sourceKind", run.directDocuments() ? "DOCUMENT_SOURCE" : "REQUIREMENT_LIST"))
                + (run.directDocuments() ? "\n本次直接依据冻结原文开发；DOC 编号是文档来源，不是提取后的需求。先读取目录与正文，结合代码形成设计及验收场景；未提取图片影响实现或验收时提出具体问题。" : "")
                + "\n软件开发范围由冻结需求原文定义，使用专属 MCP 分页读取每项需求和原文；标题不替代需求正文。"
                + "每包必须有行为测试，最后一个包必须验证跨包、受影响功能和核心业务流程。";
    }
    static boolean document(String value) { return value != null && (value.startsWith(INDEX) || value.startsWith(SOURCES)); }
    static String planPrompt(DocumentDesignContextMapper mapper, String planId, String fallback) {
        return mapper.documentPlanSource(planId).map(source -> INDEX + JSON.writeValueAsString(Map.of("runId", source.runId(),
                "revision", source.documentRevision(), "manifestSha256", source.manifestSha256()))
                + "\nDOC 编号是原文件来源：使用 list_development_documents/list_development_sections/read_development_source 读取本版全部原文；RQ 历史编号使用需求读取工具。核对已冻结事实，仅调整剩余包并保持需求覆盖；最后一包须包括整体回归。")
                .orElse(fallback);
    }
    static String resolve(DocumentDesignContextMapper mapper, DesignRequirementRevisionRow revision, DesignWorkPackageRow owner) {
        if (SourceRequirementContext.source(revision.requirementText())) return SourceRequirementContext.resolve(mapper, revision, owner);
        if (!document(revision.requirementText())) return revision.requirementText();
        var identity = mapper.documentPackageDesign(owner.id(), revision.designerSessionId()).orElseThrow(() ->
                new ConflictException("DOCUMENT_DESIGN_SOURCE_MISSING", "设计缺少冻结文档来源，不得降级到普通需求正文"));
        if (!owner.requirementRevisionId().equals(revision.id()) || !owner.designerSessionId().equals(revision.designerSessionId())
                || !identity.requirementRevisionId().equals(revision.id()))
            throw new ConflictException("DOCUMENT_DESIGN_SCOPE_MISMATCH", "工作包不属于该冻结文档需求");
        if (mapper.basis(identity.runId(), identity.documentRevision()).orElseThrow().sourceKind().equals("DOCUMENT_SOURCE"))
            return DocumentDirectDesignContext.resolve(mapper, identity, owner);
        var rows = mapper.documentPackageRequirements(revision.id(), owner.id());
        if (rows.isEmpty()) throw new ConflictException("DOCUMENT_PACKAGE_COVERAGE_MISSING", "工作包没有需求清单覆盖关系");
        if (rows.size() > 4096) throw capacity(rows.size());
        var entries = new ArrayList<>(rows.stream().map(row -> new Entry(row.requirementKey(), row.title(), row.statement(),
                JSON.readValue(row.acceptanceJson(), String[].class), row.sourcesJson())).toList());
        if (mapper.documentLastPackage(owner.id())) entries.add(new Entry(FINAL_REGRESSION, "模板授权中的最终整体回归", REGRESSION_RULE,
                new String[]{"后续包改变前包行为时，最终回归能够报告失败，失败不能自动认定为通过"}, "[]"));
        String encoded = SOURCES + JSON.writeValueAsString(new Envelope(identity.runId(), identity.documentRevision(),
                identity.manifestSha256(), entries));
        if (encoded.length() > 2_000_000) throw capacity(rows.size());
        return encoded;
    }
    static Map<String, PackageRequirementSources.Source> sources(String text) {
        if (text == null || !text.startsWith(SOURCES)) return null;
        Envelope envelope = envelope(text); var result = new LinkedHashMap<String, PackageRequirementSources.Source>();
        for (var entry : envelope.entries()) {
            String source = entry.title() + "\n" + entry.statement() + "\n验收场景：\n" + String.join("\n", entry.acceptance())
                    + "\n原文引用：" + entry.sourcesJson();
            if (result.put(entry.key(), new PackageRequirementSources.Source(entry.key(), source, DocumentModelStore.hash(source))) != null)
                throw new ConflictException("DOCUMENT_PACKAGE_SOURCE_DUPLICATE", "冻结文档需求引用重复");
        }
        return Collections.unmodifiableMap(result);
    }
    static String text(String source) {
        if (SourceRequirementContext.source(source)) return SourceRequirementContext.text(source);
        if (source == null || !source.startsWith(SOURCES)) return source;
        return envelope(source).entries().stream().map(entry -> (entry.key().startsWith("DOC-") ? "" : entry.title() + "\n") + entry.statement()
                + "\n" + String.join("\n", entry.acceptance())).collect(java.util.stream.Collectors.joining("\n\n"));
    }
    static String prompt(String text) {
        if (SourceRequirementContext.source(text)) return SourceRequirementContext.prompt(text);
        if (!document(text)) return text;
        if (text.startsWith(INDEX)) return text;
        var source = envelope(text);
        return "冻结文档需求 run=" + source.runId() + ", revision=" + source.revision() + ", sha256=" + source.manifestSha256()
                + "。本包及全局约束来源共 " + source.entries().size() + " 项，sourceRefs 使用下方列出的原始来源编号（DOC 为文档，RQ 为历史需求条目）。\n"
                + "需要分析的编号：" + String.join(",", source.entries().stream().map(Entry::key).toList())
                + (source.entries().stream().anyMatch(entry -> entry.key().equals(FINAL_REGRESSION))
                    ? "\n服务端模板约束 " + FINAL_REGRESSION + "（不属于上传文档）：" + REGRESSION_RULE : "")
                + "\n对于 DOC 原文来源，使用 list_development_documents/list_development_sections 和 read_development_source 阅读本包原文；不调用历史需求条目工具。对于 RQ 历史需求，使用 list_development_requirements 分页定位，read_development_requirement 逐项读取正文、验收和引用；"
                + "必要时用 read_development_source 核对原文。服务端约束正文已在本提示给出，无需文档工具读取；"
                + "必须读取全部本包及全局约束，不能仅依据标题或编号推断满足。";
    }
    private static Envelope envelope(String text) {
        try { return JSON.readValue(text.substring(SOURCES.length()), Envelope.class); }
        catch (RuntimeException invalid) { throw new ConflictException("DOCUMENT_PACKAGE_SOURCE_INVALID", "冻结文档来源格式失效"); }
    }
    private static BadRequestException capacity(int count) {
        return new BadRequestException("LARGE_TASK_MODE_REQUIRED", "当前包包含 " + count + " 项需求，超过有界编译容量；请按业务能力拆分后保持逐项覆盖");
    }
    record Envelope(String runId, int revision, String manifestSha256, List<Entry> entries) { }
    record Entry(String key, String title, String statement, String[] acceptance, String sourcesJson) { }
}
