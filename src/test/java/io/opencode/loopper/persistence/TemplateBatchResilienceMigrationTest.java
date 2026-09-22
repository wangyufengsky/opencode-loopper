package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateBatchResilienceMigrationTest {
    @TempDir Path root;

    @Test void upgradePreservesRunningIdentitiesAndOldContractAndRollsBackCleanup() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("resilience.db") + "?foreign_keys=on&transaction_mode=IMMEDIATE";
        Flyway.configure().dataSource(url, null, null).target("115").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','now','now')");
            sql.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('t','p','report','WAITING_INPUT','now','now')");
            sql.executeUpdate("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at) VALUES('s','t',0,'report','[]','[]','[]','[]','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at) VALUES('a','t','s',1,'RUNNING','now')");
            sql.executeUpdate("INSERT INTO template_task_run(task_id,request_key,request_sha256,template_id,template_version,branch_id,branch_label,branch_ref,start_date,end_date,contract_json,created_at,updated_at) VALUES('t','key','hash','CODE_REVIEW','10','branch','main','refs/heads/main','2026-09-01','2026-09-01','{\"model\":\"frozen\"}','now','now')");
            sql.executeUpdate("INSERT INTO template_task_batch(id,task_id,attempt_id,ordinal,purpose,input_json,input_sha256,state,prompt_json,prompt_sha256,created_at,updated_at) VALUES('b','t','a',0,'REVIEW','{}','hash','RUNNING','{\"messageId\":\"original\"}','prompt','now','now')");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(7); flyway.validate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var row = sql.executeQuery("SELECT b.state,b.prompt_json,r.contract_json,COALESCE(json_extract(r.contract_json,'$.batchMaxRetries'),0) FROM template_task_batch b JOIN template_task_run r ON r.task_id=b.task_id")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("RUNNING");
                assertThat(row.getString(2)).contains("original"); assertThat(row.getString(3)).isEqualTo("{\"model\":\"frozen\"}");
                assertThat(row.getInt(4)).isZero();
            }
            sql.executeUpdate("INSERT INTO template_batch_retry_policy VALUES('b',2,3)");
            sql.executeUpdate("INSERT INTO template_batch_transport_issue VALUES('b','session','prompt','RUNNING','OPENCODE_STATUS_FAILED','unavailable','first','last',2,'next',1,NULL)");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE template_batch_retry_policy SET automatic_retries=4")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO template_batch_retry_policy VALUES('missing',0,3)")).hasMessageContaining("FOREIGN KEY");
            db.setAutoCommit(false); sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'"); db.rollback(); db.setAutoCommit(true);
            for (String table : java.util.List.of("template_batch_retry_policy", "template_batch_transport_issue")) {
                try (var row = sql.executeQuery("SELECT count(*) FROM " + table)) { assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(1); }
            }
            sql.executeUpdate("DELETE FROM template_task_batch WHERE id='b'");
            for (String table : java.util.List.of("template_batch_retry_policy", "template_batch_transport_issue")) {
                try (var row = sql.executeQuery("SELECT count(*) FROM " + table)) { assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isZero(); }
            }
            try (var row = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(row.next()).isFalse(); }
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
