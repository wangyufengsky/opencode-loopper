package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BatchAssistMigrationTest {
    @TempDir Path root;
    @Test void upgradesV82AndFreezesOnlyNewTaskResources() throws Exception {
        String url="jdbc:sqlite:"+root.resolve("batch-upgrade.db")+"?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("82").load().migrate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','p','/project','now','now')");
            sql.execute("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('old','p','old','WAITING_INPUT','now','now')");
        }
        var migration=Flyway.configure().dataSource(url,null,null).load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(39);migration.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO assist_project_config VALUES('p','{\"repository\":\"group/project\",\"sources\":[]}',0,'now')");
            sql.execute("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('new','p','new','PENDING_START','now','now')");
            sql.execute("UPDATE assist_project_config SET config_json='{}' WHERE project_id='p'");
            try(var rows=sql.executeQuery("SELECT config_json FROM assist_batch_binding WHERE owner_key='TASK:old'")) {assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("{}");}
            try(var rows=sql.executeQuery("SELECT config_json FROM assist_batch_binding WHERE owner_key='TASK:new'")) {assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).contains("group/project");}
            try(var rows=sql.executeQuery("SELECT state FROM task WHERE id='old'")) {assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("WAITING_INPUT");}
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")) {assertThat(rows.next()).isFalse();}
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
