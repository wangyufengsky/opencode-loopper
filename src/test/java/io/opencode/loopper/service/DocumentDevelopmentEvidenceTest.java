package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DocumentDevelopmentEvidenceTest {
    private final DocumentDevelopmentEvidenceMapper mapper = mock(DocumentDevelopmentEvidenceMapper.class);
    private final DocumentDevelopmentCompletion completion = mock(DocumentDevelopmentCompletion.class);
    private final DocumentTemplateAdmission admission = mock(DocumentTemplateAdmission.class);
    private final DocumentRequirementMapper requirements = mock(DocumentRequirementMapper.class);
    private final LoopperMapper domain = mock(LoopperMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private DocumentDevelopmentEvidence evidence;
    private DocumentTemplateRunRow run;
    private PackageDesignAcceptedResultRow accepted;
    private final TaskRow task = mock(TaskRow.class);
    @BeforeEach void prepare() {
        var manager = mock(PlatformTransactionManager.class); when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        evidence = new DocumentDevelopmentEvidence(mapper, completion, admission, requirements, domain, json, manager);
        run = new DocumentTemplateRunRow("run", "request", "hash", "project", "REQUIREMENT_DEVELOPMENT", "1", "开发", "EXECUTING",
                null, null, null, "{}", "designer", "task", 1, null, null, 0, "now", "now", 3);
        var proof = new DocumentDevelopmentCompletion.Proof("task", 7, "AWAITING_DECISION", "cycle", 4, "batch", List.of(),
                List.of(new DocumentDevelopmentCompletion.Stage("stage", 0, "WP-1", "pack", "最终回归", "attempt",
                        List.of(new DocumentDevelopmentCompletion.TestEvidence("test", "PASS", List.of("EventBusTest"), "通过", "now")))));
        when(completion.require("task")).thenReturn(proof); when(admission.require("run")).thenReturn(run);
        when(task.id()).thenReturn("task"); when(task.version()).thenReturn(7L); when(domain.findTask("task")).thenReturn(Optional.of(task));
        var cycle = mock(TaskExecutionCycleRow.class); when(cycle.version()).thenReturn(4L); when(domain.latestTaskExecutionCycle("task")).thenReturn(Optional.of(cycle));
        var pack = new TaskPackageRunRow("pack", "task", "plan", "design", "WP-1", 0, "第一包", "SUCCEEDED", null, 0, 1, 1, null, "now", "now", 4);
        when(domain.listTaskPackageRuns("task")).thenReturn(List.of(pack));
        var design = mock(DesignWorkPackageRow.class); when(design.id()).thenReturn("design"); when(design.packageId()).thenReturn("WP-1");
        when(domain.findDesignWorkPackage("design")).thenReturn(Optional.of(design));
        when(domain.documentPackageDesign("design", "designer")).thenReturn(Optional.of(
                new DocumentDevelopmentMapper.Design("design-revision", "run", 1, "manifest", "now")));
        var fixture = new PackageDesignV2CompilationTest(); var candidate = fixture.candidate();
        ((ObjectNode) candidate.path("sourceBindings").get(0)).set("sourceRefs", json.valueToTree(List.of("RQ-1", DocumentRequirementContext.FINAL_REGRESSION)));
        accepted = new PackageDesignAcceptedResultRow("candidate", "design", 1, 0, "PACKAGE_DESIGN_V2", json.writeValueAsString(candidate), "", "{}", "hash", "compiled", "now", "now", 1);
        when(mapper.accepted("design", 1)).thenReturn(Optional.of(accepted)); when(mapper.insert(any())).thenReturn(1);
        when(requirements.page("run", 1, -1, 100)).thenReturn(List.of(requirement("RQ-1")));
        when(requirements.item("run", 1, "RQ-1")).thenReturn(Optional.of(requirement("RQ-1")));
        when(requirements.basis("run", 1)).thenReturn(Optional.of(new DocumentSourceMapper.Basis("run", 1, "REQUIREMENT_LIST", "manifest", "[]", "now")));
    }
    @Test void freezesApprovedMappingsWithActualStageAttemptAndTestIdentities() {
        evidence.freeze(run);
        var captured = org.mockito.ArgumentCaptor.forClass(DocumentDevelopmentEvidenceMapper.Evidence.class); verify(mapper).insert(captured.capture());
        var stored = captured.getValue(); var snapshot = json.readValue(stored.contentJson(), DocumentDevelopmentEvidence.Snapshot.class);
        assertThat(stored.sha256()).isEqualTo(DocumentModelStore.hash(stored.contentJson()));
        assertThat(snapshot.requirements().get("RQ-1")).singleElement().satisfies(mapping -> {
            assertThat(mapping.candidateRunId()).isEqualTo("candidate"); assertThat(mapping.stageId()).isEqualTo("stage");
            assertThat(mapping.attemptId()).isEqualTo("attempt"); assertThat(mapping.testResultIds()).containsExactly("test");
        });
        verify(admission).transition(run, DocumentTemplateState.REPORTING, LifecycleEvent.RENDER_REQUIREMENT_REPORT, null, null);
        when(mapper.find("run")).thenReturn(Optional.of(stored)); when(task.version()).thenReturn(99L);
        assertThat(evidence.read(run)).isEqualTo(snapshot);
    }
    @Test void requirementOmissionCannotBeHiddenBySuccessfulTask() {
        when(requirements.page("run", 1, -1, 100)).thenReturn(List.of(requirement("RQ-1"), requirement("RQ-2")));
        assertThatThrownBy(() -> evidence.freeze(run)).isInstanceOf(ConflictException.class); verify(mapper, never()).insert(any());
    }
    @Test void sameRequirementKeyWithChangedRuleCannotReuseOldExecutionEvidence() {
        when(domain.documentPackageDesign("design", "designer")).thenReturn(Optional.of(
                new DocumentDevelopmentMapper.Design("design-revision", "run", 2, "old-manifest", "earlier")));
        when(requirements.item("run", 2, "RQ-1")).thenReturn(Optional.of(new DocumentRequirementMapper.Requirement(
                "run", 2, "RQ-1", 0, "事件安全", "事件", "FUNCTION", "未注册事件必须报错", "[]", "[]", "[]")));
        assertThatThrownBy(() -> evidence.freeze(run)).isInstanceOf(ConflictException.class);
        verify(mapper, never()).insert(any());
    }
    @Test void missingApprovedCandidateAndConcurrentExecutionChangePreventFreezing() {
        when(mapper.accepted("design", 1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> evidence.freeze(run)).isInstanceOf(ConflictException.class);
        when(mapper.accepted("design", 1)).thenReturn(Optional.of(accepted)); when(task.version()).thenReturn(8L);
        assertThatThrownBy(() -> evidence.freeze(run)).isInstanceOf(ConflictException.class); verify(mapper, never()).insert(any());
    }
    private DocumentRequirementMapper.Requirement requirement(String key) {
        return new DocumentRequirementMapper.Requirement("run", 1, key, 0, "事件安全", "事件", "FUNCTION", "未注册事件安全忽略", "[]", "[]", "[]");
    }
}
