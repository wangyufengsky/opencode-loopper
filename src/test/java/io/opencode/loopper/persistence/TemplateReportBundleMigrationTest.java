package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateReportBundleMigrationTest {
    @TempDir Path root;
    @Test void upgradesV78AndKeepsCountersAfterBundleDeletionWithRollbackAndForeignKeys() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("reports.db") + "?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE";
        Flyway.configure().dataSource(url, null, null).target("78").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','项目','/tmp/p','now','now')");
            sql.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('t','p','历史报告','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at) VALUES('s','t',0,'报告','[]','[]','[]','[]','RUNNING','now','now')");
            sql.executeUpdate("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at) VALUES('a','t','s',1,'RUNNING','now')");
        }
        var migrated = Flyway.configure().dataSource(url, null, null).load(); migrated.migrate(); migrated.validate();
        assertThat(migrated.info().current().getVersion().getVersion()).isEqualTo("120");
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO template_report_sequence VALUES('namespace',1)");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO template_report_bundle VALUES('missing','t','namespace',1,'项目','目录','主报告.md')"))
                    .hasMessageContaining("FOREIGN KEY");
            sql.executeUpdate("INSERT INTO template_report_bundle VALUES('a','t','namespace',1,'项目','目录','主报告.md')");
            db.setAutoCommit(false);
            sql.executeUpdate("DELETE FROM template_report_bundle WHERE task_id='t'");
            sql.executeUpdate("UPDATE template_report_sequence SET last_sequence=2");
            db.rollback(); db.setAutoCommit(true);
            try (var rows = sql.executeQuery("SELECT count(*) FROM template_report_bundle")) { assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = sql.executeQuery("SELECT last_sequence FROM template_report_sequence")) { assertThat(rows.getInt(1)).isEqualTo(1); }
            sql.executeUpdate("DELETE FROM template_report_bundle WHERE task_id='t'");
            sql.executeUpdate("DELETE FROM attempt WHERE id='a'");
            try (var rows = sql.executeQuery("SELECT last_sequence FROM template_report_sequence")) { assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = sql.executeQuery("SELECT title FROM task WHERE id='t'")) { assertThat(rows.getString(1)).isEqualTo("历史报告"); }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }
}
