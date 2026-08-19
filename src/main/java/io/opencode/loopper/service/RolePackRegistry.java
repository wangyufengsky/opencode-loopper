package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TestPolicy;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Component;

/** Versioned prompt capabilities; permissions and workflow remain server-owned. */
@Component
public final class RolePackRegistry {
    public static final String VERSION = "2026-08-dynamic-v1";

    public RolePack resolve(TaskIntent intent, List<String> technologies, List<ArtifactKind> artifacts) {
        List<String> normalized = technologies == null ? List.of() : technologies.stream()
                .map(String::toLowerCase).toList();
        if (intent == TaskIntent.SOFTWARE_CHANGE || intent == TaskIntent.LEGACY_SOFTWARE) {
            if (normalized.contains("python")) return new RolePack("software-python", VERSION,
                    "Python 软件设计师", ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.OPTIONAL);
            if (normalized.contains("node") || normalized.contains("vue") || normalized.contains("javascript")
                    || normalized.contains("typescript")) return new RolePack("software-node", VERSION,
                    "Node/前端软件设计师", ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED);
            return new RolePack("software-java", VERSION, "Java 软件设计师",
                    ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED);
        }
        if (intent == TaskIntent.DOCUMENT_AUTHORING) {
            return new RolePack("document-markdown-docx", VERSION, "文档制品设计师",
                    ExecutionStrategy.SERVER_DOCUMENT_MATERIALIZATION, TestPolicy.NOT_APPLICABLE);
        }
        if (intent == TaskIntent.DATA_CONVERSION) {
            return new RolePack("tabular-conversion", VERSION, "表格转换设计师",
                    ExecutionStrategy.SERVER_TABULAR_CONVERSION, TestPolicy.NOT_APPLICABLE);
        }
        if (EnumSet.of(TaskIntent.READ_ONLY_REVIEW, TaskIntent.RESEARCH).contains(intent)) {
            return new RolePack("read-only-report", VERSION, "只读评审器",
                    ExecutionStrategy.READ_ONLY_REPORT, TestPolicy.NOT_APPLICABLE);
        }
        return new RolePack("local-maintenance", VERSION, "本地维护设计师",
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.OPTIONAL);
    }

    public record RolePack(String id, String version, String displayName,
                           ExecutionStrategy executionStrategy, TestPolicy defaultTestPolicy) { }
}
