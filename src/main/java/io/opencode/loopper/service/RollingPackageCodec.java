package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.PackageFactSnapshotRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Serialization, hashing and prompt bounds for the rolling aggregate. */
final class RollingPackageCodec {
    private final ObjectMapper json;
    RollingPackageCodec(ObjectMapper json) { this.json = json; }

    List<LoopSpec.StageSpec> compiledStages(LoopSpecCompilationRow compilation) {
        try {
            var envelope = json.readValue(compilation.compiledPackageJson(),
                    DesignerSemanticContracts.PackageCompilationEnvelope.class).normalized();
            if (envelope.stages().isEmpty()) throw new ConflictException("PACKAGE_STAGE_MISSING", "工作包没有可执行 Stage");
            return envelope.stages();
        } catch (JacksonException failure) {
            throw new ConflictException("PACKAGE_COMPILATION_INVALID", "工作包累计执行规范无法读取");
        }
    }

    String planJson(List<DesignWorkPackageRow> packages) {
        return write(packages.stream().map(row -> Map.of("packageKey", row.packageId(), "ordinal", row.ordinal(),
                "title", row.title(), "dependencies", strings(row.dependenciesJson()))).toList());
    }

    LoopSpec copyWithStages(LoopSpec base, List<LoopSpec.StageSpec> stages) {
        return new LoopSpec(base.schemaVersion(), base.projectId(), base.goal(), base.context(), List.copyOf(stages),
                base.limits(), base.model(), base.sessionPolicy(), base.nextAttemptPromptTemplate(), base.budget());
    }

    LoopSpec parseSpec(String value) {
        try { return json.readValue(value, LoopSpec.class); }
        catch (JacksonException failure) { throw new ConflictException("TASK_SPEC_INVALID", "累计执行规范无法读取"); }
    }

    String factContext(List<PackageFactSnapshotRow> facts) {
        StringBuilder result = new StringBuilder();
        for (PackageFactSnapshotRow fact : facts) {
            String block = boundedUtf8("\n\n### 已冻结事实 " + fact.packageRunId()
                    + "\n已证明索引：inputTree=" + fact.inputTree() + ", outputTree=" + fact.outputTree()
                    + ", manifestSha256=" + fact.manifestSha256() + ", diffSha256=" + fact.diffSha256()
                    + ", evidenceSha256=" + fact.evidenceSha256()
                    + "\n已接受合同索引：taskSpecSha256=" + fact.taskSpecSha256()
                    + "\nAI 导航摘要（非证据）：" + boundedSummary(fact.navigationSummary()), 4 * 1024);
            if (utf8(result.toString()) + utf8(block) > 24 * 1024) break;
            result.append(block);
        }
        return result.toString();
    }

    String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("Unable to serialize rolling package state", failure); }
    }

    Object jsonValue(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, Object.class); }
        catch (JacksonException failure) { throw new ConflictException("PACKAGE_CONTRACT_INVALID", "工作包合同无法读取"); }
    }

    String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    String boundedTitle(String value) {
        String normalized = value == null || value.isBlank() ? "大型软件任务" : value.strip();
        return normalized.substring(0, Math.min(normalized.length(), 120));
    }

    String boundedSummary(String value) {
        if (value == null || value.isBlank()) return "仅用于下一包导航，不属于机器证据。";
        return boundedUtf8(value, 4096);
    }

    private List<String> strings(String source) {
        try { return json.readValue(source, new TypeReference<List<String>>() { }); }
        catch (JacksonException failure) { return List.of(); }
    }
    private String boundedUtf8(String value, int limit) {
        if (utf8(value) <= limit) return value;
        int end = Math.min(value.length(), limit);
        while (utf8(value.substring(0, end)) > limit) end--;
        return value.substring(0, end);
    }
    private int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
