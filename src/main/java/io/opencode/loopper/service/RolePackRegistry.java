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
import java.util.Properties;
import java.io.StringReader;
import java.io.IOException;
import io.opencode.loopper.service.roles.RolePromptResources;
import org.springframework.stereotype.Component;

/** Versioned prompt capabilities; permissions and workflow remain server-owned. */
@Component
public final class RolePackRegistry {
    public static final String VERSION = "2026-08-dynamic-v7";
    static final String ACCEPTANCE_V6_VERSION = "2026-08-dynamic-v6";
    private static final Set<String> DETERMINISTIC_ACCEPTANCE_VERSIONS = Set.of(
            "2026-08-dynamic-v4", "2026-08-dynamic-v5", ACCEPTANCE_V6_VERSION, VERSION);

    public RolePack resolve(TaskIntent intent, List<String> technologies, List<ArtifactKind> artifacts) {
        if (intent == TaskIntent.SOFTWARE_CHANGE || intent == TaskIntent.LEGACY_SOFTWARE) {
            Set<SoftwareFamily> families = softwareFamilies(technologies);
            if (families.size() > 1) return pack("software-mixed", VERSION);
            if (families.contains(SoftwareFamily.PYTHON)) return pack("software-python", VERSION);
            if (families.contains(SoftwareFamily.NODE)) return pack("software-node", VERSION);
            if (families.contains(SoftwareFamily.JAVA)) return pack("software-java", VERSION);
            if (families.isEmpty() && intent == TaskIntent.LEGACY_SOFTWARE) {
                return pack("software-java", VERSION);
            }
            if (families.isEmpty()) return pack("software-generic", VERSION);
            return pack("software-generic", VERSION);
        }
        if (intent == TaskIntent.DOCUMENT_AUTHORING) {
            return pack("document-markdown-docx", VERSION);
        }
        if (intent == TaskIntent.DATA_CONVERSION) {
            return pack("tabular-conversion", VERSION);
        }
        if (EnumSet.of(TaskIntent.READ_ONLY_REVIEW, TaskIntent.RESEARCH).contains(intent)) {
            return pack("read-only-report", VERSION);
        }
        return pack("local-maintenance", VERSION);
    }

    /** Looks up only an actually bundled definition; historical versions require their own snapshot. */
    public RolePack byIdAndVersion(String id, String version) {
        return pack(id, version);
    }

    private static RolePack pack(String id, String version) {
        String content = RolePromptResources.read("role-pack." + version + "." + id + ".definition");
        Properties values = new Properties();
        try {
            values.load(new StringReader(content));
        } catch (IOException failure) {
            throw new IllegalStateException("Invalid Role Pack definition: " + id + "@" + version, failure);
        }
        if (!id.equals(values.getProperty("id")) || !version.equals(values.getProperty("version"))) {
            throw new IllegalStateException("Mismatched Role Pack definition: " + id + "@" + version);
        }
        return new RolePack(id, version, values.getProperty("displayName"),
                ExecutionStrategy.valueOf(values.getProperty("executionStrategy")),
                TestPolicy.valueOf(values.getProperty("defaultTestPolicy")));
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

    static boolean supportsDeterministicAcceptance(String version) {
        return DETERMINISTIC_ACCEPTANCE_VERSIONS.contains(version);
    }

    static boolean supportsClosedAcceptance(String version) {
        return ACCEPTANCE_V6_VERSION.equals(version) || VERSION.equals(version);
    }

    private static SoftwareFamily softwareFamily(String technology) {
        if (technology == null || technology.isBlank()) return null;
        String value = technology.trim().toLowerCase(Locale.ROOT);
        if (containsAny(value, "javascript", "typescript", "node", "node.js", "nodejs", "npm", "pnpm",
                "yarn", "vue", "react", "vite", "vitest")) return SoftwareFamily.NODE;
        if (containsAny(value, "python", "python3", "pytest", "unittest", "django", "flask", "fastapi"))
            return SoftwareFamily.PYTHON;
        if (value.equals("java") || value.startsWith("java ") || value.matches("java\\d+.*")
                || containsAny(value, "jdk", "spring", "maven", "gradle", "kotlin", "junit",
                "jupiter", "surefire", "testng"))
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
