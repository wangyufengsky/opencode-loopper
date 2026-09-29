package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowWritebackMigrationTest {
    @TempDir Path directory;
    @Test void upgradeAddsAThirdOwnerWithoutRewritingExistingLeasesOrSchemaObjects()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("writeback.db")+"?foreign_keys=on";var before=new TreeMap<String,String>();
        Flyway.configure().dataSource(url,null,null).target("189").load().migrate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('retained','原项目','/tmp/retained-writeback','original','original')");
            sql.execute("INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,state,acquired_at,heartbeat_at,released_at,release_reason,version) VALUES('/tmp/retained-writeback','fingerprint','DIRECT','RELEASED','old','old','old','fixture',4)");
            try(var rows=sql.executeQuery("SELECT type||':'||name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'")){while(rows.next())before.put(rows.getString(1),rows.getString(2));}
        }
        var migration=Flyway.configure().dataSource(url,null,null).target("190").load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);migration.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            var after=new TreeMap<String,String>();try(var rows=sql.executeQuery("SELECT type||':'||name,sql FROM sqlite_master WHERE sql IS NOT NULL")){while(rows.next())after.put(rows.getString(1),rows.getString(2));}
            before.forEach((key,value)->assertThat(key.equals("table:workspace_lease")?after.get(key).replace(", holder_writeback_id TEXT REFERENCES workflow_writeback(id)",""):after.get(key)).as(key).isEqualTo(value));
            try(var rows=sql.executeQuery("SELECT state,version,holder_task_id,holder_workflow_attempt_id,holder_writeback_id,release_reason FROM workspace_lease")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("RELEASED");assertThat(rows.getInt(2)).isEqualTo(4);
                assertThat(rows.getString(3)).isNull();assertThat(rows.getString(4)).isNull();assertThat(rows.getString(5)).isNull();assertThat(rows.getString(6)).isEqualTo("fixture");
            }
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(rows.next()).isFalse();}
            try(var rows=sql.executeQuery("PRAGMA integrity_check")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("ok");}
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
