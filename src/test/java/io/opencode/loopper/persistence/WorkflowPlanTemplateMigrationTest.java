package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowPlanTemplateMigrationTest {
    @TempDir Path directory;
    @Test void upgradeRetainsDefinitionsAndOnlyAcceptsImmutableMatchingOrigins()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("upgrade.db")+"?foreign_keys=on",sha="a".repeat(64);
        Flyway.configure().dataSource(url,null,null).target("176").load().migrate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','项目','/tmp/source-export','t','t')");
            sql.execute("INSERT INTO workflow_template(id,title,description,builtin,head_revision,layout_json,created_at,updated_at) VALUES('t','流程','',0,1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_template_revision VALUES('t',1,'{ \"schemaVersion\" : 1 }','"+sha+"','t')");
            sql.execute("INSERT INTO workflow_requirement(id,project_id,title,objective,state,head_revision,source_template_id,source_revision,layout_json,created_at,updated_at) VALUES('r','p','需求','目标','PLANNING',1,'t',1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_plan_revision VALUES('r',1,'{ \"schemaVersion\" : 1 }','"+sha+"','TEMPLATE',NULL,'t')");
        }
        var migration=Flyway.configure().dataSource(url,null,null).load();migration.migrate();migration.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            try(var row=sql.executeQuery("SELECT definition_json FROM workflow_plan_revision")){assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("{ \"schemaVersion\" : 1 }");}
            try(var row=sql.executeQuery("SELECT count(*) FROM workflow_template_plan_source")){assertThat(row.next()).isTrue();assertThat(row.getInt(1)).isZero();}
            assertThatThrownBy(()->sql.execute("INSERT INTO workflow_template_plan_source VALUES('t','r',1,'"+"b".repeat(64)+"','"+sha+"','CURRENT','t')")).hasMessageContaining("source mismatch");
            sql.execute("INSERT INTO workflow_template_plan_source VALUES('t','r',1,'"+sha+"','"+sha+"','CURRENT','t')");
            assertThatThrownBy(()->sql.execute("UPDATE workflow_template_plan_source SET mode='INITIAL'")).hasMessageContaining("immutable");
            assertThatThrownBy(()->sql.execute("DELETE FROM workflow_template_plan_source")).hasMessageContaining("retained");
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(rows.next()).isFalse();}
        }
    }
}
