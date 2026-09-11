package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateTaskMigrationTest {
    @TempDir Path directory;

    @Test void upgradePreservesTaskForeignKeysAndModesWhileRetiringNewAutomationTriggers() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("76").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','project','/fixture','now','now')");
            sql.execute("INSERT INTO task(id,project_id,title,state,created_at,updated_at,execution_mode,workspace_policy) VALUES('old','p','old','PENDING_START','now','now','ROLLING_PACKAGES','PINNED_DIRECT')");
            sql.execute("INSERT INTO loopspec_template VALUES('t','template','','ACTIVE','now','now',0)");
            sql.execute("INSERT INTO loopspec_template_version VALUES('v','t',1,'{}','hash',1,0,'now')");
            sql.execute("INSERT INTO automation_rule VALUES('r','rule','p','v','CRON','ENABLED','REVIEW_REQUIRED','{}',NULL,NULL,'now','now',7)");
            sql.execute("INSERT INTO story_binding VALUES('story','001','0002',0,'now')");
            sql.execute("INSERT INTO task_story_binding VALUES('old','story')");
        }
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT execution_mode,workspace_policy FROM task WHERE id='old'")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("ROLLING_PACKAGES"); assertThat(row.getString(2)).isEqualTo("PINNED_DIRECT");
            }
            try (var row = sql.executeQuery("SELECT state,version FROM automation_rule WHERE id='r'")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("DISABLED"); assertThat(row.getInt(2)).isEqualTo(8);
            }
            try (var row = sql.executeQuery("SELECT binding_id FROM task_story_binding WHERE task_id='old'")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("story");
            }
            sql.execute("INSERT INTO task(id,project_id,title,state,created_at,updated_at,execution_mode,workspace_policy) VALUES('new','p','report','PENDING_START','now','now','TEMPLATE_REPORT','ISOLATED_REPORT')");
            assertThatThrownBy(() -> sql.execute("UPDATE task SET execution_mode='UNKNOWN' WHERE id='old'")).isInstanceOf(java.sql.SQLException.class);
            try (var row = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(row.next()).isFalse(); }
        }
    }

    @Test void freshSchemaEnforcesOneIdempotentRunAndDateOrder() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("fresh.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','project','/fixture','now','now')");
            sql.execute("INSERT INTO task(id,project_id,title,state,created_at,updated_at,execution_mode,workspace_policy) VALUES('new','p','report','PENDING_START','now','now','TEMPLATE_REPORT','ISOLATED_REPORT')");
            String insert = "INSERT INTO template_task_run(task_id,request_key,request_sha256,template_id,template_version,branch_id,branch_label,branch_ref,start_date,end_date,contract_json,created_at,updated_at) VALUES('new','key','hash','CODE_REVIEW','1','branch','main','refs/heads/main','2026-09-11','2026-09-11','{}','now','now')";
            sql.execute(insert);
            assertThatThrownBy(() -> sql.execute(insert)).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> sql.execute("UPDATE template_task_run SET end_date='2026-09-10'")).isInstanceOf(java.sql.SQLException.class);
            sql.execute("DELETE FROM task WHERE id='new'");
            try (var row = sql.executeQuery("SELECT count(*) FROM template_task_run")) { row.next(); assertThat(row.getInt(1)).isZero(); }
            try (var row = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(row.next()).isFalse(); }
        }
    }
}
