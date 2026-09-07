package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageSemanticPreparationMigrationTest {
    @TempDir Path root;
    @Test void appendsPreparationWithoutChangingHistoricalRunContractsOrForeignKeys() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("history.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("72").load().migrate();
        String before;
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement();
             var rows = sql.executeQuery("SELECT sql FROM sqlite_master WHERE name='ai_candidate_submission_run'")) { before = rows.getString(1); }
        Flyway.configure().dataSource(url, null, null).target("73").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var rows = sql.executeQuery("SELECT sql FROM sqlite_master WHERE name='ai_candidate_submission_run'")) { assertThat(rows.getString(1)).isEqualTo(before); }
            try (var rows = sql.executeQuery("SELECT sql FROM sqlite_master WHERE name='designer_conversation_turn'")) { assertThat(rows.getString(1)).contains("PACKAGE_SEMANTICS", "PACKAGE_DESIGN", "PACKAGE_QUESTION", "REQUIREMENT"); }
            try (var rows = sql.executeQuery("SELECT count(*) FROM package_semantic_preparation")) { assertThat(rows.getInt(1)).isZero(); }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO package_semantic_preparation VALUES('x','missing',1,'remote','" + "a".repeat(64) + "','PACKAGE_SEMANTIC_PREPARATION_V1','[]','original','PREPARED',null,null,'t',0)"))
                    .hasMessageContaining("FOREIGN KEY");
        }
    }
}
