package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesignerConversationMigrationTest {
    @TempDir Path directory;
    @Test void upgradingV67PreservesOldDesignerAndDoesNotOptItIntoReuse() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("upgrade.db");
        Flyway.configure().dataSource(url, null, null).target("67").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/tmp/p','t','t')");
            sql.execute("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at,external_session_id,workflow_phase) VALUES('old','p','REVIEWING','READ_ONLY','t','t','old-remote','DISCUSSING_REQUIREMENT')");
        }
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            for (String table : java.util.List.of("designer_conversation_policy", "designer_conversation", "designer_conversation_turn")) {
                try (var result = sql.executeQuery("SELECT COUNT(*) FROM " + table)) { assertThat(result.getInt(1)).isZero(); }
            }
            try (var result = sql.executeQuery("SELECT external_session_id FROM story_accounting_active_remote")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("old-remote");
            }
            try (var result = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(result.next()).isFalse(); }
        }
    }
}
