package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h",
        "loopper.data-dir=target/acceptance-internal-launch-preparer-test"
})
class AcceptanceCandidateInternalLaunchPreparerTest {
    private static final String CONTRACT = "ACCEPTANCE_CLOSED_CHOICE_V7";
    private static final String PROFILE = "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS";
    private static final String INTERNAL_MCP = "loopper-private-7";
    private static final String ENDPOINT = "a".repeat(64);
    private static final String ROUTE = """
            {"contractVersion":"ACCEPTANCE_CLOSED_CHOICE_V7","serverResolved":false,"compilerRequired":true,
             "resolution":{"outcome":"NEEDS_COMPILER",
              "stageCandidates":[{"stageIndex":0,"title":"Implement","objective":"Implement safely","lockedFactIndexes":[0]}],
              "factAssignmentCandidates":[],"ambiguousCapabilityFactIndexes":[0],
              "tiedCapabilityIndexesByFact":{"0":[0,1]},"optimalTieChoiceSets":[[0],[1]],
              "trueCapabilityTieCount":2}}
            """.replaceAll("\\s+", "");
    private static final String BINDING =
            "{\"capabilityPreferences\":[{\"factIndex\":0,\"capabilityIndexes\":[1]}]}";

    @TempDir Path projectRoot;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoopperMapper mapper;

    private OpenCodeClient openCode;
    private AcceptanceCandidateInternalLaunchPlanCodec plans;
    private AcceptanceCandidateInternalLaunchPreparer preparer;

