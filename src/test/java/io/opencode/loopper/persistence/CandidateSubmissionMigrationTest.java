package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CandidateSubmissionMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsCurrentCandidateSubmissionContractsOnFreshAndV46Databases() throws Exception {
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

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("50");
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
            assertThat(runColumns).contains("designer_session_id", "task_id", "project_id", "owner_type",
                            "owner_id", "source_revision", "owner_version", "submission_channel", "close_reason")
                    .doesNotContain("task_decomposition_id", "loop_spec_compilation_id", "design_work_package_id");

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
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('run-1','s','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2','PLANNING',1,0,'IN_PROCESS_LEGACY',
                      'DECOMPOSITION_PLAN_V2','generation-1','remote-1','OPEN',5,0,'now','now',0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('package-run','s','DESIGN_WORK_PACKAGE','wp','PACKAGE_DESIGN_V1','PACKAGE_DESIGN',1,0,'INTERNAL_MCP',
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
                      id,designer_session_id,owner_type,owner_id,candidate_kind,
                      workflow_step,source_revision,owner_version,submission_channel,contract_version,
                      runtime_generation_id,external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-package-owner','s','TASK_DECOMPOSITION','dec','PACKAGE_DESIGN_V1','PACKAGE_DESIGN',1,0,
                      'INTERNAL_MCP','PACKAGE_DESIGN_V1','generation-1','remote-1','OPEN',3,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,task_id,owner_type,owner_id,candidate_kind,
                      workflow_step,source_revision,owner_version,submission_channel,contract_version,
                      runtime_generation_id,external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-two-scopes','s','missing-task','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2',
                      'PLANNING',1,0,'IN_PROCESS_LEGACY','DECOMPOSITION_PLAN_V2','generation-1','remote-1',
                      'OPEN',5,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,
                      workflow_step,source_revision,owner_version,submission_channel,contract_version,
                      runtime_generation_id,external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-owner-scope','s-other','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2',
                      'PLANNING',1,0,'IN_PROCESS_LEGACY','DECOMPOSITION_PLAN_V2','generation-1','remote-1',
                      'OPEN',5,0,'now','now',0)
                    """))
                    .hasMessageContaining("candidate owner scope mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE ai_candidate_submission_run SET owner_id='cmp' WHERE id='run-1'"))
                    .hasMessageContaining("candidate owner and scope are immutable");
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-generation','s','LOOP_SPEC_COMPILATION','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'INTERNAL_MCP',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','generation-other','remote-1','OPEN',2,0,'now','now',0)
                    """))
                    .hasMessageContaining("FOREIGN KEY constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-owner','s','TASK_DECOMPOSITION','dec','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'INTERNAL_MCP',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','generation-1','remote-1','OPEN',2,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            statement.executeUpdate("DELETE FROM design_work_package WHERE id='wp'");
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM ai_candidate_submission_run "
                    + "WHERE id='package-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                    + "WHERE id='package-attempt'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-state','s','LOOP_SPEC_COMPILATION','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'INTERNAL_MCP',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','generation-1','remote-1','FAILED',2,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-channel','s','LOOP_SPEC_COMPILATION','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'PUBLIC_MCP',
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

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("50");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (var result = statement.executeQuery("SELECT owner_type,owner_id,state,version "
                    + "FROM ai_candidate_submission_run WHERE id='preserved-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("owner_type")).isEqualTo("TASK_DECOMPOSITION");
                assertThat(result.getString("owner_id")).isEqualTo("dec");
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
            assertThat(indexes).contains("ux_candidate_submission_open_owner",
                    "idx_candidate_submission_scope_state", "idx_candidate_submission_attempt_run");
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

    @Test
    void upgradingFromV48PreservesPackageAcceptedResultAndMapsTypedScopeOwner() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v48-with-data.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("48")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                    VALUES('package-remote','package-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,design_work_package_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('preserved-package-run','s','wp','PACKAGE_DESIGN_V1','PACKAGE_DESIGN_V1',1,0,
                      'INTERNAL_MCP','PACKAGE_DESIGN_V1','package-generation','package-remote',
                      'ACCEPTED',3,1,'preserved-package-attempt','now','now',1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      canonical_result_sha256,created_at)
                    VALUES('preserved-package-attempt','preserved-package-run',1,'key',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'ACCEPTED',0,'[]','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO package_design_candidate_accepted_result(
                      candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
                      canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
                      created_at,updated_at,version)
                    VALUES('preserved-package-run','wp',1,0,'PACKAGE_DESIGN_V1','{}','# Package','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now','now',0)
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("50");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (var result = statement.executeQuery("""
                    SELECT designer_session_id,task_id,project_id,owner_type,owner_id,state,version
                    FROM ai_candidate_submission_run WHERE id='preserved-package-run'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("designer_session_id")).isEqualTo("s");
                assertThat(result.getString("task_id")).isNull();
                assertThat(result.getString("project_id")).isNull();
                assertThat(result.getString("owner_type")).isEqualTo("DESIGN_WORK_PACKAGE");
                assertThat(result.getString("owner_id")).isEqualTo("wp");
                assertThat(result.getString("state")).isEqualTo("ACCEPTED");
                assertThat(result.getLong("version")).isEqualTo(1);
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                    + "WHERE run_id='preserved-package-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM package_design_candidate_accepted_result "
                    + "WHERE candidate_run_id='preserved-package-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void upgradingFromV48PreservesAcceptanceClosedChoiceRunAndAttempts() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v48-acceptance-with-data.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("48")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                    VALUES('acceptance-remote','acceptance-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      '2026-08-31T01:00:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,loop_spec_compilation_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('preserved-acceptance-run','s','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CLOSED_CHOICE',7,3,
                      'INTERNAL_MCP','ACCEPTANCE_CLOSED_CHOICE_V7','acceptance-generation','acceptance-remote',
                      'ACCEPTED',2,2,'preserved-acceptance-attempt-2',
                      '2026-08-31T01:00:01Z','2026-08-31T01:00:03Z',2)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,loop_spec_compilation_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('historical-closed-acceptance-run','s','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CLOSED_CHOICE',7,3,
                      'INTERNAL_MCP','ACCEPTANCE_CLOSED_CHOICE_V7','acceptance-generation','acceptance-remote',
                      'CLOSED',2,0,'2026-08-31T01:00:01Z','2026-08-31T01:00:03Z',1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      canonical_result_sha256,created_at)
                    VALUES('preserved-acceptance-attempt-1','preserved-acceptance-run',1,'choice-key-1',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'REJECTED',1,'[{"code":"CHOICE_INVALID"}]','{"outcome":"REJECTED"}',NULL,
                      '2026-08-31T01:00:02Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      canonical_result_sha256,created_at)
                    VALUES('preserved-acceptance-attempt-2','preserved-acceptance-run',2,'choice-key-2',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'ACCEPTED',0,'[]','{"outcome":"ACCEPTED"}',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                      '2026-08-31T01:00:03Z')
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("50");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (var result = statement.executeQuery("""
                    SELECT designer_session_id,task_id,project_id,owner_type,owner_id,candidate_kind,workflow_step,
                           source_revision,owner_version,submission_channel,contract_version,runtime_generation_id,
                           external_session_id,state,max_attempts,attempts_used,terminal_attempt_id,created_at,
                           updated_at,version
                    FROM ai_candidate_submission_run WHERE id='preserved-acceptance-run'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("designer_session_id")).isEqualTo("s");
                assertThat(result.getString("task_id")).isNull();
                assertThat(result.getString("project_id")).isNull();
                assertThat(result.getString("owner_type")).isEqualTo("LOOP_SPEC_COMPILATION");
                assertThat(result.getString("owner_id")).isEqualTo("cmp");
                assertThat(result.getString("candidate_kind")).isEqualTo("ACCEPTANCE_CLOSED_CHOICE_V7");
                assertThat(result.getString("workflow_step")).isEqualTo("CLOSED_CHOICE");
                assertThat(result.getInt("source_revision")).isEqualTo(7);
                assertThat(result.getInt("owner_version")).isEqualTo(3);
                assertThat(result.getString("submission_channel")).isEqualTo("INTERNAL_MCP");
                assertThat(result.getString("contract_version")).isEqualTo("ACCEPTANCE_CLOSED_CHOICE_V7");
                assertThat(result.getString("runtime_generation_id")).isEqualTo("acceptance-generation");
                assertThat(result.getString("external_session_id")).isEqualTo("acceptance-remote");
                assertThat(result.getString("state")).isEqualTo("ACCEPTED");
                assertThat(result.getInt("max_attempts")).isEqualTo(2);
                assertThat(result.getInt("attempts_used")).isEqualTo(2);
                assertThat(result.getString("terminal_attempt_id")).isEqualTo("preserved-acceptance-attempt-2");
                assertThat(result.getString("created_at")).isEqualTo("2026-08-31T01:00:01Z");
                assertThat(result.getString("updated_at")).isEqualTo("2026-08-31T01:00:03Z");
                assertThat(result.getInt("version")).isEqualTo(2);
            }
            try (var result = statement.executeQuery("""
                    SELECT state,close_reason FROM ai_candidate_submission_run
                    WHERE id='historical-closed-acceptance-run'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("state")).isEqualTo("CLOSED");
                assertThat(result.getString("close_reason")).isNull();
            }
            try (var result = statement.executeQuery("""
                    SELECT id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                           canonical_result_sha256,created_at
                    FROM ai_candidate_submission_attempt
                    WHERE run_id='preserved-acceptance-run' ORDER BY ordinal
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("id")).isEqualTo("preserved-acceptance-attempt-1");
                assertThat(result.getInt("ordinal")).isEqualTo(1);
                assertThat(result.getString("idempotency_key")).isEqualTo("choice-key-1");
                assertThat(result.getString("request_sha256")).isEqualTo("b".repeat(64));
                assertThat(result.getString("outcome")).isEqualTo("REJECTED");
                assertThat(result.getInt("retryable")).isEqualTo(1);
                assertThat(result.getString("problems_json")).isEqualTo("[{\"code\":\"CHOICE_INVALID\"}]");
                assertThat(result.getString("response_json")).isEqualTo("{\"outcome\":\"REJECTED\"}");
                assertThat(result.getString("canonical_result_sha256")).isNull();
                assertThat(result.getString("created_at")).isEqualTo("2026-08-31T01:00:02Z");
                assertThat(result.next()).isTrue();
                assertThat(result.getString("id")).isEqualTo("preserved-acceptance-attempt-2");
                assertThat(result.getInt("ordinal")).isEqualTo(2);
                assertThat(result.getString("idempotency_key")).isEqualTo("choice-key-2");
                assertThat(result.getString("request_sha256")).isEqualTo("c".repeat(64));
                assertThat(result.getString("outcome")).isEqualTo("ACCEPTED");
                assertThat(result.getInt("retryable")).isZero();
                assertThat(result.getString("problems_json")).isEqualTo("[]");
                assertThat(result.getString("response_json")).isEqualTo("{\"outcome\":\"ACCEPTED\"}");
                assertThat(result.getString("canonical_result_sha256")).isEqualTo("d".repeat(64));
                assertThat(result.getString("created_at")).isEqualTo("2026-08-31T01:00:03Z");
                assertThat(result.next()).isFalse();
            }
            // Acceptance has no separate accepted-result table; that table is package-design-only.
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='preserved-acceptance-run'")).isZero();
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void upgradingFromV48FailsClosedAndRollsBackWhenHistoricalOwnerCrossesScope() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v48-mismatched-owner.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("48")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                    VALUES('dirty-remote','dirty-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,design_work_package_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('dirty-package-run','s-other','wp','PACKAGE_DESIGN_V1','PACKAGE_DESIGN_V1',1,0,
                      'INTERNAL_MCP','PACKAGE_DESIGN_V1','dirty-generation','dirty-remote',
                      'ACCEPTED',3,1,'dirty-package-attempt','now','now',1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      canonical_result_sha256,created_at)
                    VALUES('dirty-package-attempt','dirty-package-run',1,'dirty-key',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'ACCEPTED',0,'[]','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO package_design_candidate_accepted_result(
                      candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
                      canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
                      created_at,updated_at,version)
                    VALUES('dirty-package-run','wp',1,0,'PACKAGE_DESIGN_V1','{}','# Dirty package','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now','now',0)
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        assertThatThrownBy(flyway::migrate)
                .rootCause().hasMessageContaining("candidate owner scope mismatch");

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("48");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            List<String> runColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_submission_run)")) {
                while (result.next()) runColumns.add(result.getString("name"));
            }
            assertThat(runColumns).contains("design_work_package_id").doesNotContain("owner_type", "owner_id");
            assertThat(count(statement, "ai_candidate_submission_run", "id='dirty-package-run'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_attempt", "id='dirty-package-attempt'")).isEqualTo(1);
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='dirty-package-run'")).isEqualTo(1);
            assertThat(count(statement, "sqlite_master", "type='table' AND name LIKE '%_v48'")).isZero();
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @ParameterizedTest(name = "deleting {0} owner cascades its candidate records")
    @MethodSource("candidateOwnerDeleteCases")
    void deletingEveryConstructibleOwnerCascadesRunAttemptAndTypedResult(OwnerDeleteCase owner) throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve(
                "delete-owner-" + owner.ownerType().toLowerCase() + ".db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertAllCandidateOwners(statement);
            insertSharedRuntimeBinding(statement);
            insertCandidateRunAttemptAndOptionalResult(statement, owner, "delete-run");

            assertThat(count(statement, owner.ownerTable(), "id='" + owner.ownerId() + "'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_run", "id='delete-run'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_attempt", "run_id='delete-run'")).isEqualTo(1);
            if (owner.hasAcceptedResult()) {
                assertThat(count(statement, "package_design_candidate_accepted_result",
                        "candidate_run_id='delete-run'")).isEqualTo(1);
            }

            statement.executeUpdate("DELETE FROM " + owner.ownerTable() + " WHERE id='" + owner.ownerId() + "'");

            assertThat(count(statement, "ai_candidate_submission_run", "id='delete-run'")).isZero();
            assertThat(count(statement, "ai_candidate_submission_attempt", "run_id='delete-run'")).isZero();
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='delete-run'")).isZero();
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void ownerDeleteCascadeParticipatesInTheCallingTransactionRollback() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("owner-delete-rollback.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        OwnerDeleteCase owner = candidateOwnerDeleteCases()
                .map(argument -> (OwnerDeleteCase) argument.get()[0])
                .filter(candidate -> candidate.hasAcceptedResult())
                .findFirst().orElseThrow();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertAllCandidateOwners(statement);
            insertSharedRuntimeBinding(statement);
            insertCandidateRunAttemptAndOptionalResult(statement, owner, "rollback-run");

            connection.setAutoCommit(false);
            statement.executeUpdate("DELETE FROM design_work_package WHERE id='wp'");
            assertThat(count(statement, "design_work_package", "id='wp'")).isZero();
            assertThat(count(statement, "ai_candidate_submission_run", "id='rollback-run'")).isZero();
            assertThat(count(statement, "ai_candidate_submission_attempt", "run_id='rollback-run'")).isZero();
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='rollback-run'")).isZero();
            connection.rollback();

            assertThat(count(statement, "design_work_package", "id='wp'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_run", "id='rollback-run'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_attempt", "run_id='rollback-run'")).isEqualTo(1);
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='rollback-run'")).isEqualTo(1);
            connection.setAutoCommit(true);
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static Stream<Arguments> candidateOwnerDeleteCases() {
        return Stream.of(
                Arguments.of(new OwnerDeleteCase("TASK_DECOMPOSITION", "dec", "task_decomposition",
                        "designer_session_id", "s", "DECOMPOSITION_PLAN_V2", 5, false)),
                Arguments.of(new OwnerDeleteCase("LOOP_SPEC_COMPILATION", "cmp", "loop_spec_compilation",
                        "designer_session_id", "s", "ACCEPTANCE_CLOSED_CHOICE_V7", 2, false)),
                Arguments.of(new OwnerDeleteCase("DESIGN_WORK_PACKAGE", "wp", "design_work_package",
                        "designer_session_id", "s", "PACKAGE_DESIGN_V1", 3, true)),
                Arguments.of(new OwnerDeleteCase("TASK_PACKAGE_PLAN_REVISION", "plan", "task_package_plan_revision",
                        "task_id", "task", "ROLLING_PACKAGE_PLAN_V1", 3, false)),
                Arguments.of(new OwnerDeleteCase("ANALYSIS_REPORT", "report", "analysis_report",
                        "designer_session_id", "s", "REVIEWER_REPORT_V1", 3, false)),
                Arguments.of(new OwnerDeleteCase("PROJECT_CONVENTION_DRAFT", "convention",
                        "project_convention_draft", "project_id", "p", "PROJECT_CONVENTION_V1", 3, false)),
                Arguments.of(new OwnerDeleteCase("JUDGE_RUN", "judge", "judge_run",
                        "task_id", "task", "JUDGE_DECISION_V1", 2, false)));
    }

    private void insertCandidateRunAttemptAndOptionalResult(
            java.sql.Statement statement, OwnerDeleteCase owner, String runId) throws Exception {
        String attemptId = runId + "-attempt";
        statement.executeUpdate("""
                INSERT INTO ai_candidate_submission_run(
                  id,%s,owner_type,owner_id,candidate_kind,workflow_step,source_revision,owner_version,
                  submission_channel,contract_version,runtime_generation_id,external_session_id,state,max_attempts,
                  attempts_used,terminal_attempt_id,created_at,updated_at,version)
                VALUES('%s','%s','%s','%s','%s','OWNER_DELETE',1,0,'IN_PROCESS_LEGACY','%s',
                  'shared-generation','shared-remote','ACCEPTED',%d,1,'%s','now','now',1)
                """.formatted(owner.scopeColumn(), runId, owner.scopeId(), owner.ownerType(), owner.ownerId(),
                owner.candidateKind(), owner.candidateKind(), owner.maxAttempts(), attemptId));
        statement.executeUpdate("""
                INSERT INTO ai_candidate_submission_attempt(
                  id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                  canonical_result_sha256,created_at)
                VALUES('%s','%s',1,'owner-delete-key',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  'ACCEPTED',0,'[]','{}',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now')
                """.formatted(attemptId, runId));
        if (owner.hasAcceptedResult()) {
            statement.executeUpdate("""
                    INSERT INTO package_design_candidate_accepted_result(
                      candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
                      canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
                      created_at,updated_at,version)
                    VALUES('%s','wp',1,0,'PACKAGE_DESIGN_V1','{}','# Package','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now','now',0)
                    """.formatted(runId));
        }
    }

    private void insertSharedRuntimeBinding(java.sql.Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                VALUES('shared-remote','shared-generation','MANAGED',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','now')
                """);
    }

    private void insertAllCandidateOwners(java.sql.Statement statement) throws Exception {
        insertCandidateOwners(statement);
        statement.executeUpdate("""
                INSERT INTO task(id,project_id,loop_draft_id,title,state,created_at,updated_at)
                VALUES('task','p','d','Task','PENDING_START','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,
                  deliverables_json,verifiers_json,state,created_at,updated_at)
                VALUES('stage','task',0,'Stage','[]','[]','[]','[]','RUNNING','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at)
                VALUES('task-attempt','task','stage',1,'RUNNING','now')
                """);
        statement.executeUpdate("""
                INSERT INTO task_package_plan_revision(
                  id,task_id,designer_session_id,requirement_revision_id,revision,state,origin,plan_json,
                  base_task_version,base_package_version,created_at,updated_at)
                VALUES('plan','task','s','r',1,'PROPOSED','AI','{}',0,0,'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_task_profile(
                  id,designer_session_id,requirement_revision_id,state,intent,workflow_template,mutation_mode,
                  artifact_kinds_json,technologies_json,test_policy,execution_strategy,role_pack_id,
                  role_pack_version,confidence,evidence_json,resolution_source,decision_required,created_at,updated_at)
                VALUES('profile','s','r','FROZEN','SOFTWARE_CHANGE','DIRECT_SOFTWARE_DESIGN','WRITE',
                  '[]','[]','REQUIRED','OPENCODE','software-java','2026-08-dynamic-v7',90,'[]',
                  'USER_CONFIRMED',0,'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO analysis_report(
                  id,designer_session_id,task_profile_id,state,title,markdown,evidence_json,created_at,updated_at)
                VALUES('report','s','profile','READY','Report','# Report','[]','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO project_convention_draft(
                  id,project_id,state,source_exists,source_sha256,source_content,created_at,updated_at)
                VALUES('convention','p','READY',0,
                  'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd','',
                  'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO judge_run(id,task_id,attempt_id,role,ordinal,state,created_at)
                VALUES('judge','task','task-attempt','REQUIREMENT',1,'RUNNING','now')
                """);
    }

    private record OwnerDeleteCase(
            String ownerType,
            String ownerId,
            String ownerTable,
            String scopeColumn,
            String scopeId,
            String candidateKind,
            int maxAttempts,
            boolean hasAcceptedResult) {}

    private int count(java.sql.Statement statement, String table, String predicate) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
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
        statement.executeUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('d-other','p','G','{}','DRAFT_READY','now','now')");
        statement.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) "
                + "VALUES('s-other','p','RUNNING','READ_ONLY','d-other','now','now')");
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
