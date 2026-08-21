package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TestPolicy;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Versioned prompt capabilities; permissions and workflow remain server-owned. */
@Component
public final class RolePackRegistry {
    public static final String VERSION = "2026-08-dynamic-v4";

    public RolePack resolve(TaskIntent intent, List<String> technologies, List<ArtifactKind> artifacts) {
        if (intent == TaskIntent.SOFTWARE_CHANGE || intent == TaskIntent.LEGACY_SOFTWARE) {
            Set<SoftwareFamily> families = softwareFamilies(technologies);
            if (families.size() > 1) return new RolePack("software-mixed", VERSION,
                    "混合技术栈软件设计师", ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED);
            if (families.contains(SoftwareFamily.PYTHON)) return new RolePack("software-python", VERSION,
                    "Python 软件设计师", ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.OPTIONAL);
            if (families.contains(SoftwareFamily.NODE)) return new RolePack("software-node", VERSION,
                    "Node/前端软件设计师", ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.OPTIONAL);
            if (families.contains(SoftwareFamily.JAVA)) return new RolePack("software-java", VERSION,
                    "Java 软件设计师", ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED);
            if (families.isEmpty()) return new RolePack("software-java", VERSION, "Java 软件设计师",
                    ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED);
            return new RolePack("software-generic", VERSION, "通用软件设计师",
                    ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.OPTIONAL);
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
            return new RolePack("read-only-report", VERSION, "评审员",
                    ExecutionStrategy.READ_ONLY_REPORT, TestPolicy.NOT_APPLICABLE);
        }
        return new RolePack("local-maintenance", VERSION, "本地维护设计师",
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.OPTIONAL);
    }

    static Set<SoftwareFamily> softwareFamilies(List<String> technologies) {
        LinkedHashSet<SoftwareFamily> families = new LinkedHashSet<>();
        if (technologies == null) return Set.of();
        for (String technology : technologies) {
            SoftwareFamily family = softwareFamily(technology);
            if (family != null) families.add(family);
        }
        return Set.copyOf(families);
    }

    private static SoftwareFamily softwareFamily(String technology) {
        if (technology == null || technology.isBlank()) return null;
        String value = technology.trim().toLowerCase(Locale.ROOT);
        if (containsAny(value, "javascript", "typescript", "node", "node.js", "nodejs", "npm", "pnpm",
                "yarn", "vue", "react", "vite", "vitest")) return SoftwareFamily.NODE;
        if (containsAny(value, "python", "python3", "pytest", "unittest", "django", "flask", "fastapi"))
            return SoftwareFamily.PYTHON;
        if (value.equals("java") || value.startsWith("java ") || value.matches("java\\d+.*")
                || containsAny(value, "jdk", "spring", "maven", "gradle", "kotlin"))
            return SoftwareFamily.JAVA;
        return SoftwareFamily.OTHER;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    enum SoftwareFamily { JAVA, PYTHON, NODE, OTHER }

    public record RolePack(String id, String version, String displayName,
                           ExecutionStrategy executionStrategy, TestPolicy defaultTestPolicy) { }
}
