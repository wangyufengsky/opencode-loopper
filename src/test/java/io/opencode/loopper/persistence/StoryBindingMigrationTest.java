package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoryBindingMigrationTest {
    @TempDir Path root;
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
