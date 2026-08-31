package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerStatusProjectorTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
    private final DesignerStatusProjector projector =
            new DesignerStatusProjector(mapper, new ObjectMapper(), acceptance);

    @Test
    void projectsPackageCandidateFactsFromTheWorkPackageOwnerAndCompilationRecord() {
        when(mapper.findCurrentDesignRequirementRevision("designer")).thenReturn(Optional.of(
                new DesignRequirementRevisionRow("requirement", "designer", 1, "message", "requirement",
                        "[]", 1, "ACTIVE", 0, 96, "now", "now", 0)));
        when(mapper.listDesignWorkPackages("requirement")).thenReturn(List.of(
                workPackage("package-mcp", "WP-1"), workPackage("package-fallback", "WP-2")));
        LoopSpecCompilationRow mcp = compilation("compilation-mcp", "WP-1", true,
                "MCP_ACCEPTED", null);
        LoopSpecCompilationRow fallback = compilation("compilation-fallback", "WP-2", false,
                "MARKDOWN_FALLBACK", "FEATURE_DISABLED");
        when(mapper.findLatestLoopSpecCompilationForPackage("designer", "WP-1"))
                .thenReturn(Optional.of(mcp));
        when(mapper.findLatestLoopSpecCompilationForPackage("designer", "WP-2"))
                .thenReturn(Optional.of(fallback));
        when(mapper.findWorkPackageRoleProfile("package-mcp")).thenReturn(Optional.empty());
        when(mapper.findWorkPackageRoleProfile("package-fallback")).thenReturn(Optional.empty());
        when(mapper.findLatestCandidateSubmissionRunForWorkPackage("package-mcp", 1L))
                .thenReturn(Optional.of(candidateRun()));
        when(mapper.findLatestCandidateSubmissionRunForWorkPackage("package-fallback", 1L))
                .thenReturn(Optional.empty());
        when(mapper.countCandidateSubmissionAttemptsForRun("run")).thenReturn(2);

        List<DesignerSessionService.WorkPackageStatus> statuses = projector.workPackages("designer");

        assertThat(statuses).extracting(DesignerSessionService.WorkPackageStatus::candidateRunState)
                .containsExactly("ACCEPTED", null);
        assertThat(statuses).extracting(DesignerSessionService.WorkPackageStatus::candidateSessions)
                .containsExactly(1, 0);
        assertThat(statuses).extracting(DesignerSessionService.WorkPackageStatus::candidateSubmissions)
                .containsExactly(2, 0);
        assertThat(statuses).extracting(DesignerSessionService.WorkPackageStatus::compilationSource)
                .containsExactly("MCP_ACCEPTED", "MARKDOWN_FALLBACK");
        assertThat(statuses).extracting(DesignerSessionService.WorkPackageStatus::fallbackReason)
                .containsExactly(null, "FEATURE_DISABLED");
        assertThat(statuses).extracting(DesignerSessionService.WorkPackageStatus::serverCompiled)
                .containsExactly(true, false);
        verify(mapper, never()).countCandidateSubmissionRunsForCompilation(anyString());
        verify(mapper, never()).countCandidateSubmissionAttemptsForCompilation(anyString());
    }

    @Test
    void readsThePendingCandidateFromTheNextDesignRevisionBeforeCompilationExists() {
        when(mapper.findCurrentDesignRequirementRevision("designer")).thenReturn(Optional.of(
                new DesignRequirementRevisionRow("requirement", "designer", 1, "message", "requirement",
                        "[]", 1, "ACTIVE", 0, 96, "now", "now", 0)));
        when(mapper.listDesignWorkPackages("requirement")).thenReturn(List.of(
                workPackage("package-mcp", "WP-1")));
        when(mapper.findLatestLoopSpecCompilationForPackage("designer", "WP-1"))
                .thenReturn(Optional.empty());
        when(mapper.findWorkPackageRoleProfile("package-mcp")).thenReturn(Optional.empty());
        when(mapper.findLatestCandidateSubmissionRunForWorkPackage("package-mcp", 2L))
                .thenReturn(Optional.of(candidateRun(2L, "OPEN")));
        when(mapper.countCandidateSubmissionAttemptsForRun("run")).thenReturn(1);

        DesignerSessionService.WorkPackageStatus status = projector.workPackages("designer").getFirst();

        assertThat(status.candidateRunState()).isEqualTo("OPEN");
        assertThat(status.candidateSessions()).isEqualTo(1);
        assertThat(status.candidateSubmissions()).isEqualTo(1);
        assertThat(status.compilationSource()).isNull();
    }

    private static DesignWorkPackageRow workPackage(String id, String packageId) {
        return new DesignWorkPackageRow(id, "designer", "requirement", "decomposition",
                packageId, "WP-1".equals(packageId) ? 0 : 1, packageId, "objective", "[]", "[]",
                "[]", "[]", "[]", "[]", "DESIGNING", null, null, null, 1, 0, 0,
                null, null, null, null, null, 0, null, null, "now", "now", 0);
    }

    private static LoopSpecCompilationRow compilation(String id, String packageId, boolean serverCompiled,
                                                       String source, String fallbackReason) {
        return new LoopSpecCompilationRow(id, "designer", 1, "COMPLETED", null, null, 0,
                "message", 1, null, null, "now", "now", 0, packageId, 0, "{}",
                "FINAL_JSON", null, 0, "TEXT_MARKER", null, false, "TEXT_MARKER", null,
                false, null, 0, 0, serverCompiled, source, fallbackReason);
    }

    private static CandidateSubmissionRunRow candidateRun() {
        return candidateRun(1L, "ACCEPTED");
    }

    private static CandidateSubmissionRunRow candidateRun(long sourceRevision, String state) {
        return new CandidateSubmissionRunRow("run", "designer", null, null, "package-mcp",
                "PACKAGE_DESIGN_V1", "PACKAGE_DESIGN_V1", sourceRevision, 0, "INTERNAL_MCP", "PACKAGE_DESIGN_V1",
                "generation", "remote", state, 5, 2, "attempt-2", "now", "now", 2);
    }
}
