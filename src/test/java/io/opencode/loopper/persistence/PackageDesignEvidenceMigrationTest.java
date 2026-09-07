package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageDesignEvidenceMigrationTest {
    @TempDir Path root;

    @Test void upgradeAddsNoHistoricalEvidenceAndKeepsRunSchemaUnchanged() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("evidence.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("71").load().migrate();
        String before;
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT sql FROM sqlite_master WHERE name='ai_candidate_submission_run'")) {
            before = rows.getString(1);
        }
        Flyway.configure().dataSource(url, null, null).target("72").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT sql FROM sqlite_master WHERE name='ai_candidate_submission_run'")) {
                assertThat(rows.getString(1)).isEqualTo(before);
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM package_design_evidence")) { assertThat(rows.getInt(1)).isZero(); }
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO package_design_evidence VALUES('missing','PACKAGE_GAP_ASSESSMENT_V1','"
                    + "a".repeat(64) + "','{}','" + "b".repeat(64) + "','t')")).hasMessageContaining("FOREIGN KEY");
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }

    @Test void evidenceCannotBeReplacedAfterFreeze() throws Exception {
        // Minimal parent permits testing the new immutable table without manufacturing a running application.
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
            sql.execute("PRAGMA foreign_keys=ON");
            sql.execute("CREATE TABLE ai_candidate_submission_run(id TEXT PRIMARY KEY)");
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V72__package_design_frozen_evidence.sql"));
            for (String statement : migration.split("(?m)(?=^CREATE )")) {
                if (statement.startsWith("CREATE ")) sql.execute(statement);
            }
            sql.executeUpdate("INSERT INTO ai_candidate_submission_run VALUES('run')");
            sql.executeUpdate("INSERT INTO package_design_evidence VALUES('run','PACKAGE_GAP_ASSESSMENT_V1','"
                    + "a".repeat(64) + "','{}','" + "b".repeat(64) + "','t')");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE package_design_evidence SET snapshot_json='[]' WHERE run_id='run'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> sql.executeUpdate("DELETE FROM package_design_evidence WHERE run_id='run'"))
                    .hasMessageContaining("immutable");
        }
    }
}
