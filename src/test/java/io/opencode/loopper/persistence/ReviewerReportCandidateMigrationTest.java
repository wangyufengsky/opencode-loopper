package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewerReportCandidateMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void addsImmutableReviewerSourceSnapshotAndAcceptedResultWithoutReplacingAcceptance() throws Exception {
        String url = migratedDatabase("reviewer-result-schema.db");

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            Set<String> tables = new HashSet<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) tables.add(result.getString(1));
            }

            assertThat(tables).contains(
                    "reviewer_report_candidate_source_snapshot",
                    "reviewer_report_candidate_accepted_result",
                    "acceptance_candidate_internal_launch",
                    "ai_candidate_internal_launch");
        }
    }

    @Test
    void bindsSnapshotAndAcceptedResultToExactReviewerRunAndMakesPayloadImmutable() throws Exception {
        String url = migratedDatabase("reviewer-result-constraints.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertFixture(statement);

            assertThatThrownBy(() -> statement.executeUpdate(sourceSnapshotInsert(
                    "reviewer-run", "report", 1)))
                    .hasMessageContaining("Reviewer source snapshot owner mismatch");
            statement.executeUpdate(sourceSnapshotInsert("reviewer-run", "report", 0));
            statement.executeUpdate("""
                    UPDATE analysis_report
                    SET external_session_id='reviewer-remote',external_session_state='RUNNING',
                      updated_at='attached',version=version+1
                    WHERE id='report'
                    """);
            statement.executeUpdate(reviewerRunInsert("reviewer-run", "report", 1));

            assertThatThrownBy(() -> statement.executeUpdate(acceptedResultInsert(
                    "reviewer-run", "report-other", 1)))
                    .hasMessageContaining("Reviewer accepted result run mismatch");
            statement.executeUpdate(acceptedResultInsert("reviewer-run", "report", 1));

            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE reviewer_report_candidate_source_snapshot
                    SET canonical_source_manifest_json='[]' WHERE candidate_run_id='reviewer-run'
                    """))
                    .hasMessageContaining("Reviewer source snapshot is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE reviewer_report_candidate_accepted_result
                    SET markdown='changed' WHERE candidate_run_id='reviewer-run'
                    """))
                    .hasMessageContaining("Reviewer accepted result payload is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE reviewer_report_candidate_accepted_result
                    SET settled_analysis_report_id='report-other',updated_at='later',version=version+1
                    WHERE candidate_run_id='reviewer-run'
                    """))
                    .hasMessageContaining("Reviewer accepted result requires exact proven READY owner");

            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                      problems_json,response_json,canonical_result_sha256,created_at)
                    VALUES('accepted-attempt','reviewer-run',1,'key-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'ACCEPTED',0,'[]','{}',
                      'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee','now')
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET state='ACCEPTED',attempts_used=1,terminal_attempt_id='accepted-attempt',
                      updated_at='accepted',version=version+1
                    WHERE id='reviewer-run'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE reviewer_report_candidate_accepted_result
                    SET settled_analysis_report_id='report',updated_at='later',version=version+1
                    WHERE candidate_run_id='reviewer-run'
                    """))
                    .hasMessageContaining("Reviewer accepted result requires exact proven READY owner");
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
                VALUES('p','P','/tmp/p','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at)
                VALUES('d','p','Goal','{}','DRAFT_READY','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at)
                VALUES('s','p','RUNNING','READ_ONLY','d','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_task_profile(
                  id,designer_session_id,state,intent,workflow_template,mutation_mode,
                  artifact_kinds_json,technologies_json,test_policy,execution_strategy,
                  role_pack_id,role_pack_version,confidence,evidence_json,resolution_source,
                  decision_required,created_at,updated_at)
                VALUES('profile','s','FROZEN','READ_ONLY_REVIEW','REVIEWER_REPORT','READ_ONLY',
                  '[]','[]','NOT_APPLICABLE','OPENCODE','reviewer','2026-08-dynamic-v7',90,'[]',
                  'USER_CONFIRMED',0,'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO analysis_report(
                  id,designer_session_id,task_profile_id,state,title,markdown,evidence_json,
                  reviewer_contract_version,findings_json,source_requirement,
                  source_requirement_revision,created_at,updated_at,version)
                VALUES('report','s','profile','RUNNING','Report','','[]','REVIEWER_REPORT_V1','[]',
                         'Review requirement',7,'now','now',0),
                      ('report-other','s','profile','RUNNING','Other','','[]','REVIEWER_REPORT_V1','[]',
                         'Other requirement',7,'now','now',0)
                """);
        statement.executeUpdate("""
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                  internal_mcp_server,created_at)
                VALUES('reviewer-remote','generation-1','MANAGED',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'loopper_internal_generic','now')
                """);
    }

    private String reviewerRunInsert(String runId, String reportId, int ownerVersion) {
        return """
                INSERT INTO ai_candidate_submission_run(
                  id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,
                  source_revision,owner_version,submission_channel,contract_version,
                  runtime_generation_id,external_session_id,state,max_attempts,attempts_used,
                  created_at,updated_at,version)
                VALUES('%s','s','ANALYSIS_REPORT','%s','REVIEWER_REPORT_V1','REVIEWER_REPORT_V1',
                  7,%d,'IN_PROCESS_LEGACY','REVIEWER_REPORT_V1','generation-1','reviewer-remote',
                  'OPEN',3,0,'now','now',0)
                """.formatted(runId, reportId, ownerVersion);
    }

    private String sourceSnapshotInsert(String runId, String reportId, int preparedOwnerVersion) {
        return """
                INSERT INTO reviewer_report_candidate_source_snapshot(
                  candidate_run_id,analysis_report_id,source_revision,prepared_owner_version,contract_version,
                  canonical_source_manifest_json,source_manifest_sha256,created_at)
                VALUES('%s','%s',7,%d,'REVIEWER_REPORT_V1',
                  '[{"path":"src/Main.java","sizeBytes":14,"lineCount":1,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','now')
                """.formatted(runId, reportId, preparedOwnerVersion);
    }

    private String acceptedResultInsert(String runId, String reportId, int ownerVersion) {
        return """
                INSERT INTO reviewer_report_candidate_accepted_result(
                  candidate_run_id,analysis_report_id,source_revision,owner_version,contract_version,
                  canonical_candidate_json,canonical_findings_json,markdown,evidence_json,
                  content_sha256,source_snapshot_sha256,candidate_payload_sha256,canonical_result_sha256,
                  created_at,updated_at,version)
                VALUES('%s','%s',7,%d,'REVIEWER_REPORT_V1',
                  '{"title":"Report","summary":"Summary","findings":[{"severity":"INFO","title":"Finding","detail":"Detail","path":"src/Main.java","line":1,"recommendation":"Fix"}],"limitations":[]}',
                  '[{"severity":"INFO","title":"Finding","detail":"Detail","path":"src/Main.java","line":1,"recommendation":"Fix"}]',
                  '# Report','[{"path":"src/Main.java","line":1,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                  'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                  'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                  'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                  'now','now',0)
                """.formatted(runId, reportId, ownerVersion);
    }
}
