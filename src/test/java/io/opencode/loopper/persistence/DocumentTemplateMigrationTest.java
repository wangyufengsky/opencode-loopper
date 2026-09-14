package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentTemplateMigrationTest {
    @TempDir Path root;
    @Test void upgradesSupportedReleaseKeepingAnActiveFirstPhaseReportAndItsSource() throws Exception {
        String url = url("release.db"); migrate(url, "83");
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','历史项目','/tmp/p','now','now')");
            sql.executeUpdate("INSERT INTO task(id,project_id,title,state,execution_mode,workspace_policy,created_at,updated_at) VALUES('t','p','旧报告','RUNNING','TEMPLATE_REPORT','ISOLATED_REPORT','now','now')");
            sql.executeUpdate("""
                INSERT INTO template_task_run(task_id,request_key,request_sha256,template_id,template_version,branch_id,branch_label,
                  branch_ref,start_date,end_date,contract_json,created_at,updated_at)
                VALUES('t','old-request','old-digest','CODE_REVIEW','7','local:main','main','refs/heads/main','2026-09-01','2026-09-02','{"frozen":"v7"}','now','now')
                """);
        }
        migrate(url, null);
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var rows = sql.executeQuery("SELECT state,source_template_id,document_run_id FROM task_list_item WHERE id='t'")) {
                assertThat(rows.getString(1)).isEqualTo("RUNNING"); assertThat(rows.getString(2)).isEqualTo("CODE_REVIEW"); assertThat(rows.getString(3)).isNull();
            }
            try (var rows = sql.executeQuery("SELECT contract_json FROM template_task_run WHERE task_id='t'")) { assertThat(rows.getString(1)).isEqualTo("{\"frozen\":\"v7\"}"); }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }
    @Test void addingAttemptsPreservesFrozenModelEvidenceAndForeignReferences() throws Exception {
        String url = url("attempts.db"); migrate(url, "88");
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','now','now')");
            sql.executeUpdate("INSERT INTO document_template_run(id,request_key,request_sha256,project_id,template_id,template_version,title,state,contract_json,created_at,updated_at) VALUES('d','request','digest','p','REQUIREMENT_CODE_REVIEW','1','需求','ANALYZING','{}','now','now')");
            sql.executeUpdate("INSERT INTO document_template_model_run(id,run_id,candidate_kind,ordinal,generation,state,input_json,input_sha256,external_session_id,output_json,output_sha256,created_at,updated_at) VALUES('model','d','REQUIREMENT_CODE_ASSESSMENT_V1',0,0,'RUNNING','{}','input','remote','{}','output','now','now')");
            sql.executeUpdate("INSERT INTO document_code_read(model_id,path,blob_sha,start_line,end_line,content,sha256,created_at) VALUES('model','app.java','blob',1,1,'old source','sha','now')");
        }
        migrate(url, null);
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var rows = sql.executeQuery("SELECT attempt,external_session_id,output_sha256 FROM document_template_model_run WHERE id='model'")) {
                assertThat(rows.getInt(1)).isZero(); assertThat(rows.getString(2)).isEqualTo("remote"); assertThat(rows.getString(3)).isEqualTo("output");
            }
            try (var rows = sql.executeQuery("SELECT content FROM document_code_read WHERE model_id='model'")) { assertThat(rows.getString(1)).isEqualTo("old source"); }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
            assertThatThrownBy(() -> sql.executeUpdate("DELETE FROM document_template_model_run WHERE id='model'"))
                    .hasMessageContaining("FOREIGN KEY");
        }
    }
    private String url(String name) { return "jdbc:sqlite:" + root.resolve(name) + "?foreign_keys=on&transaction_mode=IMMEDIATE"; }
    private void migrate(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, null, null);
        if (target != null) configuration.target(target);
        var flyway = configuration.load(); flyway.migrate(); flyway.validate();
    }
}
