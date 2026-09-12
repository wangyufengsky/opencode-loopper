package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateDocumentProgressMigrationTest {
    @TempDir Path directory;

    @Test void v77UpgradePreservesFrozenRunsAndAddsNullableProjectDefaultAndCascadingPlan() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("77").load().migrate();
        String contract = "{\"definition\":{\"version\":\"2\"},\"reportTemplates\":{\"sha256\":\"frozen\"}}";
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','project','/fixture','now','now')");
            sql.execute("INSERT INTO task(id,project_id,title,state,created_at,updated_at,execution_mode,workspace_policy) VALUES('t','p','report','PENDING_START','now','now','TEMPLATE_REPORT','ISOLATED_REPORT')");
            sql.execute("INSERT INTO template_task_run(task_id,request_key,request_sha256,template_id,template_version,branch_id,branch_label,branch_ref,start_date,end_date,contract_json,created_at,updated_at) VALUES('t','key','hash','CODE_REVIEW','2','branch','main','refs/heads/main','2026-09-11','2026-09-11','" + contract + "','now','now')");
        }
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT contract_json,request_sha256 FROM template_task_run WHERE task_id='t'")) {
                row.next(); assertThat(row.getString(1)).isEqualTo(contract); assertThat(row.getString(2)).isEqualTo("hash");
            }
            try (var row = sql.executeQuery("SELECT document_path FROM project WHERE id='p'")) { row.next(); assertThat(row.getString(1)).isNull(); }
            sql.execute("INSERT INTO template_task_plan VALUES('t',20,3)");
            assertThatThrownBy(() -> sql.execute("UPDATE template_task_plan SET review_batches=-1")).isInstanceOf(java.sql.SQLException.class);
            sql.execute("DELETE FROM task WHERE id='t'");
            try (var row = sql.executeQuery("SELECT count(*) FROM template_task_plan")) { row.next(); assertThat(row.getInt(1)).isZero(); }
            try (var row = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(row.next()).isFalse(); }
        }
    }
}
