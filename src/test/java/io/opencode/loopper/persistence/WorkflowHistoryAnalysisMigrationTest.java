package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowHistoryAnalysisMigrationTest {
    @TempDir Path directory;
    @Test void upgradePreservesEveryPreviousTableTriggerAndFrozenPlan()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("upgrade.db")+"?foreign_keys=on";var before=new LinkedHashMap<String,String>();
        Flyway.configure().dataSource(url,null,null).target("178").load().migrate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO workflow_template(id,title,description,builtin,head_revision,layout_json,created_at,updated_at) VALUES('t','原流程','',0,1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_template_revision VALUES('t',1,'{ \"schemaVersion\" : 1 }','"+"a".repeat(64)+"','t')");
            try(var rows=sql.executeQuery("SELECT type||':'||name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'")){while(rows.next())before.put(rows.getString(1),rows.getString(2));}
        }
        var migration=Flyway.configure().dataSource(url,null,null).target("179").load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);migration.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            var after=new LinkedHashMap<String,String>();try(var rows=sql.executeQuery("SELECT type||':'||name,sql FROM sqlite_master WHERE sql IS NOT NULL")){while(rows.next())after.put(rows.getString(1),rows.getString(2));}
            before.forEach((key,value)->assertThat(after.get(key)).as(key).isEqualTo(value));
            try(var rows=sql.executeQuery("SELECT definition_json FROM workflow_template_revision WHERE template_id='t'")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("{ \"schemaVersion\" : 1 }");}
            try(var rows=sql.executeQuery("SELECT count(*) FROM workflow_history_analysis_input")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(rows.next()).isFalse();}
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
