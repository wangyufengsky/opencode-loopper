package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateCandidateSubmissionMigrationTest {
    @TempDir Path root;
    @Test void upgradesV79PreservingBatchesAndEnforcesReplayAcceptanceAndCascade() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("candidates.db") + "?foreign_keys=on&transaction_mode=IMMEDIATE";
        Flyway.configure().dataSource(url, null, null).target("79").load().migrate();
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
            sql.executeUpdate("INSERT INTO template_candidate_submission VALUES('b',1,'bad','"+hash+"',0,NULL,'rejected','now')");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO template_candidate_submission VALUES('b',2,'bad','"+hash+"',0,NULL,'rejected','now')"))
                    .hasMessageContaining("UNIQUE");
            sql.executeUpdate("INSERT INTO template_candidate_submission VALUES('b',2,'good','"+hash+"',1,'{}','accepted','now')");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO template_candidate_submission VALUES('b',3,'second','"+hash+"',1,'{}','accepted','now')"))
                    .hasMessageContaining("UNIQUE");
            db.setAutoCommit(false); sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'"); db.rollback(); db.setAutoCommit(true);
            try (var rows = sql.executeQuery("SELECT count(*) FROM template_candidate_submission")) { assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = sql.executeQuery("SELECT state,output_json FROM template_task_batch WHERE id='b'")) {
                assertThat(rows.getString(1)).isEqualTo("RUNNING"); assertThat(rows.getString(2)).isNull();
            }
            sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'");
            try (var rows = sql.executeQuery("SELECT count(*) FROM template_candidate_submission")) { assertThat(rows.getInt(1)).isZero(); }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }
}
