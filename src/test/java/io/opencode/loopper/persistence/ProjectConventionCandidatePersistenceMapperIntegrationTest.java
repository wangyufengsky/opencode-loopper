package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h",
        "loopper.data-dir=target/project-convention-candidate-persistence-mapper-test"
})
class ProjectConventionCandidatePersistenceMapperIntegrationTest {
    private static final String SOURCE = "# Human project rules\n";
    private static final String SOURCE_SHA = "a".repeat(64);
    private static final String STACK_FINGERPRINT = "b".repeat(64);
    private static final String EVIDENCE = """
            {"analysisState":"READY","components":[{"componentKey":"root","commands":[["mvn","test"]],"paths":["pom.xml"]}]}
            """.strip();

    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoopperMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        jdbc.update("""
                INSERT INTO project(id,name,root_path,created_at,updated_at)
                VALUES('p','P','/tmp/p','now','now')
                """);
        jdbc.update("""
                INSERT INTO project_stack_profile(
                  id,project_id,analysis_state,manifest_fingerprint,analyzed_at,created_at)
                VALUES('stack','p','READY',?,'now','now')
                """, STACK_FINGERPRINT);
    }

    @Test
    void roundTripsRestartIdentityFrozenEvidenceAndOneWayAcceptedSettlement() {
        ProjectConventionDraftRow draft = new ProjectConventionDraftRow(
                "draft", "p", "RUNNING", null, null, 1, SOURCE_SHA, SOURCE,
                null, null, null, "now", "now", 0, "stack", STACK_FINGERPRINT,
                "INTERNAL_MCP", 7L);
        assertThat(mapper.insertProjectConventionDraft(draft)).isEqualTo(1);
        assertThat(mapper.findProjectConventionDraft("draft")).contains(draft);

        ProjectConventionCandidateSourceSnapshotRow snapshot =
                new ProjectConventionCandidateSourceSnapshotRow(
                        "run", "p", "draft", 7, 0, "PROJECT_CONVENTION_V1", 1,
                        SOURCE_SHA, SOURCE, SOURCE_SHA, "stack", STACK_FINGERPRINT,
                        EVIDENCE, "d".repeat(64), "snapshot-at");
        assertThat(mapper.insertProjectConventionCandidateSourceSnapshot(snapshot)).isEqualTo(1);
        assertThat(mapper.findProjectConventionCandidateSourceSnapshot("run")).contains(snapshot);

        createAndSettleGenericLaunch();
        ProjectConventionCandidateAcceptedResultRow accepted =
                new ProjectConventionCandidateAcceptedResultRow(
                        "run", "p", "draft", 7, 1, "PROJECT_CONVENTION_V1",
                        "{\"contractVersion\":\"PROJECT_CONVENTION_V1\",\"componentKeys\":[\"root\"]}",
                        "a".repeat(64), "b".repeat(64), "# Proposed convention\n",
                        "c".repeat(64), null, "accepted-at", "accepted-at", 0);
        assertThat(mapper.insertProjectConventionCandidateAcceptedResult(accepted)).isEqualTo(1);
        assertThat(mapper.findProjectConventionCandidateAcceptedResult("run")).contains(accepted);
        assertThat(mapper.listUnsettledProjectConventionCandidateAcceptedResults())
                .containsExactly(accepted);

        markRunAccepted();
        assertThat(mapper.settleProjectConventionCandidateAcceptedResult(
                "run", 0, "draft", "settled-at")).isEqualTo(1);
        assertThat(mapper.settleProjectConventionCandidateAcceptedResult(
                "run", 0, "draft", "duplicate-at")).isZero();
        assertThat(mapper.findProjectConventionCandidateAcceptedResult("run"))
                .hasValueSatisfying(result -> {
                    assertThat(result.settledDraftId()).isEqualTo("draft");
                    assertThat(result.updatedAt()).isEqualTo("settled-at");
                    assertThat(result.version()).isEqualTo(1);
                    assertThat(result.canonicalCandidateJson()).isEqualTo(accepted.canonicalCandidateJson());
                    assertThat(result.proposedContent()).isEqualTo(accepted.proposedContent());
                });
        assertThat(mapper.listUnsettledProjectConventionCandidateAcceptedResults()).isEmpty();
    }

    private void createAndSettleGenericLaunch() {
        jdbc.update("""
                INSERT INTO ai_candidate_internal_launch(
                  id,candidate_run_id,candidate_kind,project_id,owner_type,owner_id,
                  project_convention_draft_id,workflow_step,source_revision,contract_version,
                  max_attempts,state,prepared_owner_version,exact_title,canonical_directory,
                  runtime_generation_id,managed,internal_mcp_server,endpoint_fingerprint,profile,
                  permission_policy_json,permission_policy_digest,create_request_sha256,
                  creation_credential,attestation_type,created_at,updated_at,version)
                VALUES('launch','run','PROJECT_CONVENTION_V1','p','PROJECT_CONVENTION_DRAFT','draft',
                  'draft','PROJECT_CONVENTION_V1',7,'PROJECT_CONVENTION_V1',3,'PREPARED',0,
                  'convention-launch','/tmp/p','generation-1',1,'loopper_internal_generic',?,
                  'PROJECT_CONVENTION_CANDIDATE_READ_ONLY','[]',?,?,?,
                  'LOCAL_REQUEST_ATTESTED','now','now',0)
                """, "e".repeat(64), "f".repeat(64), "1".repeat(64), "C".repeat(43));
        jdbc.update("""
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                  internal_mcp_server,created_at)
                VALUES('remote','generation-1','MANAGED',?,'loopper_internal_generic','now')
                """, "e".repeat(64));
        jdbc.update("""
                UPDATE ai_candidate_internal_launch
                SET state='CREATED',create_claim_owner='worker',create_claim_token='claim',
                  create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                  create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                  external_session_id='remote',external_attested_at='attested-at',
                  updated_at='created-at',version=version+1 WHERE id='launch'
                """);
        jdbc.update("""
                UPDATE project_convention_draft
                SET external_session_id='remote',external_session_state='CANDIDATE_PROMPT_PENDING',
                  updated_at='attached-at',version=version+1 WHERE id='draft'
                """);

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO ai_candidate_submission_run(
                      id,project_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,
                      external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('run','p','PROJECT_CONVENTION_DRAFT','draft','PROJECT_CONVENTION_V1',
                      'PROJECT_CONVENTION_V1',7,1,'INTERNAL_MCP','PROJECT_CONVENTION_V1',
                      'generation-1','remote','OPEN',3,0,'now','now',0)
                    """);
            jdbc.update("""
                    UPDATE ai_candidate_internal_launch
                    SET state='SETTLED',settled_owner_version=1,settled_at='settled-at',
                      create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
                      updated_at='settled-at',version=version+1 WHERE id='launch'
                    """);
        });
    }

    private void markRunAccepted() {
        jdbc.update("""
                INSERT INTO ai_candidate_submission_attempt(
                  id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                  problems_json,response_json,canonical_result_sha256,created_at)
                VALUES('attempt','run',1,'key-1',?,'ACCEPTED',0,'[]','{}',?,'now')
                """, "2".repeat(64), "b".repeat(64));
        jdbc.update("""
                UPDATE ai_candidate_submission_run
                SET state='ACCEPTED',attempts_used=1,terminal_attempt_id='attempt',
                  updated_at='run-accepted-at',version=version+1 WHERE id='run'
                """);
    }
}
