package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DesignerTimeoutMigrationTest {
    @TempDir Path root;
    @Test void upgradeLeavesHistoricalDesignPolicyAbsentAndRollsBackDeletion() throws Exception {
        String url="jdbc:sqlite:"+root.resolve("policy.db")+"?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("105").load().migrate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/project','now','now')");
            sql.execute("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at) VALUES('d','p','PENDING_HANDOFF','READ_ONLY','now','now')");
        }
        var migration=Flyway.configure().dataSource(url,null,null).target("106").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);migration.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            try(var rows=sql.executeQuery("SELECT count(*) FROM designer_timeout_policy")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
            sql.execute("INSERT INTO designer_timeout_policy VALUES('d',0,1800)");
            db.setAutoCommit(false);sql.execute("DELETE FROM designer_session WHERE id='d'");db.rollback();db.setAutoCommit(true);
            try(var rows=sql.executeQuery("SELECT enabled FROM designer_timeout_policy WHERE designer_id='d'")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
            sql.execute("DELETE FROM designer_session WHERE id='d'");
            try(var rows=sql.executeQuery("SELECT count(*) FROM designer_timeout_policy")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(rows.next()).isFalse();}
        }
    }
}
