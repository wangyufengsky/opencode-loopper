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
    void createsV48CandidateSubmissionContractsOnFreshAndV46Databases() throws Exception {
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

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("48");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            List<String> tables = new ArrayList<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) tables.add(result.getString(1));
            }
            assertThat(tables).contains("ai_candidate_submission_run", "ai_candidate_submission_attempt",
                    "open_code_session_runtime_binding", "package_design_candidate_accepted_result");

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
            assertThat(runColumns).contains("source_revision", "owner_version", "submission_channel",
                    "design_work_package_id");

            List<String> compilationColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(loop_spec_compilation)")) {
                while (result.next()) compilationColumns.add(result.getString("name"));
            }
            assertThat(compilationColumns).contains("compilation_source", "fallback_reason");

            List<String> attemptColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_submission_attempt)")) {
                while (result.next()) attemptColumns.add(result.getString("name"));
            }
            assertThat(attemptColumns)
                    .contains("request_sha256", "problems_json", "response_json", "canonical_result_sha256")
                    .noneMatch(column -> column.contains("candidate"));

            List<String> acceptedResultColumns = new ArrayList<>();
            try (var result = statement.executeQuery(
                    "PRAGMA table_info(package_design_candidate_accepted_result)")) {
                while (result.next()) acceptedResultColumns.add(result.getString("name"));
            }
            assertThat(acceptedResultColumns).containsExactly(
                    "candidate_run_id", "design_work_package_id", "source_revision", "owner_version",
                    "contract_version", "canonical_candidate_json", "canonical_markdown",
                    "compiled_result_json", "canonical_result_sha256", "settled_compilation_id",
                    "created_at", "updated_at", "version");

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
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,design_work_package_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('package-run','s','wp','PACKAGE_DESIGN_V1','PACKAGE_DESIGN',1,0,'INTERNAL_MCP',
                      'PACKAGE_DESIGN_V1','generation-1','remote-1','FALLBACK_REQUIRED',3,3,
                      'package-attempt','now','now',0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      created_at)
                    VALUES('package-attempt','package-run',3,'attempt-3',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'FALLBACK_REQUIRED',0,'[]','{}','now')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,task_decomposition_id,design_work_package_id,candidate_kind,
                      workflow_step,source_revision,owner_version,submission_channel,contract_version,
                      runtime_generation_id,external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-package-owner','s','dec','wp','PACKAGE_DESIGN_V1','PACKAGE_DESIGN',1,0,
                      'INTERNAL_MCP','PACKAGE_DESIGN_V1','generation-1','remote-1','OPEN',3,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }

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

    @Test
    void upgradingFromV47PreservesRunsAttemptsIndexesAndForeignKeys() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v47-with-data.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("47")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                    VALUES('preserved-remote','preserved-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,task_decomposition_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('preserved-run','s','dec','DECOMPOSITION_PLAN_V2','PLANNING',1,0,
                      'IN_PROCESS_LEGACY','DECOMPOSITION_PLAN_V2','preserved-generation','preserved-remote',
                      'WAITING_INPUT',5,1,'preserved-attempt','now','now',1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      created_at)
                    VALUES('preserved-attempt','preserved-run',1,'key',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'WAITING_INPUT',0,'[]','{}','now')
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("48");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (var result = statement.executeQuery("SELECT design_work_package_id,state,version "
                    + "FROM ai_candidate_submission_run WHERE id='preserved-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("design_work_package_id")).isNull();
                assertThat(result.getString("state")).isEqualTo("WAITING_INPUT");
                assertThat(result.getLong("version")).isEqualTo(1);
            }
            try (var result = statement.executeQuery("SELECT outcome FROM ai_candidate_submission_attempt "
                    + "WHERE id='preserved-attempt'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("WAITING_INPUT");
            }
            List<String> indexes = new ArrayList<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")) {
                while (result.next()) indexes.add(result.getString(1));
            }
            assertThat(indexes).contains("ux_candidate_submission_open_decomposition",
                    "ux_candidate_submission_open_compilation", "ux_candidate_submission_open_package",
                    "idx_candidate_submission_attempt_run");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      created_at)
                    VALUES('orphan','missing-run',1,'orphan',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'REJECTED',1,'[]','{}','now')
                    """))
                    .hasMessageContaining("FOREIGN KEY constraint failed");
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
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
        statement.executeUpdate("INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,"
                + "decomposition_id,package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,"
                + "deliverables_json,acceptance_intent_json,requirement_refs_json,state,design_revision,created_at,updated_at) "
                + "VALUES('wp','s','r','dec','WP-1',0,'Package','Deliver','[]','[]','[]','[]','[]','[]',"
                + "'DESIGNING',0,'now','now')");
    }
}
