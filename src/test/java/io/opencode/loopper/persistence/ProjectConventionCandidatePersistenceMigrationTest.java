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
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectConventionCandidatePersistenceMigrationTest {
    private static final String SOURCE = "# Human project rules\n";
    private static final String SOURCE_SHA = "a".repeat(64);
    private static final String EVIDENCE = """
            {"analysisState":"READY","components":[{"componentKey":"root","commands":[["mvn","test"]],"paths":["pom.xml"]}]}
            """.strip();

    @TempDir Path temporaryDirectory;

    @Test
    void upgradesV61WithRestartIdentityAndImmutableConventionCandidateTables() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("convention-v62-upgrade.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("61")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO project(id,name,root_path,created_at,updated_at)
                    VALUES('legacy-project','Legacy','/tmp/legacy','now','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO project_convention_draft(
                      id,project_id,state,source_exists,source_sha256,source_content,
                      created_at,updated_at,version)
                    VALUES('legacy-draft','legacy-project','READY',0,
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','',
                      'now','now',0)
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("64");

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            Set<String> tables = new HashSet<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) tables.add(result.getString(1));
            }
            assertThat(tables).contains(
                    "project_convention_candidate_source_snapshot",
                    "project_convention_candidate_accepted_result");
            try (var result = statement.executeQuery("""
                    SELECT response_mode,source_revision
                    FROM project_convention_draft WHERE id='legacy-draft'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("response_mode")).isNull();
                assertThat(result.getObject("source_revision")).isNull();
            }
        }
    }

    @Test
    void freezesExactProjectDraftSourceAndEvidenceBeforeCreateDispatch() throws Exception {
        String url = migratedDatabase("convention-source-anchors.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertFixture(statement);
            statement.executeUpdate(conventionLaunchInsert("launch", "run", "draft", "p", 7, 0));

            assertThatThrownBy(() -> statement.executeUpdate(markCreateDispatch("launch")))
                    .hasMessageContaining("Convention create dispatch requires exact frozen source snapshot");
            assertThatThrownBy(() -> statement.executeUpdate(snapshotInsert(
                    "run", "draft", "other-project", 7, 0)))
                    .hasMessageContaining("Convention source snapshot owner mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(snapshotInsert(
                    "run", "draft", "p", 8, 0)))
                    .hasMessageContaining("Convention source snapshot owner mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(snapshotInsert(
                    "run", "draft", "p", 7, 1)))
                    .hasMessageContaining("Convention source snapshot owner mismatch");

            assertThat(statement.executeUpdate(snapshotInsert("run", "draft", "p", 7, 0))).isEqualTo(1);
            assertThat(statement.executeUpdate(markCreateDispatch("launch"))).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE project_convention_candidate_source_snapshot
                    SET canonical_evidence_json='{}' WHERE candidate_run_id='run'
                    """))
                    .hasMessageContaining("Convention source snapshot is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE project_convention_draft SET source_revision=8 WHERE id='draft'
                    """))
                    .hasMessageContaining("Convention candidate restart identity is immutable");

            assertThat(statement.executeUpdate(snapshotInsert(
                    "orphan-run", "cascade-draft", "p", 3, 0))).isEqualTo(1);
            assertThat(statement.executeUpdate("DELETE FROM project_convention_draft WHERE id='cascade-draft'"))
                    .isPositive();
            assertThat(count(statement,
                    "project_convention_candidate_source_snapshot", "candidate_run_id='orphan-run'"))
                    .isZero();
        }
    }

    @Test
    void acceptsOnlyTheExactConventionRunAndKeepsResultImmutableExceptOneWaySettlement() throws Exception {
        String url = migratedDatabase("convention-accepted-result.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertFixture(statement);
            statement.executeUpdate(snapshotInsert("run", "draft", "p", 7, 0));
            statement.executeUpdate(conventionLaunchInsert("launch", "run", "draft", "p", 7, 0));
            statement.executeUpdate(runtimeBindingInsert());
            statement.executeUpdate(markLaunchCreated("launch"));
            statement.executeUpdate("""
                    UPDATE project_convention_draft
                    SET external_session_id='remote',external_session_state='CANDIDATE_PROMPT_PENDING',
                      updated_at='attached',version=version+1
                    WHERE id='draft'
                    """);
            settleLaunch(connection, statement);

            assertThatThrownBy(() -> statement.executeUpdate(acceptedResultInsert(
                    "run", "other-draft", "p", 7, 1)))
                    .hasMessageContaining("Convention accepted result run mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(acceptedResultInsert(
                    "run", "draft", "other-project", 7, 1)))
                    .hasMessageContaining("Convention accepted result run mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(acceptedResultInsert(
                    "run", "draft", "p", 8, 1)))
                    .hasMessageContaining("Convention accepted result run mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(acceptedResultInsert(
                    "run", "draft", "p", 7, 2)))
                    .hasMessageContaining("Convention accepted result run mismatch");

            assertThat(statement.executeUpdate(acceptedResultInsert("run", "draft", "p", 7, 1)))
                    .isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE project_convention_candidate_accepted_result
                    SET proposed_content='changed' WHERE candidate_run_id='run'
                    """))
                    .hasMessageContaining("Convention accepted result payload is immutable");
            assertThatThrownBy(() -> statement.executeUpdate(settleResult("draft")))
                    .hasMessageContaining("Convention accepted result settlement requires accepted run");

            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                      problems_json,response_json,canonical_result_sha256,created_at)
                    VALUES('accepted-attempt','run',1,'key-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'ACCEPTED',0,'[]','{}',
                      'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee','now')
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET state='ACCEPTED',attempts_used=1,terminal_attempt_id='accepted-attempt',
                      updated_at='accepted',version=version+1 WHERE id='run'
                    """);
            assertThat(statement.executeUpdate(settleResult("draft"))).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE project_convention_candidate_accepted_result
                    SET settled_draft_id=NULL,updated_at='again',version=version+1
                    WHERE candidate_run_id='run'
                    """))
                    .hasMessageContaining("Convention accepted result settlement is irreversible");
        }
    }

    private String migratedDatabase(String name) {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve(name);
        Flyway.configure().dataSource(url, null, null).load().migrate();
        return url;
    }

    private void insertFixture(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO project(id,name,root_path,created_at,updated_at)
                VALUES('p','P','/tmp/p','now','now'),
                      ('other-project','Other','/tmp/other','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO project_stack_profile(
                  id,project_id,analysis_state,manifest_fingerprint,analyzed_at,created_at)
                VALUES('stack','p','READY',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','now','now'),
                      ('other-stack','other-project','READY',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO project_convention_draft(
                  id,project_id,state,source_exists,source_sha256,source_content,
                  created_at,updated_at,version,project_stack_profile_id,stack_fingerprint,
                  response_mode,source_revision)
                VALUES('draft','p','RUNNING',1,'%s','%s','now','now',0,'stack',
                         'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                         'INTERNAL_MCP',7),
                      ('other-draft','p','RUNNING',1,'%s','%s','now','now',0,'stack',
                         'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                         'INTERNAL_MCP',7),
                      ('cascade-draft','p','RUNNING',1,'%s','%s','now','now',0,'stack',
                         'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                         'INTERNAL_MCP',3)
                """.formatted(SOURCE_SHA, SOURCE, SOURCE_SHA, SOURCE, SOURCE_SHA, SOURCE));
    }

    private String snapshotInsert(
            String runId, String draftId, String projectId, int sourceRevision, int ownerVersion) {
        String profileId = "other-project".equals(projectId) ? "other-stack" : "stack";
        String fingerprint = "other-project".equals(projectId) ? "c".repeat(64) : "b".repeat(64);
        return """
                INSERT INTO project_convention_candidate_source_snapshot(
                  candidate_run_id,project_id,project_convention_draft_id,source_revision,
                  prepared_owner_version,contract_version,source_exists,source_agents_sha256,
                  source_content,source_content_sha256,project_stack_profile_id,stack_fingerprint,
                  canonical_evidence_json,evidence_sha256,created_at)
                VALUES('%s','%s','%s',%d,%d,'PROJECT_CONVENTION_V1',1,'%s','%s','%s',
                  '%s','%s','%s','%s','now')
                """.formatted(runId, projectId, draftId, sourceRevision, ownerVersion,
                SOURCE_SHA, SOURCE, SOURCE_SHA, profileId, fingerprint, EVIDENCE, "d".repeat(64));
    }

    private String conventionLaunchInsert(
            String launchId, String runId, String draftId, String projectId,
            int sourceRevision, int ownerVersion) {
        return """
                INSERT INTO ai_candidate_internal_launch(
                  id,candidate_run_id,candidate_kind,project_id,owner_type,owner_id,
                  project_convention_draft_id,workflow_step,source_revision,contract_version,
                  max_attempts,state,prepared_owner_version,exact_title,canonical_directory,
                  runtime_generation_id,managed,internal_mcp_server,endpoint_fingerprint,profile,
                  permission_policy_json,permission_policy_digest,create_request_sha256,
                  creation_credential,attestation_type,created_at,updated_at,version)
                VALUES('%s','%s','PROJECT_CONVENTION_V1','%s','PROJECT_CONVENTION_DRAFT','%s',
                  '%s','PROJECT_CONVENTION_V1',%d,'PROJECT_CONVENTION_V1',3,'PREPARED',%d,
                  '%s','/tmp/p','generation-1',1,'loopper_internal_generic',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'PROJECT_CONVENTION_CANDIDATE_READ_ONLY','[]',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                  '%s','LOCAL_REQUEST_ATTESTED','now','now',0)
                """.formatted(launchId, runId, projectId, draftId, draftId,
                sourceRevision, ownerVersion, launchId, "C".repeat(43));
    }

    private String markCreateDispatch(String launchId) {
        return """
                UPDATE ai_candidate_internal_launch
                SET state='CREATING',create_claim_owner='worker',create_claim_token='claim',
                  create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                  create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                  updated_at='dispatch-at',version=version+1
                WHERE id='%s'
                """.formatted(launchId);
    }

    private String markLaunchCreated(String launchId) {
        return """
                UPDATE ai_candidate_internal_launch
                SET state='CREATED',create_claim_owner='worker',create_claim_token='claim',
                  create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                  create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                  external_session_id='remote',external_attested_at='attested-at',
                  updated_at='created-at',version=version+1
                WHERE id='%s'
                """.formatted(launchId);
    }

    private String runtimeBindingInsert() {
        return """
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                  internal_mcp_server,created_at)
                VALUES('remote','generation-1','MANAGED',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'loopper_internal_generic','now')
                """;
    }

    private void settleLaunch(Connection connection, Statement statement) throws Exception {
        connection.setAutoCommit(false);
        try {
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,project_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,
                      external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('run','p','PROJECT_CONVENTION_DRAFT','draft','PROJECT_CONVENTION_V1',
                      'PROJECT_CONVENTION_V1',7,1,'INTERNAL_MCP','PROJECT_CONVENTION_V1',
                      'generation-1','remote','OPEN',3,0,'now','now',0)
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_internal_launch
                    SET state='SETTLED',settled_owner_version=1,settled_at='settled-at',
                      create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
                      updated_at='settled-at',version=version+1 WHERE id='launch'
                    """);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private String acceptedResultInsert(
            String runId, String draftId, String projectId, int sourceRevision, int ownerVersion) {
        return """
                INSERT INTO project_convention_candidate_accepted_result(
                  candidate_run_id,project_id,project_convention_draft_id,source_revision,owner_version,
                  contract_version,canonical_candidate_json,candidate_payload_sha256,
                  canonical_result_sha256,proposed_content,proposed_content_sha256,
                  created_at,updated_at,version)
                VALUES('%s','%s','%s',%d,%d,'PROJECT_CONVENTION_V1',
                  '{"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["root"],"commands":[],"paths":[]}',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  '# Human project rules\n\n<!-- LOOPPER:START -->\nmanaged\n<!-- LOOPPER:END -->\n',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                  'now','now',0)
                """.formatted(runId, projectId, draftId, sourceRevision, ownerVersion);
    }

    private String settleResult(String draftId) {
        return """
                UPDATE project_convention_candidate_accepted_result
                SET settled_draft_id='%s',updated_at='settled-result',version=version+1
                WHERE candidate_run_id='run'
                """.formatted(draftId);
    }

    private int count(Statement statement, String table, String condition) throws Exception {
        try (var result = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE " + condition)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
