package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Source-template requirements name every frozen file; code is retrieved through a separate scoped read capability. */
final class SourceRequirementContext {
    private static final String INDEX = "LOOPPER_SOURCE_TEST_INDEX_V1\n";
    private static final String SOURCES = "LOOPPER_SOURCE_TEST_REQUIREMENTS_V1\n";
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String TEST_POLICY = "只新增或补齐冻结测试源码与夹具。保留已有有效测试及断言；禁止删除测试、屏蔽执行、降低断言。"
            + "按实际接口设计正常、边界、异常和关键分支场景。需要改业务代码、依赖或构建配置时提出待处理，不擅自修改。"
            + "业务缺陷导致的测试失败必须保留证据，不能迁就错误实现。已有测试满足要求时保留并重新执行，不为制造差异重复编写。";
    private SourceRequirementContext() { }
    static boolean source(String value) { return value != null && (value.startsWith(INDEX) || value.startsWith(SOURCES)); }
    static String index(SourceTemplateRunRow run, SourceTestProfile profile) {
        return INDEX + JSON.writeValueAsString(Map.of("runId", run.id(), "manifestSha256", profile.manifestSha256(),
                "modules", profile.modules(), "requirements", JSON.readValue(run.parametersJson(), SourceTemplateParameters.class).requirements()))
                + "\n" + TEST_POLICY + "\n" + DocumentRequirementContext.REGRESSION_RULE;
    }
    static String resolve(DocumentDesignContextMapper mapper, DesignRequirementRevisionRow revision, DesignWorkPackageRow pack) {
        var origin = mapper.sourceDevelopmentPackage(pack.id(), revision.designerSessionId()).orElseThrow(SourceTemplateAdmission::conflict);
        if (!origin.ownerId().equals(revision.id()) || !pack.requirementRevisionId().equals(revision.id())) throw SourceTemplateAdmission.conflict();
        var frozen = mapper.sourceTestProfile(origin.runId()).orElseThrow(SourceTemplateAdmission::conflict);
        if (!DocumentModelStore.hash(frozen.profileJson()).equals(frozen.sha256())) throw SourceTemplateAdmission.conflict();
        var profile = JSON.readValue(frozen.profileJson(), SourceTestProfile.class);
        var run = mapper.sourceDevelopmentDesigner(revision.designerSessionId()).orElseThrow(SourceTemplateAdmission::conflict);
        if (!run.id().equals(origin.runId())) throw SourceTemplateAdmission.conflict();
        String requirements = JSON.readValue(run.parametersJson(), SourceTemplateParameters.class).requirements();
        if (!profile.manifestSha256().equals(origin.manifestSha256())) throw SourceTemplateAdmission.conflict();
        var refs = new HashSet<>(List.of(JSON.readValue(pack.requirementRefsJson(), String[].class)));
        refs.addAll(mapper.documentGlobalSourceRefs(pack.id()));
        var entries = new ArrayList<Entry>();
        for (var file : mapper.sourceDevelopmentFiles(origin.runId())) {
            String ref = reference(file.ordinal());
            if (!refs.contains(ref)) continue;
            var module = profile.modules().stream().filter(m -> m.sourcePaths().contains(file.path())).findFirst();
            if (module.isEmpty()) continue;
            entries.add(new Entry(ref, file.path(), "为冻结源码 " + file.path() + " 补齐单元测试。源码 SHA-256=" + file.sha256()
                    + "。框架=" + module.get().framework() + "；测试源码目录=" + module.get().testRoots()
                    + "；夹具目录=" + module.get().fixtureRoots() + "；模块回归命令=" + module.get().command() + "。\n" + TEST_POLICY
                    + (requirements.isBlank() ? "" : "\n本次冻结的补充要求（不扩大测试写入许可）：\n" + requirements)));
        }
        if (entries.isEmpty()) throw new ConflictException("SOURCE_PACKAGE_COVERAGE_MISSING", "工作包没有分配有效的源码测试对象");
        if (mapper.documentLastPackage(pack.id())) entries.add(new Entry(DocumentRequirementContext.FINAL_REGRESSION,
                "整体回归", DocumentRequirementContext.REGRESSION_RULE + "\n全部模块的冻结测试命令：" + JSON.writeValueAsString(profile.modules())));
        return SOURCES + JSON.writeValueAsString(new Envelope(origin.runId(), origin.manifestSha256(), List.copyOf(entries), profile.modules()));
    }
    static Map<String, PackageRequirementSources.Source> sources(String text) {
        if (text == null || !text.startsWith(SOURCES)) return null;
        var envelope = envelope(text); var result = new LinkedHashMap<String, PackageRequirementSources.Source>();
        for (var entry : envelope.entries()) if (result.put(entry.ref(),
                new PackageRequirementSources.Source(entry.ref(), entry.statement(), DocumentModelStore.hash(entry.statement()))) != null)
            throw SourceTemplateAdmission.conflict();
        return Collections.unmodifiableMap(result);
    }
    static String text(String input) {
        if (input == null || !input.startsWith(SOURCES)) return input;
        return String.join("\n\n", envelope(input).entries().stream().map(Entry::statement).toList());
    }
    /** Build configuration and read-only source references are not inferred write obligations. */
    static String mutationText(String input) {
        if (input == null || !input.startsWith(SOURCES)) return DocumentRequirementContext.text(input);
        return TEST_POLICY + "\n" + String.join("\n", envelope(input).entries().stream()
                .filter(e -> e.ref().startsWith("SRC-")).map(e -> "禁止修改源码路径：" + e.path()).toList());
    }
    static List<SourceTestProfile.Module> modules(String input) {
        return input != null && input.startsWith(SOURCES) ? envelope(input).modules() : List.of();
    }
    static String prompt(String input) {
        if (input == null || !input.startsWith(SOURCES)) return input;
        var envelope = envelope(input);
        return "本包来源为冻结源码模板 run=" + envelope.runId() + "，sha256=" + envelope.manifestSha256()
                + "。通过 get_source_development_work、read_source_development_file 阅读完整源码和已有测试。"
                + "SRC 编号对应源码测试对象，不能仅依据文件名设计。\n"
                + String.join("\n", envelope.entries().stream().map(e -> "[" + e.ref() + "] " + e.statement()).toList());
    }
    static String reference(int ordinal) { return "SRC-" + (ordinal + 1); }
    private static Envelope envelope(String value) { return JSON.readValue(value.substring(SOURCES.length()), Envelope.class); }
    record Envelope(String runId, String manifestSha256, List<Entry> entries, List<SourceTestProfile.Module> modules) { }
    record Entry(String ref, String path, String statement) { }
}
