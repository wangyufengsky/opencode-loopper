package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageBehaviorMigrationTest {
    @TempDir Path root;
    @Test void rollbackAcceptsEmptyV74ButRejectsExperimentalHistoryWithoutRewritingIt() throws Exception {
        for (boolean hasHistory : java.util.List.of(false, true)) {
            String url = "jdbc:sqlite:" + root.resolve("rollback-" + hasHistory + ".db") + "?foreign_keys=on";
            Flyway.configure().dataSource(url, null, null).target("74").load().migrate();
            if (hasHistory) {
                try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
                    sql.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','P','/tmp/p','now','now')");
                    sql.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at) VALUES('d','p','RUNNING','READ_ONLY','now','now')");
                    sql.executeUpdate("INSERT INTO designer_conversation(id,designer_session_id,scope_key,generation,root_path,profile,model_json,state,created_at,updated_at) VALUES('c','d','WP-1',1,'/tmp/p','DESIGNER_PACKAGE_V2','{}','OPEN','now','now')");
                    sql.executeUpdate("INSERT INTO package_behavior_policy VALUES('c','PACKAGE_BEHAVIOR_POLICY_V1')");
                }
            }
            var rollback = Flyway.configure().dataSource(url, null, null).load();
            if (hasHistory) {
                assertThatThrownBy(rollback::migrate).hasStackTraceContaining("PACKAGE_BEHAVIOR_HISTORY_REQUIRES_0_3_79");
                assertThat(rollback.info().current().getVersion().getVersion()).isEqualTo("74");
                try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
                    try (var rows = sql.executeQuery("SELECT policy_version FROM package_behavior_policy WHERE conversation_id='c'")) { assertThat(rows.getString(1)).isEqualTo("PACKAGE_BEHAVIOR_POLICY_V1"); }
                    try (var rows = sql.executeQuery("SELECT state FROM designer_session WHERE id='d'")) { assertThat(rows.getString(1)).isEqualTo("RUNNING"); }
                    try (var rows = sql.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='package_behavior_rollback_check'")) { assertThat(rows.getInt(1)).isZero(); }
                }
            } else {
                rollback.migrate(); rollback.validate();
                assertThat(rollback.info().current().getVersion().getVersion()).isEqualTo("75");
            }
        }
    }

    @Test void v74DoesNotBackfillHistoricalPoliciesOrRewriteTurnsAndEnforcesForeignKeys() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("history.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("73").load().migrate();
        String before;
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement(); var rows = sql.executeQuery("SELECT sql FROM sqlite_master WHERE name='designer_conversation_turn'")) { before = rows.getString(1); }
        Flyway.configure().dataSource(url, null, null).target("74").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var rows = sql.executeQuery("SELECT sql FROM sqlite_master WHERE name='designer_conversation_turn'")) { assertThat(rows.getString(1)).isEqualTo(before); }
            try (var rows = sql.executeQuery("SELECT count(*) FROM package_behavior_policy")) { assertThat(rows.getInt(1)).isZero(); }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO package_behavior_policy VALUES('missing','PACKAGE_BEHAVIOR_POLICY_V1')")).hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO package_behavior_run VALUES('missing','missing','" + "a".repeat(64) + "')")).hasMessageContaining("FOREIGN KEY");
        }
    }
}
