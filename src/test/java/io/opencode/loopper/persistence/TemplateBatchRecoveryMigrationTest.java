package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateBatchRecoveryMigrationTest {
    @TempDir Path root;

    @Test void upgradeFromV110PreservesAcceptedRunningBatchAndRecoveryForeignKeysRollbackTogether() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("recovery.db") + "?foreign_keys=on&transaction_mode=IMMEDIATE";
        Flyway.configure().dataSource(url, null, null).target("110").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','now','now')");
            sql.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('t','p','report','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at) VALUES('s','t',0,'report','[]','[]','[]','[]','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at) VALUES('a','t','s',1,'RUNNING','now')");
            sql.executeUpdate("INSERT INTO template_task_batch(id,task_id,attempt_id,ordinal,purpose,input_json,input_sha256,state,created_at,updated_at) VALUES('b','t','a',0,'SNAPSHOT_LINKS','{}','hash','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO template_candidate_submission VALUES('b',1,'accepted','" + "a".repeat(64) + "',1,'{\"relations\":[]}','{\"outcome\":\"ACCEPTED\",\"action\":\"STOP\"}','now')");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate(); flyway.validate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var row = sql.executeQuery("SELECT state,output_json,generation FROM template_task_batch WHERE id='b'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("RUNNING");
                assertThat(row.getString(2)).isNull();
                assertThat(row.getInt(3)).isZero();
            }
            try (var row = sql.executeQuery("SELECT accepted,output_json,response_json FROM template_candidate_submission WHERE batch_id='b'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(1);
                assertThat(row.getString(2)).isEqualTo("{\"relations\":[]}");
                assertThat(row.getString(3)).contains("ACCEPTED", "STOP");
            }
            sql.executeUpdate("INSERT INTO template_batch_recovery(batch_id,session_id,prompt_sha256,action,command_id,requested_at,not_before) VALUES('b','session','hash','FINALIZE','command','now','now')");
            sql.executeUpdate("INSERT INTO template_batch_observation VALUES('b','session','hash','now','now','now','fingerprint','progress','busy',1)");
            sql.executeUpdate("INSERT INTO template_batch_recovery_command VALUES('command','b','FINALIZE',0,'now')");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO template_batch_recovery_command VALUES('foreign-command','missing','STOP',0,'now')"))
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE template_batch_recovery SET proof='UNKNOWN' WHERE batch_id='b'"))
                    .hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE template_batch_recovery SET action='RESTART' WHERE batch_id='b'"))
                    .hasMessageContaining("CHECK");
            db.setAutoCommit(false);
            sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'");
            db.rollback(); db.setAutoCommit(true);
            for (String table : java.util.List.of("template_batch_recovery", "template_batch_observation", "template_batch_recovery_command", "template_candidate_submission")) {
                try (var row = sql.executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(1);
                }
            }
            sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'");
            for (String table : java.util.List.of("template_batch_recovery", "template_batch_observation", "template_batch_recovery_command", "template_candidate_submission")) {
                try (var row = sql.executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isZero();
                }
            }
            try (var row = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(row.next()).isFalse(); }
        }
    }
}
