package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStackProfileOrderMigrationTest {
    @TempDir Path directory;

    @Test void upgradePreservesSnapshotsAndFreezesTheirOrderAcrossDatabaseCompaction() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("profiles.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("190").load().migrate();
        List<String> before;
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','root','t','t')");
            sql.execute("INSERT INTO project_stack_profile(id,project_id,analysis_state,manifest_fingerprint,"
                    + "technology_families_json,analyzed_at,created_at) VALUES "
                    + "('z-old','p','READY','java','[\"java\"]','2030-01-01T00:00:00Z','2030-01-01T00:00:00Z'),"
                    + "('a-new','p','READY','node','[\"node\"]','2030-01-01T00:00:00Z','2030-01-01T00:00:00Z')");
            sql.execute("INSERT INTO project_stack_component(profile_id,component_key,relative_root) VALUES('z-old','root','.')");
            before = snapshots(sql);
        }

        Flyway.configure().dataSource(url, null, null).target("191").load().migrate();

        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            assertThat(snapshots(sql)).isEqualTo(before);
            List<String> order = orderedIds(sql);
            assertThat(order).containsExactly("a-new", "z-old");
            sql.execute("VACUUM");
            assertThat(orderedIds(sql)).isEqualTo(order);
            assertThat(snapshots(sql)).isEqualTo(before);
            try (var rows = sql.executeQuery("SELECT profile_id FROM project_stack_component")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("z-old");
            }
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }

    private List<String> snapshots(Statement sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (var rows = sql.executeQuery("SELECT id,manifest_fingerprint,technology_families_json,analyzed_at,created_at "
                + "FROM project_stack_profile ORDER BY id")) {
            while (rows.next()) result.add(rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getString(3)
                    + "|" + rows.getString(4) + "|" + rows.getString(5));
        }
        return result;
    }

    private List<String> orderedIds(Statement sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (var rows = sql.executeQuery("SELECT id FROM project_stack_profile WHERE project_id='p' ORDER BY ordinal DESC")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }
}