    @BeforeEach
    void setUp() throws Exception {
        flyway.clean();
        flyway.migrate();
        insertOwnerFixture("DESIGN_ACCEPTANCE_V7");
        openCode = mock(OpenCodeClient.class);
        plans = new AcceptanceCandidateInternalLaunchPlanCodec(new ObjectMapper());
        preparer = new AcceptanceCandidateInternalLaunchPreparer(openCode,
                new AcceptanceCandidateInternalLaunchGuard(mapper, new ObjectMapper()),
                new AcceptanceCandidateInternalLaunchStore(mapper), plans,
                new AcceptanceCandidateCreationCredentialSource(), new ObjectMapper());
        when(openCode.prepareCandidateSessionCreationLocally(any(), anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> localPlan(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(4)));
    }

    @Test
    void preparesAndPersistsOneExactLocalPlanWithoutCrossingAnyRemoteBoundary() {
        var command = command(model("opencode-go", "deepseek-v4-flash", false));

        AcceptanceCandidateInternalLaunchPreparer.Prepared first = preparer.prepare(command);
        AcceptanceCandidateInternalLaunchPreparer.Prepared replay = preparer.prepare(command);

        assertThat(replay).isEqualTo(first);
        assertThat(first.row().state()).isEqualTo("PREPARED");
        assertThat(first.row().candidateRunId()).isNotEqualTo(first.row().id());
        assertThat(first.row().creationCredential()).matches("[A-Za-z0-9_-]{43}");
        assertThat(first.plan().exactTitle()).contains(first.row().candidateRunId(), first.row().id())
                .endsWith("[loopper-create:" + first.row().creationCredential() + "]");
        assertThat(plans.decode(mapper.findAcceptanceCandidateInternalLaunchForCompilation("cmp")
                .orElseThrow())).isEqualTo(first.plan());
        verify(openCode, times(1)).prepareCandidateSessionCreationLocally(
                projectRoot.toAbsolutePath().normalize(), first.plan().exactTitle().substring(0,
                        first.plan().exactTitle().indexOf(" [loopper-create:")), command.model(),
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                first.row().creationCredential());
        verify(openCode, never()).requireCandidateSessionReady(any());
        verify(openCode, never()).findSessionsByExactTitle(any(OpenCodeClient.SessionCreationPlan.class));
        verify(openCode, never()).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        verifyNoMoreInteractions(openCode);
    }

    @Test
    void pendingV7CompilationIsVisibleToTheMonitorBeforeAnyLaunchExists() {
        assertThat(mapper.activeLoopSpecCompilations())
                .extracting(io.opencode.loopper.persistence.LoopSpecCompilationRow::id)
                .containsExactly("cmp");
        assertThat(mapper.findAcceptanceCandidateInternalLaunchForCompilation("cmp")).isEmpty();
    }

    @Test
    void failsClosedBeforeLocalPlanningWhenSourcePlanningOrRouteFactsDrift() {
        var original = command(model("opencode-go", "deepseek-v4-flash", false));

        assertThatThrownBy(() -> preparer.prepare(withSourceSha(original, "f".repeat(64))))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_SOURCE_STALE"));
        assertThatThrownBy(() -> preparer.prepare(withPlanningVersion(original, 2)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_PLANNING_STALE"));
        assertThatThrownBy(() -> preparer.prepare(withRoute(original, ROUTE, "0".repeat(64))))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_ROUTE_INVALID"));
        String extra = ROUTE.substring(0, ROUTE.length() - 1) + ",\"extra\":true}";
        assertThatThrownBy(() -> preparer.prepare(withRoute(original, extra, sha256(extra))))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_ROUTE_INVALID"));
        verifyNoMoreInteractions(openCode);
    }

    @Test
    void requiresThePreSelectionV7PlanningContractInsteadOfACompiledBinding() {
        var command = command(model("opencode-go", "deepseek-v4-flash", false));

        jdbc.update("UPDATE design_acceptance_planning SET state='BOUND' WHERE compilation_id='cmp'");
        assertThatThrownBy(() -> preparer.prepare(command))
                .isInstanceOfSatisfying(ConflictException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_PLANNING_STALE");
                    assertThat(failure.getMessage()).contains("STATE");
                });

        flyway.clean();
        flyway.migrate();
        insertOwnerFixture("DESIGN_ACCEPTANCE_V6");
        assertThatThrownBy(() -> preparer.prepare(command))
                .isInstanceOfSatisfying(ConflictException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_PLANNING_STALE");
                    assertThat(failure.getMessage()).contains("STATE");
                });
        verifyNoMoreInteractions(openCode);
    }

    @Test
    void existingLaunchIsIdempotentOnlyForTheExactCommandAndPersistedPlan() {
        var original = command(model("opencode-go", "deepseek-v4-flash", false));
        AcceptanceCandidateInternalLaunchPreparer.Prepared prepared = preparer.prepare(original);

        assertThat(preparer.prepare(original)).isEqualTo(prepared);
        assertThatThrownBy(() -> preparer.prepare(command(model("opencode-go", "another-model", false))))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_REPLAY_MISMATCH"));

        AcceptanceCandidateInternalLaunchRow corrupted = copyPlan(prepared.row(),
                "[{\"permission\":\"*\",\"pattern\":\"*\",\"action\":\"deny\",\"extra\":true}]",
                prepared.row().permissionPolicyDigest(), prepared.row().modelProviderId(),
                prepared.row().modelId());
        assertThatThrownBy(() -> plans.decode(corrupted))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_PLAN_INVALID"));
        AcceptanceCandidateInternalLaunchRow halfModel = copyPlan(prepared.row(),
                prepared.row().permissionPolicyJson(), prepared.row().permissionPolicyDigest(), null,
                prepared.row().modelId());
        assertThatThrownBy(() -> plans.decode(halfModel))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACCEPTANCE_INTERNAL_LAUNCH_PLAN_INVALID"));
        verify(openCode, times(1)).prepareCandidateSessionCreationLocally(any(), anyString(), any(), any(), anyString());
    }

    private AcceptanceCandidateInternalLaunchPreparer.PrepareCommand command(
            OpenCodeClient.OpenCodeModel model) {
        return new AcceptanceCandidateInternalLaunchPreparer.PrepareCommand(
                "cmp", "s", "WP-1", 1, "m", 0, sha256("design"), 3,
                "AI_DISAMBIGUATION_V6", BINDING, sha256(BINDING), ROUTE, sha256(ROUTE), 4, model);
    }

    private AcceptanceCandidateInternalLaunchPreparer.PrepareCommand withSourceSha(
            AcceptanceCandidateInternalLaunchPreparer.PrepareCommand value, String sourceSha) {
        return new AcceptanceCandidateInternalLaunchPreparer.PrepareCommand(
                value.compilationId(), value.designerSessionId(), value.workPackageId(),
                value.sourceDesignRevision(), value.sourceDesignMessageId(), value.sourceDraftVersion(), sourceSha,
                value.planningVersion(), value.planningBindingSource(), value.planningBindingJson(),
                value.planningBindingSha256(), value.routePlanJson(), value.routePlanSha256(),
                value.preparedOwnerVersion(), value.model());
    }

    private AcceptanceCandidateInternalLaunchPreparer.PrepareCommand withPlanningVersion(
            AcceptanceCandidateInternalLaunchPreparer.PrepareCommand value, long planningVersion) {
        return new AcceptanceCandidateInternalLaunchPreparer.PrepareCommand(
                value.compilationId(), value.designerSessionId(), value.workPackageId(),
                value.sourceDesignRevision(), value.sourceDesignMessageId(), value.sourceDraftVersion(),
                value.sourceDesignSha256(), planningVersion, value.planningBindingSource(),
                value.planningBindingJson(), value.planningBindingSha256(), value.routePlanJson(),
                value.routePlanSha256(), value.preparedOwnerVersion(), value.model());
    }

    private AcceptanceCandidateInternalLaunchPreparer.PrepareCommand withRoute(
            AcceptanceCandidateInternalLaunchPreparer.PrepareCommand value, String route, String routeSha) {
        return new AcceptanceCandidateInternalLaunchPreparer.PrepareCommand(
                value.compilationId(), value.designerSessionId(), value.workPackageId(),
                value.sourceDesignRevision(), value.sourceDesignMessageId(), value.sourceDraftVersion(),
                value.sourceDesignSha256(), value.planningVersion(), value.planningBindingSource(),
                value.planningBindingJson(), value.planningBindingSha256(), route, routeSha,
                value.preparedOwnerVersion(), value.model());
    }

    private OpenCodeClient.SessionCreationPlan localPlan(Path root, String baseTitle,
            OpenCodeClient.OpenCodeModel model, String credential) throws Exception {
        List<OpenCodeClient.SessionPermissionRule> permissions = List.of(
                new OpenCodeClient.SessionPermissionRule("*", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule("external_directory", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule(INTERNAL_MCP + "_submit_candidate", "*", "allow"));
        String permissionDigest = OpenCodeClient.permissionPolicyDigest(permissions);
        String title = OpenCodeClient.recoveryTitle(baseTitle, credential);
        String requestDigest = OpenCodeClient.sessionCreationRequestSha256(root.toRealPath(), title,
                "generation-7", true, INTERNAL_MCP, ENDPOINT, model,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                permissionDigest, credential);
        return new OpenCodeClient.SessionCreationPlan(root.toRealPath(), title, "generation-7", true,
                INTERNAL_MCP, ENDPOINT, model,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                permissions, permissionDigest, credential, requestDigest);
    }

    private void insertOwnerFixture(String planningContract) {
        jdbc.update("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','P',?,'now','now')",
                projectRoot.toAbsolutePath().normalize().toString());
        jdbc.update("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('d','p','Goal','{}','DRAFT_READY','now','now')");
        jdbc.update("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,workflow_phase,"
                + "active_work_package_id,created_at,updated_at) "
                + "VALUES('s','p','RUNNING','READ_ONLY','d','COMPILING','WP-1','now','now')");
        jdbc.update("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) "
                + "VALUES('m','s',1,'ASSISTANT','design','PERSISTED','now')");
        jdbc.update("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,"
                + "requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) "
                + "VALUES('r','s',1,'m','requirement','[]',0,'ACTIVE','now','now')");
        jdbc.update("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,"
                + "source_draft_version,created_at,updated_at) VALUES('dec','s','r','RUNNING',0,'now','now')");
        jdbc.update("INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,decomposition_id,"
                + "package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,deliverables_json,"
                + "acceptance_intent_json,requirement_refs_json,state,design_revision,design_message_id,created_at,"
                + "updated_at) VALUES('wp','s','r','dec','WP-1',0,'Package','Deliver','[]','[]','[]','[]','[]','[]',"
                + "'COMPILING',1,'m','now','now')");
        jdbc.update("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,"
                + "source_design_message_id,source_draft_version,work_package_id,created_at,updated_at,version) "
                + "VALUES('cmp','s',1,'PENDING_HANDOFF','m',0,'WP-1','now','now',4)");
        jdbc.update("INSERT INTO design_acceptance_planning(compilation_id,designer_session_id,work_package_id,"
                + "design_revision,contract_version,design_sha256,state,facts_json,capabilities_json,binding_json,"
                + "diagnostics_json,created_at,updated_at,version,binding_source) VALUES('cmp','s','WP-1',1,"
                + "?,?,'EXTRACTED','[]','[]',?,'[]','now','now',3,"
                + "'AI_DISAMBIGUATION_V6')", planningContract, sha256("design"), BINDING);
    }

    private static OpenCodeClient.OpenCodeModel model(String provider, String id, Boolean thinking) {
        return new OpenCodeClient.OpenCodeModel(provider, id, thinking);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AcceptanceCandidateInternalLaunchRow copyPlan(
            AcceptanceCandidateInternalLaunchRow row, String permissionJson, String permissionDigest,
            String modelProvider, String modelId) {
        return new AcceptanceCandidateInternalLaunchRow(
                row.id(), row.compilationId(), row.designerSessionId(), row.workPackageId(),
                row.sourceDesignRevision(), row.sourceDesignMessageId(), row.sourceDraftVersion(),
                row.sourceDesignSha256(), row.planningVersion(), row.planningBindingSource(),
                row.planningBindingJson(), row.planningBindingSha256(), row.routePlanJson(),
                row.routePlanSha256(), row.candidateRunId(), row.contractVersion(), row.workflowStep(), row.state(),
                row.preparedOwnerVersion(), row.settledOwnerVersion(), row.settledAt(), row.exactTitle(),
                row.canonicalDirectory(), row.runtimeGenerationId(), row.managed(), row.internalMcpServer(),
                row.endpointFingerprint(), modelProvider, modelId, row.thinking(), row.profile(), permissionJson,
                permissionDigest, row.createRequestSha256(), row.creationCredential(), row.attestationType(),
                row.createClaimOwner(), row.createClaimToken(), row.createClaimExpiresAt(), row.createFence(),
                row.createDispatchAttempted(), row.createDispatchStartedAt(), row.externalSessionId(),
                row.externalAttestedAt(), row.terminationProof(), row.proofAt(), row.failurePhase(),
                row.lastErrorCode(), row.lastErrorDetail(), row.createdAt(), row.updatedAt(), row.version());
    }
}
