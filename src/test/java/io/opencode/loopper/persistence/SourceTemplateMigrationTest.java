package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceTemplateMigrationTest {
    @TempDir Path directory;

    @Test
    void upgradePreservesCandidateHistoryReferencesAndGuards() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("source-upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("126").load().migrate();
        Map<String, String> schemaBefore;
        Map<String, Object> historyBefore;
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','t','t')");
            sql.execute("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at) "
                    + "VALUES('s','p','RUNNING','READ_ONLY','t','t')");
            sql.execute("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) "
                    + "VALUES('m','s',1,'USER','req','PERSISTED','t')");
            sql.execute("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,requirement_text,"
                    + "requirement_segments_json,source_draft_version,state,created_at,updated_at) "
                    + "VALUES('r','s',1,'m','req','[]',0,'ACTIVE','t','t')");
            sql.execute("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,"
                    + "source_draft_version,created_at,updated_at) VALUES('owner','s','r','RUNNING',0,'t','t')");
            sql.execute("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,"
                    + "ownership_mode,endpoint_fingerprint,created_at) VALUES('remote','gen','MANAGED','"
                    + "a".repeat(64) + "','t')");
            for (String channel : new String[]{"INTERNAL_MCP", "IN_PROCESS_LEGACY"}) {
                sql.execute("INSERT INTO ai_candidate_submission_run(id,designer_session_id,owner_type,owner_id,"
                        + "candidate_kind,workflow_step,source_revision,owner_version,submission_channel,contract_version,"
                        + "runtime_generation_id,external_session_id,state,max_attempts,attempts_used,terminal_attempt_id,"
                        + "created_at,updated_at,version) VALUES('" + channel + "','s','TASK_DECOMPOSITION','owner',"
                        + "'DECOMPOSITION_PLAN_V2','PLANNING',1,0,'" + channel + "','DECOMPOSITION_PLAN_V2','gen',"
                        + "'remote','" + (channel.equals("INTERNAL_MCP") ? "OPEN" : "WAITING_INPUT")
                        + "',5," + (channel.equals("INTERNAL_MCP") ? 4 : 5) + "," + (channel.equals("INTERNAL_MCP") ? "NULL" : "'attempt-IN_PROCESS_LEGACY'")
                        + ",'t','t',5)");
                sql.execute("INSERT INTO ai_candidate_submission_attempt(id,run_id,ordinal,idempotency_key,request_sha256,"
                        + "outcome,retryable,problems_json,response_json,created_at) VALUES('attempt-" + channel
                        + "','" + channel + "'," + (channel.equals("INTERNAL_MCP") ? 4 : 5) + ",'key','" + "b".repeat(64) + "','"
                        + (channel.equals("INTERNAL_MCP") ? "REJECTED',1" : "WAITING_INPUT',0")
                        + ",'[]','{\"remainingAttempts\":0}','t')");
            }
            schemaBefore = schema(sql);
            historyBefore = history(sql);
        }

        Flyway.configure().dataSource(url, null, null).target("138").load().migrate();

        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            var after = schema(sql);
            schemaBefore.forEach((name, definition) -> {
                if (name.equals("view:task_list_item") || name.equals("trigger:trg_candidate_owner_scope_insert")) return;
                assertThat(after.get(name)).as("preserved schema %s", name).isEqualTo(definition);
            });
            assertThat(sql.executeUpdate("INSERT INTO ai_candidate_submission_run SELECT 'finite',designer_session_id,"
                    + "task_id,project_id,owner_type,owner_id,candidate_kind,'FINITE_TEST',source_revision,owner_version,"
                    + "submission_channel,contract_version,runtime_generation_id,external_session_id,state,max_attempts,"
                    + "0,NULL,created_at,updated_at,0,close_reason,4 FROM ai_candidate_submission_run WHERE id='INTERNAL_MCP'"))
                    .isEqualTo(1);
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE ai_candidate_submission_run SET correction_limit=8 WHERE id='finite'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=5 WHERE id='finite'"))
                    .hasMessageContaining("CHECK constraint failed");
            sql.executeUpdate("DELETE FROM ai_candidate_submission_run WHERE id='finite'");
            assertThat(history(sql)).isEqualTo(historyBefore);
            assertThat(sql.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=12,version=12 "
                    + "WHERE id='INTERNAL_MCP'")).isEqualTo(1);
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=6 "
                    + "WHERE id='IN_PROCESS_LEGACY'")).hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE ai_candidate_submission_run SET owner_id='other' "
                    + "WHERE id='INTERNAL_MCP'")).hasMessageContaining("immutable");
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
            try (var rows = sql.executeQuery("PRAGMA foreign_keys")) { assertThat(rows.getInt(1)).isEqualTo(1); }
        }
    }

    @Test void upgradeRejectsOldCrossScopeCandidateInsteadOfLosingItsEvidence() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("invalid-owner.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("128").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','t','t')");
            sql.execute("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at) VALUES('s','p','RUNNING','READ_ONLY','t','t')");
            sql.execute("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at) VALUES('remote','gen','MANAGED','" + "a".repeat(64) + "','t')");
            sql.execute("DROP TRIGGER trg_candidate_owner_scope_insert");
            sql.execute("INSERT INTO ai_candidate_submission_run(id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,state,max_attempts,created_at,updated_at) VALUES('invalid','s','TASK_DECOMPOSITION','missing','DECOMPOSITION_PLAN_V2','PLANNING',1,0,'INTERNAL_MCP','DECOMPOSITION_PLAN_V2','gen','remote','OPEN',5,'t','t')");
        }
        assertThatThrownBy(() -> Flyway.configure().dataSource(url, null, null).target("129").load().migrate())
                .hasStackTraceContaining("candidate owner scope mismatch");
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var row = sql.executeQuery("SELECT id,owner_id FROM ai_candidate_submission_run")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("invalid"); assertThat(row.getString(2)).isEqualTo("missing");
            }
        }
    }

    private Map<String, String> schema(Statement sql) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var rows = sql.executeQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL "
                + "AND name<>'ai_candidate_submission_run' ORDER BY type,name")) {
            while (rows.next()) result.put(rows.getString(1) + ":" + rows.getString(2), rows.getString(3));
        }
        return result;
    }

    private Map<String, Object> history(Statement sql) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String table : new String[]{"ai_candidate_submission_run", "ai_candidate_submission_attempt"}) {
            try (var rows = sql.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                while (rows.next()) {
                    for (int col = 1; col <= rows.getMetaData().getColumnCount(); col++) {
                        result.put(table + ":" + rows.getString("id") + ":" + rows.getMetaData().getColumnName(col),
                                rows.getObject(col));
                    }
                }
            }
        }
        return result;
    }
}
