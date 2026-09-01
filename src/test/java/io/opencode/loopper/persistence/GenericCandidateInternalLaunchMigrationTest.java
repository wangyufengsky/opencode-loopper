package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenericCandidateInternalLaunchMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void addsGenericLaunchProtocolWithoutReplacingAcceptanceProtocol() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("generic-launch-schema.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            Set<String> tables = new HashSet<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }

            assertThat(tables).contains(
                    "ai_candidate_internal_launch",
                    "ai_candidate_internal_launch_cleanup_remote",
                    "ai_candidate_internal_launch_settlement_certificate",
                    "ai_candidate_internal_launch_run_requirement",
                    "ai_candidate_internal_termination_intent",
                    "acceptance_candidate_internal_launch",
                    "acceptance_candidate_internal_launch_cleanup_remote",
                    "acceptance_candidate_internal_launch_settlement_certificate",
                    "acceptance_candidate_internal_launch_run_requirement",
                    "acceptance_candidate_internal_termination_intent");

            Set<String> promptColumns = new HashSet<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_prompt_dispatch)")) {
                while (result.next()) {
                    promptColumns.add(result.getString("name"));
                }
            }
            assertThat(promptColumns).contains("internal_launch_id", "candidate_launch_id");
            try (var result = statement.executeQuery("""
                    SELECT sql FROM sqlite_master
                    WHERE type='index' AND name='ux_ai_candidate_internal_launch_active_owner'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("sql")).contains("'SETTLED'");
            }
        }
    }

    @Test
    void enforcesExactKindScopeAndTypedOwnerAnchors() throws Exception {
        String url = migratedDatabase("owner-anchors.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertGenericOwnerFixture(statement);

            assertThatThrownBy(() -> statement.executeUpdate(reviewerLaunchInsert(
                    "bad-reviewer-launch", "bad-reviewer-run", "s-other", 0)))
                    .hasMessageContaining("Reviewer owner anchor mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(reviewerLaunchInsert(
                    "bad-contract-launch", "bad-contract-run", "s", 0).replace(
                    "'REVIEWER_REPORT_V1',7,'REVIEWER_REPORT_V1',3",
                    "'WRONG_STEP',7,'REVIEWER_REPORT_V1',3")))
                    .hasMessageContaining("CHECK constraint failed");
            statement.executeUpdate(reviewerLaunchInsert("reviewer-launch", "reviewer-run", "s", 0));
            statement.executeUpdate(conventionLaunchInsert("convention-launch", "convention-run", 0));
            statement.executeUpdate(judgeLaunchInsert("judge-launch", "judge-candidate-run", 0));

            assertThat(count(statement, "ai_candidate_internal_launch")).isEqualTo(3);
        }
    }

    @Test
    void keepsLegacyReviewerConventionAndJudgeRunsOutsideTheGenericLaunchProtocol() throws Exception {
        String url = migratedDatabase("legacy-dual-entry.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertGenericOwnerFixture(statement);
            insertRuntimeBinding(statement, "legacy-reviewer-remote");
            insertRuntimeBinding(statement, "legacy-convention-remote");
            insertRuntimeBinding(statement, "legacy-judge-remote");

            statement.executeUpdate(legacyRunInsert(
                    "legacy-reviewer", "s", null, null, "ANALYSIS_REPORT", "report",
                    "REVIEWER_REPORT_V1", "legacy-reviewer-remote", 3));
            statement.executeUpdate(legacyRunInsert(
                    "legacy-convention", null, null, "p", "PROJECT_CONVENTION_DRAFT", "convention",
                    "PROJECT_CONVENTION_V1", "legacy-convention-remote", 3));
            statement.executeUpdate(legacyRunInsert(
                    "legacy-judge", null, "task", null, "JUDGE_RUN", "judge",
                    "JUDGE_DECISION_V1", "legacy-judge-remote", 2));
            statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET attempts_used=1,updated_at='later',version=version+1
                    WHERE id IN ('legacy-reviewer','legacy-convention','legacy-judge')
                    """);

            assertThat(count(statement, "ai_candidate_submission_run")).isEqualTo(3);
            assertThat(count(statement, "ai_candidate_internal_launch")).isZero();
            assertThat(count(statement, "ai_candidate_internal_launch_run_requirement")).isZero();
        }
    }

    @Test
    void requiresAtomicRunSettlementAndTypedAcknowledgedInitialPromptForZeroSubmission() throws Exception {
        String url = migratedDatabase("settlement-and-prompt.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertGenericOwnerFixture(statement);
            statement.executeUpdate(reviewerLaunchInsert("reviewer-launch", "reviewer-run", "s", 0));
            insertRuntimeBinding(statement, "reviewer-remote");
            markReviewerLaunchCreatedAndAttachOwner(statement);

            assertThatThrownBy(() -> statement.executeUpdate(reviewerRunInsert()))
                    .hasMessageContaining("FOREIGN KEY constraint failed");
            assertThat(count(statement, "ai_candidate_submission_run")).isZero();

            settleReviewerLaunch(connection, statement);
            assertThat(count(statement, "ai_candidate_internal_launch_run_requirement")).isOne();
            assertThat(count(statement, "ai_candidate_internal_launch_settlement_certificate")).isOne();

            assertThatThrownBy(() -> statement.executeUpdate(promptInsert(
                    "missing-launch-prompt", null, null, "PROMPTING", 0)))
                    .hasMessageContaining("exact SETTLED launch");
            assertThatThrownBy(() -> statement.executeUpdate(promptInsert(
                    "dual-launch-prompt", "reviewer-launch", "missing-acceptance-launch", "PROMPTING", 0)))
                    .hasMessageContaining("mutually exclusive");

            statement.executeUpdate(promptInsert(
                    "initial-prompt", "reviewer-launch", null, "PROMPTING", 0));
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET state='CLOSED',close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION',version=version+1
                    WHERE id='reviewer-run'
                    """))
                    .hasMessageContaining("acknowledged initial prompt");

            statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET dispatch_attempted=1,dispatch_started_at='dispatch-at',acknowledged=1,
                      acked_at='acked-at',state='ACKNOWLEDGED',updated_at='acked-at',version=version+1
                    WHERE id='initial-prompt'
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET state='CLOSED',close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION',version=version+1
                    WHERE id='reviewer-run'
                    """);
            assertThat(count(statement, "ai_candidate_submission_run")).isOne();
        }
    }

    @Test
    void bindsCorrectionsToTheSameLaunchAndFreezesProgressUnderTerminationIntent() throws Exception {
        String url = migratedDatabase("correction-and-termination.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertGenericOwnerFixture(statement);
            statement.executeUpdate(reviewerLaunchInsert("reviewer-launch", "reviewer-run", "s", 0));
            insertRuntimeBinding(statement, "reviewer-remote");
            markReviewerLaunchCreatedAndAttachOwner(statement);
            settleReviewerLaunch(connection, statement);
            statement.executeUpdate(promptInsert(
                    "initial-prompt", "reviewer-launch", null, "PROMPTING", 0));
            statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET dispatch_attempted=1,dispatch_started_at='dispatch-at',acknowledged=1,
                      acked_at='acked-at',state='ACKNOWLEDGED',updated_at='acked-at',version=version+1
                    WHERE id='initial-prompt'
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                      problems_json,response_json,created_at)
                    VALUES('rejected-attempt','reviewer-run',1,'key-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'REJECTED',1,'[]','{}','now')
                    """);
            statement.executeUpdate(
                    "UPDATE ai_candidate_submission_run SET attempts_used=1,version=version+1 WHERE id='reviewer-run'");

            assertThatThrownBy(() -> statement.executeUpdate(correctionPromptInsert(
                    "correction-without-launch", null)))
                    .hasMessageContaining("exact SETTLED launch");
            statement.executeUpdate(correctionPromptInsert("correction-prompt", "reviewer-launch"));

            assertThatThrownBy(() -> statement.executeUpdate(terminationCleanupInsert()))
                    .hasMessageContaining("cleanup parent is not accepting registrations");
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_internal_termination_intent(
                      id,launch_id,candidate_run_id,intent_kind,target_launch_state,state,
                      anchor_owner_version,created_at,updated_at)
                    VALUES('termination','reviewer-launch','reviewer-run','PROTOCOL_FAILURE',
                      'FAILED_STOPPED','REQUESTED',1,'now','now')
                    """);
            statement.executeUpdate(terminationCleanupInsert());
            assertThat(count(statement, "ai_candidate_internal_launch_cleanup_remote")).isOne();
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                      problems_json,response_json,created_at)
                    VALUES('blocked-attempt','reviewer-run',2,'key-2',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'REJECTED',1,'[]','{}','now')
                    """))
                    .hasMessageContaining("submission blocked by termination intent");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "DELETE FROM ai_candidate_internal_launch WHERE id='reviewer-launch'"))
                    .hasMessageContaining("live generic candidate launch");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE analysis_report SET state='FAILED' WHERE id='report'"))
                    .hasMessageContaining("live generic candidate protocol");
        }
    }

    private String migratedDatabase(String name) {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve(name);
        Flyway.configure().dataSource(url, null, null).load().migrate();
        return url;
    }

    private void insertGenericOwnerFixture(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO project(id,name,root_path,created_at,updated_at)
                VALUES('p','P','/tmp/p','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at)
                VALUES('d','p','Goal','{}','DRAFT_READY','now','now'),
                      ('d-other','p','Other','{}','DRAFT_READY','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at)
                VALUES('s','p','RUNNING','READ_ONLY','d','now','now'),
                      ('s-other','p','RUNNING','READ_ONLY','d-other','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_message(
                  id,designer_session_id,ordinal,role,content,delivery_state,created_at)
                VALUES('m','s',1,'ASSISTANT','requirement','PERSISTED','now')
                """);
        statement.executeUpdate("""
                INSERT INTO design_requirement_revision(
                  id,designer_session_id,revision,source_message_id,requirement_text,
                  requirement_segments_json,source_draft_version,state,created_at,updated_at)
                VALUES('r','s',1,'m','requirement','[]',0,'ACTIVE','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_task_profile(
                  id,designer_session_id,requirement_revision_id,state,intent,workflow_template,
                  mutation_mode,artifact_kinds_json,technologies_json,test_policy,execution_strategy,
                  role_pack_id,role_pack_version,confidence,evidence_json,resolution_source,
                  decision_required,created_at,updated_at)
                VALUES('profile','s','r','FROZEN','READ_ONLY_REVIEW','REVIEWER_REPORT','READ_ONLY',
                  '[]','[]','NOT_APPLICABLE','OPENCODE','reviewer','2026-08-dynamic-v7',90,'[]',
                  'USER_CONFIRMED',0,'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO analysis_report(
                  id,designer_session_id,task_profile_id,state,title,markdown,evidence_json,
                  created_at,updated_at,version)
                VALUES('report','s','profile','RUNNING','Report','','[]','now','now',0)
                """);
        statement.executeUpdate("""
                INSERT INTO project_convention_draft(
                  id,project_id,state,source_exists,source_sha256,source_content,created_at,updated_at,version)
                VALUES('convention','p','RUNNING',0,
                  'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd','',
                  'now','now',0)
                """);
        statement.executeUpdate("""
                INSERT INTO task(id,project_id,loop_draft_id,title,state,created_at,updated_at)
                VALUES('task','p','d','Task','RUNNING','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO stage(
                  id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,
                  deliverables_json,verifiers_json,state,created_at,updated_at)
                VALUES('stage','task',0,'Stage','[]','[]','[]','[]','RUNNING','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at)
                VALUES('attempt','task','stage',1,'RUNNING','now')
                """);
        statement.executeUpdate("""
                INSERT INTO judge_run(id,task_id,attempt_id,role,ordinal,state,created_at,version)
                VALUES('judge','task','attempt','REQUIREMENT',1,'CREATING','now',0)
                """);
    }

    private String reviewerLaunchInsert(String launchId, String runId, String designerSessionId, int ownerVersion) {
        return launchInsert(launchId, runId, "REVIEWER_REPORT_V1", designerSessionId, null, null,
                "ANALYSIS_REPORT", "report", "report", null, null, 3, ownerVersion, "R");
    }

    private String conventionLaunchInsert(String launchId, String runId, int ownerVersion) {
        return launchInsert(launchId, runId, "PROJECT_CONVENTION_V1", null, null, "p",
                "PROJECT_CONVENTION_DRAFT", "convention", null, "convention", null,
                3, ownerVersion, "C");
    }

    private String judgeLaunchInsert(String launchId, String runId, int ownerVersion) {
        return launchInsert(launchId, runId, "JUDGE_DECISION_V1", null, "task", null,
                "JUDGE_RUN", "judge", null, null, "judge", 2, ownerVersion, "J");
    }

    private String launchInsert(
            String launchId,
            String runId,
            String kind,
            String designerSessionId,
            String taskId,
            String projectId,
            String ownerType,
            String ownerId,
            String analysisReportId,
            String conventionId,
            String judgeRunId,
            int maxAttempts,
            int ownerVersion,
            String credentialCharacter) {
        return """
                INSERT INTO ai_candidate_internal_launch(
                  id,candidate_run_id,candidate_kind,designer_session_id,task_id,project_id,
                  owner_type,owner_id,analysis_report_id,project_convention_draft_id,judge_run_id,
                  workflow_step,source_revision,contract_version,max_attempts,state,prepared_owner_version,
                  exact_title,canonical_directory,runtime_generation_id,managed,internal_mcp_server,
                  endpoint_fingerprint,profile,permission_policy_json,permission_policy_digest,
                  create_request_sha256,creation_credential,attestation_type,created_at,updated_at,version)
                VALUES('%s','%s','%s',%s,%s,%s,'%s','%s',%s,%s,%s,
                  '%s',7,'%s',%d,'PREPARED',%d,'%s','/tmp/p','generation-1',1,
                  'loopper_internal_generic','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'GENERIC_CANDIDATE_NO_TOOLS','[]',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                  '%s','LOCAL_REQUEST_ATTESTED','now','now',0)
                """.formatted(
                launchId, runId, kind, sqlString(designerSessionId), sqlString(taskId), sqlString(projectId),
                ownerType, ownerId, sqlString(analysisReportId), sqlString(conventionId), sqlString(judgeRunId),
                kind, kind, maxAttempts, ownerVersion, launchId, credentialCharacter.repeat(43));
    }

    private void insertRuntimeBinding(Statement statement, String remoteId) throws Exception {
        statement.executeUpdate("""
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                  internal_mcp_server,created_at)
                VALUES('%s','generation-1','MANAGED',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'loopper_internal_generic','now')
                """.formatted(remoteId));
    }

    private void markReviewerLaunchCreatedAndAttachOwner(Statement statement) throws Exception {
        statement.executeUpdate("""
                UPDATE ai_candidate_internal_launch
                SET state='CREATED',create_claim_owner='worker',create_claim_token='claim',
                  create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                  create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                  external_session_id='reviewer-remote',external_attested_at='attested-at',version=version+1
                WHERE id='reviewer-launch'
                """);
        statement.executeUpdate("""
                UPDATE analysis_report
                SET external_session_id='reviewer-remote',external_session_state='CANDIDATE_PROMPT_PENDING',
                  version=1,updated_at='attached-at'
                WHERE id='report'
                """);
    }

    private String reviewerRunInsert() {
        return """
                INSERT INTO ai_candidate_submission_run(
                  id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                  owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                  state,max_attempts,attempts_used,created_at,updated_at,version)
                VALUES('reviewer-run','s','ANALYSIS_REPORT','report','REVIEWER_REPORT_V1',
                  'REVIEWER_REPORT_V1',7,1,'INTERNAL_MCP','REVIEWER_REPORT_V1','generation-1',
                  'reviewer-remote','OPEN',3,0,'now','now',0)
                """;
    }

    private String legacyRunInsert(
            String id, String designerSessionId, String taskId, String projectId,
            String ownerType, String ownerId, String kind, String remoteId, int maxAttempts) {
        return """
                INSERT INTO ai_candidate_submission_run(
                  id,designer_session_id,task_id,project_id,owner_type,owner_id,candidate_kind,
                  workflow_step,source_revision,owner_version,submission_channel,contract_version,
                  runtime_generation_id,external_session_id,state,max_attempts,attempts_used,
                  created_at,updated_at,version)
                VALUES('%s',%s,%s,%s,'%s','%s','%s','%s',7,0,'IN_PROCESS_LEGACY','%s',
                  'generation-1','%s','OPEN',%d,0,'now','now',0)
                """.formatted(id, sqlString(designerSessionId), sqlString(taskId), sqlString(projectId),
                ownerType, ownerId, kind, kind, kind, remoteId, maxAttempts);
    }

    private void settleReviewerLaunch(Connection connection, Statement statement) throws Exception {
        connection.setAutoCommit(false);
        try {
            statement.executeUpdate(reviewerRunInsert());
            statement.executeUpdate("""
                    UPDATE ai_candidate_internal_launch
                    SET state='SETTLED',settled_owner_version=1,settled_at='settled-at',
                      create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
                      version=version+1
                    WHERE id='reviewer-launch'
                    """);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private String promptInsert(
            String promptId, String candidateLaunchId, String acceptanceLaunchId, String state, int acknowledged) {
        String acknowledgedFields = acknowledged == 1 ? ",1,'acked-at'" : ",0,NULL";
        return """
                INSERT INTO ai_candidate_prompt_dispatch(
                  id,run_id,dispatch_kind,external_session_id,runtime_generation_id,message_id,
                  request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                  acknowledged,acked_at,created_at,updated_at,internal_launch_id,candidate_launch_id)
                VALUES('%s','reviewer-run','INITIAL','reviewer-remote','generation-1','%s-message',
                  '{}','dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                  '%s',1,'model-call-at'%s,'now','now',%s,%s)
                """.formatted(promptId, promptId, state, acknowledgedFields,
                sqlString(acceptanceLaunchId), sqlString(candidateLaunchId));
    }

    private String correctionPromptInsert(String promptId, String candidateLaunchId) {
        return """
                INSERT INTO ai_candidate_prompt_dispatch(
                  id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,
                  message_id,request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                  created_at,updated_at,candidate_launch_id)
                VALUES('%s','reviewer-run','CORRECTION',1,'reviewer-remote','generation-1',
                  '%s-message','{}',
                  'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                  'PROMPTING',1,'model-call-at','now','now',%s)
                """.formatted(promptId, promptId, sqlString(candidateLaunchId));
    }

    private String terminationCleanupInsert() {
        return """
                INSERT INTO ai_candidate_internal_launch_cleanup_remote(
                  launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                  directory_sha256,title_sha256,purpose,state,created_at,updated_at,version)
                VALUES('reviewer-launch','reviewer-remote','generation-1',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                  'TERMINATION','DISCOVERED','now','now',0)
                """;
    }

    private String sqlString(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private int count(Statement statement, String table) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
