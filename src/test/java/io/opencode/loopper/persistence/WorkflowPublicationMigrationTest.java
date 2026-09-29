package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowPublicationMigrationTest {
    @TempDir Path directory;
    @Test void upgradeRetainsRowsAndEveryUnrelatedSchemaObject()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("publication.db")+"?foreign_keys=on";var before=new TreeMap<String,String>();
        Flyway.configure().dataSource(url,null,null).target("185").load().migrate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('retained','原项目','/tmp/retained-reuse','original','original')");
            try(var rows=sql.executeQuery("SELECT type||':'||name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'")){while(rows.next())before.put(rows.getString(1),rows.getString(2));}
        }
        var migration=Flyway.configure().dataSource(url,null,null).target("187").load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(2);migration.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            var after=new TreeMap<String,String>();try(var rows=sql.executeQuery("SELECT type||':'||name,sql FROM sqlite_master WHERE sql IS NOT NULL")){while(rows.next())after.put(rows.getString(1),rows.getString(2));}
            before.forEach((key,value)->assertThat(after.get(key)).as(key).isEqualTo(value));
            assertThat(after.get("trigger:trg_workflow_publication_owner")).contains("COMPLETED","RELEASED","latest_attempt_id");
            try(var rows=sql.executeQuery("SELECT name,root_path,created_at,updated_at FROM project WHERE id='retained'")){assertThat(rows.next()).isTrue();assertThat(List.of(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4))).containsExactly("原项目","/tmp/retained-reuse","original","original");}

            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(rows.next()).isFalse();}
            try(var rows=sql.executeQuery("PRAGMA integrity_check")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("ok");}
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
