package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateContinuationMigrationTest {
    @TempDir Path root;
    @Test void upgradesV80PreservingBatchesAndEnforcesContinuationIntegrity() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("continuations.db") + "?foreign_keys=on&transaction_mode=IMMEDIATE";
        Flyway.configure().dataSource(url, null, null).target("80").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','now','now')");
            sql.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('t','p','legacy','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at) VALUES('s','t',0,'report','[]','[]','[]','[]','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at) VALUES('a','t','s',1,'RUNNING','now')");
            sql.executeUpdate("INSERT INTO template_task_batch(id,task_id,attempt_id,ordinal,purpose,input_json,input_sha256,state,created_at,updated_at) VALUES('b','t','a',0,'REVIEW','{}','hash','RUNNING','now','now')");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).load(); flyway.migrate(); flyway.validate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            String hash = "a".repeat(64);
            String insert = "INSERT INTO template_length_continuation VALUES('b',1,'{}','{}','" + hash + "',0,1,'now')";
            sql.executeUpdate(insert);
            assertThatThrownBy(() -> sql.executeUpdate(insert)).hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO template_length_continuation VALUES('b',2,'{}','{}','" + hash + "',0,3,'now')"))
                    .hasMessageContaining("CHECK");
            db.setAutoCommit(false); sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'"); db.rollback(); db.setAutoCommit(true);
            try (var rows = sql.executeQuery("SELECT count(*) FROM template_length_continuation")) { assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = sql.executeQuery("SELECT state,output_json FROM template_task_batch WHERE id='b'")) {
                assertThat(rows.getString(1)).isEqualTo("RUNNING"); assertThat(rows.getString(2)).isNull();
            }
            sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'");
            try (var rows = sql.executeQuery("SELECT count(*) FROM template_length_continuation")) { assertThat(rows.getInt(1)).isZero(); }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }
}
