package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AssistMigrationTest {
    @TempDir Path temp;
    @Test void upgradesV81AndKeepsTheOriginalSchemaHistory() throws Exception {
        String url="jdbc:sqlite:"+temp.resolve("upgrade.db");
        Flyway.configure().dataSource(url,null,null).locations("classpath:db/migration").target("81").load().migrate();
        try(var connection=DriverManager.getConnection(url);var statement=connection.createStatement()) {
            statement.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('project','project','/project','now','now')");
        }
        var flyway=Flyway.configure().dataSource(url,null,null).locations("classpath:db/migration").load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(35);flyway.validate();
        try(var connection=DriverManager.getConnection(url);var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM project WHERE id='project'")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(1);}
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
