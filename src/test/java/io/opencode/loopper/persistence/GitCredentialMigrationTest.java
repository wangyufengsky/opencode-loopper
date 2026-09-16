package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class GitCredentialMigrationTest {
    @TempDir Path temp;
    @Test void upgradesExistingVersion111AndLeavesProjectsUnconfigured() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("upgrade.db");
        Flyway.configure().dataSource(url, null, null).target("111").load().migrate();
        try (var db = DriverManager.getConnection(url); var s = db.createStatement()) {
            s.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('existing','Existing','/fixture','2026-01-01','2026-01-01')");
        }
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var db = DriverManager.getConnection(url); var s = db.createStatement()) {
            try (var rs = s.executeQuery("SELECT count(*) FROM project WHERE id='existing'")) { assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isEqualTo(1); }
            try (var rs = s.executeQuery("SELECT count(*) FROM git_credential")) { assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isZero(); }
        }
    }
}
