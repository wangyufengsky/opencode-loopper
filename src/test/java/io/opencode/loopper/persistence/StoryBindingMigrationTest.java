package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoryBindingMigrationTest {
    @TempDir Path root;
    @Test void upgradesV66WithForeignKeysPreservingActivityAndEnforcingSingleExplicitRetry() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("v66.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("66").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','fixture','/fixture','now','now')");
            statement.execute("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at) VALUES('d','p','COMPLETED','READ_ONLY','now','now')");
            statement.execute("INSERT INTO story_binding VALUES('b','SYS-001','000123',1,'now')");
            statement.execute("INSERT INTO story_accounting_session VALUES('s','b','d',NULL,'remote',NULL,'/fixture','REQUIREMENT_DESIGNER',1,'start',1,'COMPLETE_FAILED','run','now','now')");
            statement.execute("INSERT INTO story_accounting_call VALUES('c','s','COMPLETE','msg_old','complete','complete','FAILED','run','old receipt','ERROR','old error',1,'before','after')");
            statement.execute("INSERT INTO story_accounting_activity VALUES('c','[{\"content\":\"original output\"}]','dismissed')");
        }
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
            try (var rows = statement.executeQuery("SELECT result_text,parts_json,dismissed_at FROM story_accounting_call JOIN story_accounting_activity ON call_id=id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("old receipt");
                assertThat(rows.getString(2)).contains("original output");
                assertThat(rows.getString(3)).isEqualTo("dismissed");
            }
            statement.execute("INSERT INTO story_accounting_call(id,accounting_session_id,phase,message_id,operation,arguments_text,state,started_at,retry_of) VALUES('retry','s','COMPLETE','msg_new','complete','complete','PREPARED','now','c')");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.execute("INSERT INTO story_accounting_call(id,accounting_session_id,phase,message_id,operation,arguments_text,state,started_at,retry_of) VALUES('duplicate','s','COMPLETE','msg_dup','complete','complete','PREPARED','now','c')"))
                    .isInstanceOf(java.sql.SQLException.class);
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }
    @Test void upgradesV65CallsWithoutLosingReceiptsAndAllowsManualCancellation() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("v65.db");
        Flyway.configure().dataSource(url, null, null).target("65").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO story_binding VALUES('b','SYS-001','000123',1,'2026-09-03T00:00:00Z')");
            // Minimal historical ownership fixture; foreign key integrity is exercised by the Spring integration tests.
            statement.execute("INSERT INTO story_accounting_session VALUES('s','b','historical-designer',NULL,'remote',NULL,'/tmp','ROUTER',1,'start',1,'ACTIVE','run','2026-09-03T00:00:00Z','2026-09-03T00:00:01Z')");
            statement.execute("INSERT INTO story_accounting_call VALUES('c','s','BEGIN','msg_stat','start','start SYS-001 000123','SUCCEEDED','run','receipt',NULL,NULL,0,'2026-09-03T00:00:00Z','2026-09-03T00:00:01Z')");
        }
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT call.result_text,activity.dismissed_at FROM story_accounting_call call JOIN story_accounting_activity activity ON activity.call_id=call.id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("receipt"); assertThat(rows.getString(2)).isNotBlank();
            }
            statement.execute("UPDATE story_accounting_call SET state='CANCELLING'");
            statement.execute("UPDATE story_accounting_call SET state='CANCELLED'");
        }
    }

    @Test void upgradesV64WithoutOptingHistoricalSessionsIntoAccounting() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("upgrade.db");
        Flyway.configure().dataSource(url, null, null).target("64").load().migrate();
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            for (String table : new String[]{"story_binding", "designer_story_binding", "task_story_binding",
                    "story_accounting_session", "story_accounting_call", "story_accounting_active_remote"}) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }
}
