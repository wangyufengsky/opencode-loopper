package io.opencode.loopper.persistence;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
class SnapshotReviewLightweightMigrationTest {
    @TempDir Path temp;
    @Test void upgrades112WithoutChangingExistingProjectOrFrozenContracts() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("upgrade.db");
        Flyway.configure().dataSource(url, null, null).target("112").load().migrate();
        try (var db = DriverManager.getConnection(url); var s = db.createStatement()) {
            s.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('existing','Existing','/fixture','2026-01-01','2026-01-01')");
        }
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var db = DriverManager.getConnection(url); var s = db.createStatement()) {
            for (String table : java.util.List.of("snapshot_review_context_request", "snapshot_review_reusable", "snapshot_review_reuse"))
                try (var rs = s.executeQuery("SELECT count(*) FROM " + table)) { assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isZero(); }
            try (var rs = s.executeQuery("SELECT name FROM project WHERE id='existing'")) { assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isEqualTo("Existing"); }
            try (var rs = s.executeQuery("PRAGMA foreign_key_check")) { assertThat(rs.next()).isFalse(); }
        }
    }
}
