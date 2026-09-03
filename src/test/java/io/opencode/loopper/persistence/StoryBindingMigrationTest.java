package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoryBindingMigrationTest {
    @TempDir Path root;
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
