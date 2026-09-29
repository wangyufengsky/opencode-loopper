package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowSnapshotDeliveryMigrationTest {
    @TempDir Path directory;
    @Test void upgradePreservesOriginalDocumentBytesOwnersAndUnrelatedGuards()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("reports.db")+"?foreign_keys=on",sha="a".repeat(64);
        Flyway.configure().dataSource(url,null,null).target("183").load().migrate();
        Map<String,String> schema=new LinkedHashMap<>();Map<String,List<List<String>>> before;
        var tables=List.of("workflow_node_delivery","workflow_document","workflow_document_file","workflow_node_run","workflow_node_attempt","workflow_requirement","workflow_plan_revision");
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','原项目','/tmp/old-document','t','t')");
            sql.execute("INSERT INTO workflow_template(id,title,description,builtin,head_revision,layout_json,created_at,updated_at) VALUES('t','原流程','',0,1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_template_revision VALUES('t',1,'{ \"schemaVersion\" : 1 }','"+sha+"','t')");
            sql.execute("INSERT INTO workflow_requirement(id,project_id,title,objective,state,head_revision,source_template_id,source_revision,layout_json,created_at,updated_at) VALUES('r','p','旧需求','目标','RUNNING',1,'t',1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_plan_revision VALUES('r',1,'{ \"schemaVersion\" : 1 }','"+sha+"','TEMPLATE',NULL,'t')");
            String definition="{\"kind\":\"SYSTEM\",\"moduleId\":\"system.source.design-document\",\"moduleVersion\":1}";
            sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('n','r','report','"+definition+"','"+sha+"','ACTIVE','t','t')");
            sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'report','n')");
            sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,adapter_key,created_at,updated_at) VALUES('a','n',1,1,'RUNNING','{}','"+sha+"','system.source.design-document.v1','t','t')");
            sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='a' WHERE id='n'");
            String manifest="{ \"version\":1, \"type\":\"DESIGN_DOCUMENT\", \"files\":[{\"path\":\"summary.md\",\"sizeBytes\":11,\"sha256\":\""+sha+"\"}]}";
            sql.execute("INSERT INTO workflow_document VALUES('a','"+manifest+"','"+sha+"','frozen-time')");
            sql.execute("INSERT INTO workflow_document_file VALUES('a','summary.md',11,'"+sha+"','old report\n')");
            sql.execute("INSERT INTO workflow_node_delivery VALUES('a','{ \"summary\": \"旧交付\" }','"+sha+"',NULL,'original-time')");
            before=rows(sql,tables);
            try(var rows=sql.executeQuery("SELECT type||':'||name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%workflow_node_delivery%' AND name NOT IN ('trg_workflow_delivery_size','trg_workflow_delivery_immutable','trg_workflow_delivery_retained','trg_workflow_attempt_terminal')")) {while(rows.next())schema.put(rows.getString(1),rows.getString(2));}
        }
        var migration=Flyway.configure().dataSource(url,null,null).target("184").load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);migration.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            assertThat(rows(sql,tables)).isEqualTo(before);
            var after=new HashMap<String,String>();try(var rows=sql.executeQuery("SELECT type||':'||name,sql FROM sqlite_master WHERE sql IS NOT NULL")){while(rows.next())after.put(rows.getString(1),rows.getString(2));}
            schema.forEach((key,value)->assertThat(after.get(key)).as(key).isEqualTo(value));
            assertThat(after.get("trigger:trg_workflow_delivery_size")).contains("snapshot.analyze","snapshot.review","307200","131072");
            assertThatThrownBy(()->sql.execute("UPDATE workflow_node_delivery SET content_json=content_json")).hasMessageContaining("immutable");
            capacity(sql,"ordinary","free.readonly","WORK","model.readonly.v1",1,131072,true);
            capacity(sql,"ordinary-large","free.readonly","WORK","model.readonly.v1",1,131073,false);
            capacity(sql,"design","source.design","WORK","model.readonly.v1",1,307200,true);
            capacity(sql,"design-large","source.design","WORK","model.readonly.v1",1,307201,false);
            capacity(sql,"snapshot","snapshot.analyze","WORK","model.readonly.v1",1,1500000,true);
            capacity(sql,"snapshot-too-large","snapshot.review","WORK","model.readonly.v1",1,1500001,false);
            capacity(sql,"version","snapshot.analyze","WORK","model.readonly.v1",2,307201,false);
            capacity(sql,"kind","snapshot.analyze","SYSTEM","model.readonly.v1",1,307201,false);
            capacity(sql,"adapter","snapshot.analyze","WORK","human.v1",1,307201,false);
            assertThatThrownBy(()->sql.execute("UPDATE workflow_document_file SET content=content")).hasMessageContaining("immutable");
            assertThatThrownBy(()->sql.execute("DELETE FROM workflow_document")).hasMessageContaining("immutable");
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(rows.next()).isFalse();}
            try(var rows=sql.executeQuery("PRAGMA integrity_check")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("ok");}
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
    private void capacity(Statement sql,String id,String module,String kind,String adapter,int version,int bytes,boolean allowed)throws Exception {
        String sha="a".repeat(64),definition="{\"kind\":\""+kind+"\",\"moduleId\":\""+module+"\",\"moduleVersion\":"+version+"}";
        sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('"+id+"','r','"+id+"','"+definition+"','"+sha+"','ACTIVE','t','t')");
        sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'"+id+"','"+id+"')");
        sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,adapter_key,created_at,updated_at) VALUES('"+id+"','"+id+"',1,1,'RUNNING','{}','"+sha+"','"+adapter+"','t','t')");
        String body="{\"text\":\""+"x".repeat(bytes-11)+"\"}";
        assertThat(body.length()).isEqualTo(bytes);
        try(var insert=sql.getConnection().prepareStatement("INSERT INTO workflow_node_delivery VALUES(?,?,?,NULL,'t')")) {
            insert.setString(1,id);insert.setString(2,body);insert.setString(3,sha);
            if(allowed)assertThat(insert.executeUpdate()).isEqualTo(1);else assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
        }
    }
    private Map<String,List<List<String>>> rows(Statement sql,List<String> tables)throws Exception {
        var result=new LinkedHashMap<String,List<List<String>>>();
        for(String table:tables)try(var rows=sql.executeQuery("SELECT * FROM "+table)) {
            var values=new ArrayList<List<String>>();while(rows.next()){var row=new ArrayList<String>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.add(rows.getString(i));values.add(row);}result.put(table,values);
        }
        return result;
    }
}
