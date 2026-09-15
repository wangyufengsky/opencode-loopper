package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateBatchRetryMigrationTest {
    @TempDir Path root;
    @Test void upgradeRetainsAcceptedReceiptsAndContinuationForeignKeysAcrossRetryGenerations() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("retry.db") + "?foreign_keys=on&transaction_mode=IMMEDIATE";
        Flyway.configure().dataSource(url, null, null).target("108").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','now','now')");
            sql.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('t','p','report','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at) VALUES('s','t',0,'report','[]','[]','[]','[]','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at) VALUES('a','t','s',1,'RUNNING','now')");
            sql.executeUpdate("INSERT INTO template_task_batch(id,task_id,attempt_id,ordinal,purpose,input_json,input_sha256,state,created_at,updated_at) VALUES('b','t','a',0,'REVIEW','{}','hash','FAILED','now','now')");
            sql.executeUpdate("INSERT INTO template_candidate_submission VALUES('b',1,'old','" + "a".repeat(64) + "',0,NULL,'{\"outcome\":\"REJECTED\"}','now')");
            sql.executeUpdate("INSERT INTO template_length_continuation VALUES('b',1,'{}','{}','" + "b".repeat(64) + "',0,1,'now')");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).load(); flyway.migrate(); flyway.validate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var row = sql.executeQuery("SELECT state,generation FROM template_task_batch WHERE id='b'")) {
                assertThat(row.getString(1)).isEqualTo("FAILED"); assertThat(row.getInt(2)).isZero();
            }
            sql.executeUpdate("INSERT INTO template_task_batch(id,task_id,attempt_id,ordinal,purpose,input_json,input_sha256,state,created_at,updated_at,generation) VALUES('next','t','a',0,'REVIEW','{}','hash','PREPARED','now','now',1)");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO template_task_batch SELECT 'duplicate',task_id,attempt_id,session_id,ordinal,purpose,input_json,input_sha256,state,creation_plan_json,prompt_json,prompt_sha256,output_json,error_code,error_message,created_at,updated_at,version,generation FROM template_task_batch WHERE id='next'"))
                    .hasMessageContaining("UNIQUE");
            try (var row = sql.executeQuery("SELECT response_json FROM template_candidate_submission WHERE batch_id='b'")) { assertThat(row.getString(1)).contains("REJECTED"); }
            try (var row = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(row.next()).isFalse(); }
            sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'");
            try (var row = sql.executeQuery("SELECT count(*) FROM template_length_continuation")) { assertThat(row.getInt(1)).isZero(); }
            try (var row = sql.executeQuery("SELECT count(*) FROM template_candidate_submission")) { assertThat(row.getInt(1)).isZero(); }
            try (var row = sql.executeQuery("SELECT count(*) FROM template_task_batch")) { assertThat(row.getInt(1)).isEqualTo(1); }
        }
    }
}
