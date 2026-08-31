package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateSubmissionMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsCandidateSubmissionAndRuntimeBindingContractsOnFreshAndV46Databases() throws Exception {
        verifyMigration(temporaryDirectory.resolve("fresh.db"), false);
        verifyMigration(temporaryDirectory.resolve("upgrade-v46.db"), true);
    }

    private void verifyMigration(Path database, boolean startAtV46) throws Exception {
        String url = "jdbc:sqlite:" + database;
        if (startAtV46) {
            Flyway.configure().dataSource(url, null, null)
                    .target(MigrationVersion.fromVersion("46")).load().migrate();
            insertLegacyManagedSession(url);
        }
        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("47");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            List<String> tables = new ArrayList<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) tables.add(result.getString(1));
            }
            assertThat(tables).contains("ai_candidate_submission_run", "ai_candidate_submission_attempt",
                    "open_code_session_runtime_binding");

            List<String> bindingColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(open_code_session_runtime_binding)")) {
                while (result.next()) bindingColumns.add(result.getString("name"));
            }
            assertThat(bindingColumns).contains("internal_mcp_server");
            if (startAtV46) {
                try (var result = statement.executeQuery("""
                        SELECT ownership_mode,runtime_generation_id,internal_mcp_server
                        FROM open_code_session_runtime_binding
                        WHERE external_session_id='legacy-designer-remote'
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("ownership_mode")).isEqualTo("LEGACY_UNKNOWN");
                    assertThat(result.getString("runtime_generation_id")).startsWith("legacy-unbound-");
                    assertThat(result.getString("internal_mcp_server")).isNull();
                }
            }

            List<String> runColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_submission_run)")) {
                while (result.next()) runColumns.add(result.getString("name"));
            }
            assertThat(runColumns).contains("source_revision", "owner_version", "submission_channel");

            List<String> attemptColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_submission_attempt)")) {
                while (result.next()) attemptColumns.add(result.getString("name"));
            }
            assertThat(attemptColumns)
                    .contains("request_sha256", "problems_json", "response_json", "canonical_result_sha256")
                    .noneMatch(column -> column.contains("candidate"));

            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                      internal_mcp_server,created_at)
                    VALUES('remote-1','generation-1','MANAGED','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'loopper_internal_generation1','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,task_decomposition_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('run-1','s','dec','DECOMPOSITION_PLAN_V2','PLANNING',1,0,'IN_PROCESS_LEGACY',
                      'DECOMPOSITION_PLAN_V2','generation-1','remote-1','OPEN',5,0,'now','now',0)
                    """);

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,loop_spec_compilation_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-generation','s','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'INTERNAL_MCP',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','generation-other','remote-1','OPEN',2,0,'now','now',0)
                    """))
                    .hasMessageContaining("FOREIGN KEY constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,task_decomposition_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-owner','s','dec','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'INTERNAL_MCP',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','generation-1','remote-1','OPEN',2,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,loop_spec_compilation_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-state','s','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'INTERNAL_MCP',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','generation-1','remote-1','FAILED',2,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,loop_spec_compilation_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-channel','s','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'PUBLIC_MCP',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','generation-1','remote-1','OPEN',2,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
        }
    }

    private void insertLegacyManagedSession(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) "
                    + "VALUES('legacy-p','Legacy','/tmp/legacy','now','now')");
            statement.executeUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                    + "VALUES('legacy-d','legacy-p','G','{}','DRAFT_READY','now','now')");
            statement.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,"
                    + "external_session_id,created_at,updated_at) VALUES('legacy-s','legacy-p','RUNNING','READ_ONLY',"
                    + "'legacy-d','legacy-designer-remote','now','now')");
        }
    }

    private void insertCandidateOwners(java.sql.Statement statement) throws Exception {
        statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) "
                + "VALUES('p','P','/tmp/p','now','now')");
        statement.executeUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('d','p','G','{}','DRAFT_READY','now','now')");
        statement.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) "
                + "VALUES('s','p','RUNNING','READ_ONLY','d','now','now')");
        statement.executeUpdate("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) "
                + "VALUES('m','s',1,'ASSISTANT','design','PERSISTED','now')");
        statement.executeUpdate("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,"
                + "requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) "
                + "VALUES('r','s',1,'m','requirement','[]',0,'ACTIVE','now','now')");
        statement.executeUpdate("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,"
                + "source_draft_version,created_at,updated_at) VALUES('dec','s','r','RUNNING',0,'now','now')");
        statement.executeUpdate("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,"
                + "source_design_message_id,source_draft_version,created_at,updated_at) "
                + "VALUES('cmp','s',1,'RUNNING','m',0,'now','now')");
    }
}
