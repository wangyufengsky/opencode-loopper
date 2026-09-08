package io.opencode.loopper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;

class CodeStructureContractTest {
    private static final int DEFAULT_MAX_LINES = 600;

    /**
     * Temporary debt caps. A refactor may lower a cap but must never raise one.
     * Files leave this map as soon as they are at or below the default limit.
     */
    private static final Map<String, Integer> LEGACY_RATCHET = Map.of(
            "io/opencode/loopper/service/DesignerSessionService.java", 5_363,
            "io/opencode/loopper/service/TaskService.java", 2_726,
            "io/opencode/loopper/service/LocalSyncConflictService.java", 1_159);

    @Test
    void productionJavaFilesRespectDefaultLimitOrLegacyRatchet() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        Map<String, Integer> violations = new LinkedHashMap<>();
        try (var files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> {
                String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
                int limit = LEGACY_RATCHET.getOrDefault(relative, DEFAULT_MAX_LINES);
                int lines = lineCount(path);
                if (lines > limit) violations.put(relative, lines);
            });
        }

        assertThat(violations)
                .as("Production classes must stay under 600 lines; legacy debt may only shrink")
                .isEmpty();
    }

    @Test
    void legacyRatchetContainsOnlyExistingOversizedFiles() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        Map<String, Integer> stale = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : LEGACY_RATCHET.entrySet()) {
            Path path = sourceRoot.resolve(entry.getKey());
            int lines = Files.exists(path) ? lineCount(path) : -1;
            if (lines <= DEFAULT_MAX_LINES || lines > entry.getValue()) stale.put(entry.getKey(), lines);
        }

        assertThat(stale)
                .as("Remove files at or below the default limit and never raise a legacy cap")
                .isEmpty();
    }

    @Test
    void compiledDomainAndSelectedCollaboratorsKeepDependencyDirection() throws Exception {
        // Inspect actual direct bytecode dependencies, including method bodies;
        // imports/comments and a class staying under 600 lines cannot prove this.
        Path classes = Path.of(io.opencode.loopper.domain.LoopSpec.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        StringWriter output = new StringWriter();
        int result = ToolProvider.findFirst("jdeps").orElseThrow().run(
                new PrintWriter(output), new PrintWriter(output), "--ignore-missing-deps",
                "-verbose:class", "-filter:none", "-include",
                "io\\.opencode\\.loopper\\.(domain\\..*|service\\.(PackageDesignScopeGuard|DesignerAcceptanceCandidateWorkflow)(\\$.*)?)",
                classes.toString());
        assertThat(result).as("jdeps must successfully inspect the compiled classes: %s", output).isZero();
        Map<String, String> violations = new LinkedHashMap<>();
        int inspected = 0;
        for (String line : output.toString().lines().toList()) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 3 || !fields[1].equals("->") || !fields[0].startsWith("io.opencode.loopper.")) continue;
            inspected++;
            String from = fields[0], to = fields[2];
            boolean reverseDomain = from.startsWith("io.opencode.loopper.domain.")
                    && to.matches("io\\.opencode\\.loopper\\.(api|config|lifecycle|persistence|runtime|service|verification|web)\\..*");
            boolean facadeCoupling = from.startsWith("io.opencode.loopper.service.DesignerAcceptanceCandidateWorkflow")
                    && to.equals("io.opencode.loopper.service.DesignerSessionService");
            boolean scopeIo = from.startsWith("io.opencode.loopper.service.PackageDesignScopeGuard")
                    // Frozen input exposes this immutable Row value; it is not a DB adapter.
                    && !to.equals("io.opencode.loopper.persistence.DesignWorkPackageRow")
                    && (to.matches("io\\.opencode\\.loopper\\.(api|persistence|runtime)\\..*")
                    || to.matches("java\\.(net|sql)\\..*") || to.equals("java.nio.file.Files")
                    || to.equals("java.lang.ProcessBuilder"));
            if (reverseDomain || facadeCoupling || scopeIo) violations.put(from + " -> " + to, line.trim());
        }
        assertThat(inspected).as("Dependency inspection must not silently match no classes").isPositive();
        assertThat(violations).as("Domain stays independent; scope policy has no direct I/O; workflow uses its narrow Port").isEmpty();
    }

    @Test
    void readPathCollaboratorsKeepNarrowConstructionBoundaries() {
        for (Class<?> type : java.util.List.of(
                io.opencode.loopper.service.ProjectInspectionCache.class,
                io.opencode.loopper.service.ProjectReadService.class,
                io.opencode.loopper.service.AutomationPollHealthService.class)) {
            for (var constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterCount()).as(type.getSimpleName() + " constructor dependencies").isLessThanOrEqualTo(8);
                assertThat(java.util.Arrays.stream(constructor.getParameterTypes()).map(Class::getSimpleName))
                        .doesNotContain("TaskService", "DesignerSessionService", "AutomationService");
            }
        }
    }

    private static int lineCount(Path path) {
        try (var lines = Files.lines(path)) {
            return Math.toIntExact(lines.count());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read " + path, failure);
        }
    }
}
