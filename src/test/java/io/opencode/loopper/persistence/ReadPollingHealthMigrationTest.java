package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadPollingHealthMigrationTest {
    @TempDir Path root;
    @Test void upgradesV75PreservingRuleAuthorityAndAddsIndependentHealthWithCascade() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("75").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','project','/fixture','now','now')");
            sql.execute("INSERT INTO loopspec_template VALUES('t','template','','ACTIVE','now','now',0)");
            sql.execute("INSERT INTO loopspec_template_version VALUES('v','t',1,'{}','hash',1,0,'now')");
            sql.execute("INSERT INTO automation_rule VALUES('r','rule','p','v','CRON','ENABLED','REVIEW_REQUIRED','{}',NULL,NULL,'now','now',7)");
        }
        Flyway.configure().dataSource(url, null, null).target("76").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT state,approval_mode,version FROM automation_rule WHERE id='r'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("ENABLED");
                assertThat(rows.getString(2)).isEqualTo("REVIEW_REQUIRED");
                assertThat(rows.getInt(3)).isEqualTo(7);
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM automation_poll_health")) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
            sql.execute("INSERT INTO automation_poll_health VALUES('r',7,'FAILED','now',NULL,1,'DETECTION_FAILED','safe')");
            sql.execute("DELETE FROM automation_rule WHERE id='r'");
            try (var rows = sql.executeQuery("SELECT count(*) FROM automation_poll_health")) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }

    @Test void freshSchemaIncludesBoundedPollingIndexes() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("fresh.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='index' AND name IN ('idx_task_monitor_active','idx_automation_reconcile_active')")) {
            rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
        }
    }
}
