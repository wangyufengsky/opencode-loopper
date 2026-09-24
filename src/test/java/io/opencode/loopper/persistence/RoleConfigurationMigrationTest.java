package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class RoleConfigurationMigrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"126", "138"})
    void upgradesPreserveHistoricalBusinessRowsWithoutInventingRoleSnapshots(String version) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("roles-" + version + ".db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target(version).load().migrate();
        Map<String, String> schema = new LinkedHashMap<>();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('project','历史项目','/tmp/role-history','t','t')");
            statement.execute("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at) VALUES('designer','project','RUNNING','READ_ONLY','t','t')");
            statement.execute("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) VALUES('message','designer',1,'USER','原始需求不可重解释','PERSISTED','t')");
            try (var rows = statement.executeQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name")) {
                while (rows.next()) schema.put(rows.getString(1) + ":" + rows.getString(2), rows.getString(3));
            }
        }

        Flyway.configure().dataSource(url, null, null).load().migrate();

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT content,delivery_state FROM designer_message WHERE id='message'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("原始需求不可重解释");
                assertThat(rows.getString(2)).isEqualTo("PERSISTED");
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM role_owner_snapshot")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM role_session_snapshot")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            if (version.equals("138")) {
                try (var rows = statement.executeQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL")) {
                    while (rows.next()) {
                        String key = rows.getString(1) + ":" + rows.getString(2);
                        if (schema.containsKey(key)) assertThat(rows.getString(3)).as(key).isEqualTo(schema.remove(key));
                    }
                }
                assertThat(schema).isEmpty();
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
            try (var rows = statement.executeQuery("PRAGMA integrity_check")) { assertThat(rows.getString(1)).isEqualTo("ok"); }
        }
    }
}
