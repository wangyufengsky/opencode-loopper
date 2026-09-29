package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcceptedWorkResultMigrationTest {
    @TempDir Path directory;

    @Test void upgradeRetainsOpenAndAcceptedHistoryWithoutInventingOutputs() throws Exception {
        String url = url("upgrade");
        migrate(url, "140");
        Map<String, Object> before;
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            seedOwner(sql);
            seedRun(sql, "old-open", false);
            seedRun(sql, "old-accepted", false);
            accept(sql, "old-accepted");
            before = history(sql);
        }
        migrate(url, null);
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            assertThat(history(sql)).isEqualTo(before);
            try (var rows = sql.executeQuery("SELECT result_storage_version FROM ai_candidate_submission_run")) {
                int count = 0;
                while (rows.next()) { assertThat(rows.getInt(1)).isZero(); count++; }
                assertThat(count).isEqualTo(2);
            }
            assertThat(count(sql, "accepted_work_result")).isZero();
            assertThatThrownBy(() -> sql.executeUpdate(
                    "UPDATE ai_candidate_submission_run SET result_storage_version=1 WHERE id='old-open'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> insertOutput(sql, "old-accepted")).hasMessageContaining("identity mismatch");
            accept(sql, "old-open");
            assertThat(count(sql, "ai_candidate_submission_attempt")).isEqualTo(2);
            assertThat(count(sql, "accepted_work_result")).isZero();
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }

    @Test void freshSchemaRequiresAcceptedIdentityAndCleansWithRollbackEvenWithoutCascades() throws Exception {
        String url = url("fresh");
        migrate(url, null);
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            seedOwner(sql);
            seedRun(sql, "new", true);
            assertThatThrownBy(() -> insertOutput(sql, "new")).hasMessageContaining("identity mismatch");
            accept(sql, "new");
            insertOutput(sql, "new");
            assertThat(count(sql, "accepted_work_result")).isEqualTo(1);
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE accepted_work_result SET content='{}'"))
                    .hasMessageContaining("immutable");
            // Explicit cleanup remains correct on legacy connections without FK cascades.
            sql.execute("PRAGMA foreign_keys=OFF");
            db.setAutoCommit(false);
            sql.executeUpdate("DELETE FROM ai_candidate_submission_run WHERE id='new'");
            assertThat(count(sql, "accepted_work_result")).isZero();
            db.rollback();
            assertThat(count(sql, "accepted_work_result")).isEqualTo(1);
            sql.executeUpdate("DELETE FROM ai_candidate_submission_attempt WHERE id='attempt-new'");
            assertThat(count(sql, "accepted_work_result")).isZero();
            db.rollback();
            db.setAutoCommit(true);
            sql.execute("PRAGMA foreign_keys=ON");
            sql.executeUpdate("DELETE FROM ai_candidate_submission_run WHERE id='new'");
            assertThat(count(sql, "accepted_work_result")).isZero();
            assertThat(count(sql, "ai_candidate_submission_attempt")).isZero();
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }

    private String url(String name) { return "jdbc:sqlite:" + directory.resolve(name + ".db") + "?foreign_keys=on"; }
    private void migrate(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, null, null);
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }
    private void seedOwner(Statement sql) throws Exception {
        sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','t','t')");
        sql.execute("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at) VALUES('s','p','RUNNING','READ_ONLY','t','t')");
        sql.execute("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) VALUES('m','s',1,'USER','req','PERSISTED','t')");
        sql.execute("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) VALUES('r','s',1,'m','req','[]',0,'ACTIVE','t','t')");
        sql.execute("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,source_draft_version,created_at,updated_at) VALUES('owner','s','r','RUNNING',0,'t','t')");
        sql.execute("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at) VALUES('remote','gen','MANAGED','" + "a".repeat(64) + "','t')");
    }
    private void seedRun(Statement sql, String id, boolean shared) throws Exception {
        sql.execute("INSERT INTO ai_candidate_submission_run(id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,state,max_attempts,created_at,updated_at"
                + (shared ? ",result_storage_version" : "") + ") VALUES('" + id
                + "','s','TASK_DECOMPOSITION','owner','DECOMPOSITION_PLAN_V2','" + id + "',1,0,'INTERNAL_MCP','DECOMPOSITION_PLAN_V2','gen','remote','OPEN',5,'t','t'"
                + (shared ? ",1" : "") + ")");
    }
    private void accept(Statement sql, String id) throws Exception {
        sql.execute("INSERT INTO ai_candidate_submission_attempt(id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,canonical_result_sha256,problems_json,response_json,created_at) VALUES('attempt-"
                + id + "','" + id + "',1,'accepted','" + "b".repeat(64) + "','ACCEPTED',0,'"
                + "a".repeat(64) + "','[]','{}','t')");
        sql.executeUpdate("UPDATE ai_candidate_submission_run SET state='ACCEPTED',attempts_used=1,version=1,terminal_attempt_id='attempt-" + id + "' WHERE id='" + id + "'");
    }
    private void insertOutput(Statement sql, String id) throws Exception {
        sql.executeUpdate("INSERT INTO accepted_work_result(id,run_id,content,sha256,created_at) VALUES('attempt-"
                + id + "','" + id + "','{}','" + "a".repeat(64) + "','t')");
    }
    private int count(Statement sql, String table) throws Exception {
        try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) { return rows.getInt(1); }
    }
    private Map<String, Object> history(Statement sql) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String table : new String[]{"ai_candidate_submission_run", "ai_candidate_submission_attempt"})
            try (var rows = sql.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                while (rows.next()) for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    String name = rows.getMetaData().getColumnName(column);
                    if (!name.equals("result_storage_version")) values.put(table + ":" + rows.getString("id") + ":" + name, rows.getObject(column));
                }
            }
        return values;
    }
}
