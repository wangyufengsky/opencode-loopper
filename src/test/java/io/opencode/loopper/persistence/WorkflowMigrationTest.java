package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowMigrationTest {
    @TempDir Path directory;
    @Test void newWorkflowTablesDoNotAlterPriorSchemaBusinessRowsOrInventHistory() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("141").load().migrate();
        Map<String, String> before;
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','历史项目','/tmp/existing','t','t')");
            sql.execute("INSERT INTO designer_session(id,project_id,state,access_mode,created_at,updated_at) VALUES('s','p','RUNNING','READ_ONLY','t','t')");
            sql.execute("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) VALUES('m','s',1,'USER','历史需求','PERSISTED','t')");
            before = schema(sql);
        }
        var migration = Flyway.configure().dataSource(url,null,null).load();
        migration.migrate(); migration.validate();
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            var after = schema(sql);
            before.forEach((key, value) -> assertThat(MigrationTestSchema.withoutWorkflowLeaseOwner(key,after.get(key))).as(key).isEqualTo(value));
            try (var rows=sql.executeQuery("SELECT content FROM designer_message WHERE id='m'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("历史需求");
            }
            for (String table : List.of("workflow_template", "workflow_template_revision", "workflow_requirement", "workflow_plan_revision", "workflow_command"))
                try (var rows=sql.executeQuery("SELECT count(*) FROM " + table)) { assertThat(rows.getInt(1)).isZero(); }
            try (var rows=sql.executeQuery("PRAGMA integrity_check")) { assertThat(rows.getString(1)).isEqualTo("ok"); }
            try (var rows=sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
    @Test void executionUpgradePreservesConfirmedPlanningHistoryWithoutInventingAttempts() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("planning-upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("142").load().migrate();
        String definition = new tools.jackson.databind.ObjectMapper().writeValueAsString(io.opencode.loopper.workflow.WorkflowFixtures.single());
        String sha = io.opencode.loopper.service.workflow.WorkflowEncoding.hash(definition);
        Map<String, String> before;
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','历史项目','/tmp/existing','t','t')");
            sql.execute("INSERT INTO workflow_template(id,title,description,builtin,head_revision,layout_json,created_at,updated_at) VALUES('t','历史流程','',0,1,'{}','t','t')");
            try (var insert=db.prepareStatement("INSERT INTO workflow_template_revision(template_id,revision,definition_json,sha256,created_at) VALUES('t',1,?,?,'t')")) {
                insert.setString(1,definition); insert.setString(2,sha); insert.executeUpdate();
            }
            sql.execute("INSERT INTO workflow_requirement(id,project_id,title,objective,state,head_revision,source_template_id,source_revision,layout_json,created_at,updated_at) "
                    + "VALUES('r','p','历史需求','固定目标','PENDING_START',1,'t',1,'{}','t','t')");
            try (var insert=db.prepareStatement("INSERT INTO workflow_plan_revision(requirement_id,revision,definition_json,sha256,source,created_at) VALUES('r',1,?,?,'TEMPLATE','t')")) {
                insert.setString(1,definition); insert.setString(2,sha); insert.executeUpdate();
            }
            before = schema(sql);
        }
        var migration = Flyway.configure().dataSource(url,null,null).load(); migration.migrate(); migration.validate();
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            var after = schema(sql); before.forEach((key,value) -> assertThat(MigrationTestSchema.withoutWorkflowLeaseOwner(key,after.get(key))).as(key).isEqualTo(value));
            try (var rows=sql.executeQuery("SELECT r.state,p.definition_json,p.sha256 FROM workflow_requirement r JOIN workflow_plan_revision p ON p.requirement_id=r.id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("PENDING_START");
                assertThat(rows.getString(2)).isEqualTo(definition); assertThat(rows.getString(3)).isEqualTo(sha);
            }
            for (String table : List.of("workflow_node_run", "workflow_plan_node", "workflow_node_attempt", "workflow_node_delivery", "workflow_attempt_stop", "workflow_input_snapshot"))
                try (var rows=sql.executeQuery("SELECT count(*) FROM " + table)) { assertThat(rows.getInt(1)).isZero(); }
            try (var rows=sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
    }
    @Test void modelUpgradeRetainsEveryExistingStopProofAndItsTerminalAttempt() throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("execution-upgrade.db")+"?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("143").load().migrate();
        String sha="0".repeat(64);
        var kinds=List.of("NO_EXTERNAL_WORK","SESSION_TERMINAL","ABORT_CONFIRMED","SESSION_ABSENT");
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','历史项目','/tmp/existing','t','t')");
            sql.execute("INSERT INTO workflow_template(id,title,description,builtin,head_revision,layout_json,created_at,updated_at) VALUES('t','历史流程','',0,1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_template_revision VALUES('t',1,'{}','"+sha+"','t')");
            sql.execute("INSERT INTO workflow_requirement(id,project_id,title,objective,state,head_revision,source_template_id,source_revision,layout_json,created_at,updated_at) VALUES('r','p','历史需求','目标','STALLED',1,'t',1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_plan_revision VALUES('r',1,'{}','"+sha+"','TEMPLATE',NULL,'t')");
            for (int i=0;i<kinds.size();i++) {
                String node="node"+i, attempt="attempt"+i, session=i==0?"NULL":"'session"+i+"'";
                sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('"+node+"','r','"+node+"','{}','"+sha+"','ACTIVE','t','t')");
                sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'"+node+"','"+node+"')");
                sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,adapter_key,external_session_id,created_at,updated_at) VALUES('"+attempt+"','"+node+"',1,1,'STOPPING','{}','"+sha+"','"+(i==0?"human.v1":"historical.remote.v1")+"',"+session+",'t','t')");
                sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='"+attempt+"' WHERE id='"+node+"'");
                sql.execute("INSERT INTO workflow_attempt_stop VALUES('"+attempt+"','"+kinds.get(i)+"',"+session+",'{ \"evidence\" : \"原始停止证明"+i+"\" }','original-time')");
                sql.execute("UPDATE workflow_node_attempt SET state='CANCELLED' WHERE id='"+attempt+"'");
                sql.execute("UPDATE workflow_node_run SET state='CANCELLED' WHERE id='"+node+"'");
            }
        }
        var migration=Flyway.configure().dataSource(url,null,null).load(); migration.migrate(); migration.validate();
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            try (var rows=sql.executeQuery("SELECT s.*,a.state FROM workflow_attempt_stop s JOIN workflow_node_attempt a ON a.id=s.attempt_id ORDER BY s.attempt_id")) {
                for (int i=0;i<kinds.size();i++) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString("attempt_id")).isEqualTo("attempt"+i);
                    assertThat(rows.getString("kind")).isEqualTo(kinds.get(i));
                    assertThat(rows.getString("external_session_id")).isEqualTo(i==0?null:"session"+i);
                    assertThat(rows.getString("evidence_json")).isEqualTo("{ \"evidence\" : \"原始停止证明"+i+"\" }");
                    assertThat(rows.getString("created_at")).isEqualTo("original-time");
                    assertThat(rows.getString("state")).isEqualTo("CANCELLED");
                }
                assertThat(rows.next()).isFalse();
            }
            try (var rows=sql.executeQuery("SELECT count(*) FROM workflow_model_launch")) { assertThat(rows.getInt(1)).isZero(); }
            assertThatThrownBy(()->sql.execute("UPDATE workflow_attempt_stop SET evidence_json='{}' WHERE attempt_id='attempt0'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(()->sql.execute("DELETE FROM workflow_attempt_stop WHERE attempt_id='attempt0'"))
                    .hasMessageContaining("retained");
            try (var rows=sql.executeQuery("PRAGMA integrity_check")) { assertThat(rows.getString(1)).isEqualTo("ok"); }
            try (var rows=sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
    @Test void codeSnapshotUpgradePreservesExistingWorkflowWriterWithoutInventingDeliveries() throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("code-upgrade.db")+"?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("145").load().migrate();
        String sha="0".repeat(64);
        Map<String,String> originalSchema;
        Map<String,Object> history=new LinkedHashMap<>();
        var tables=List.of("workflow_requirement","workflow_node_run","workflow_node_attempt","workflow_writer_queue","workspace_lease","workflow_attempt_stop","workflow_model_launch");
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','历史项目','/tmp/existing','t','t')");
            sql.execute("INSERT INTO workflow_template(id,title,description,builtin,head_revision,layout_json,created_at,updated_at) VALUES('t','历史流程','',0,1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_template_revision VALUES('t',1,'{}','"+sha+"','t')");
            sql.execute("INSERT INTO workflow_requirement(id,project_id,title,objective,state,head_revision,source_template_id,source_revision,layout_json,created_at,updated_at) VALUES('r','p','历史需求','目标','RUNNING',1,'t',1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_plan_revision VALUES('r',1,'{}','"+sha+"','TEMPLATE',NULL,'t')");
            sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('n','r','code','{}','"+sha+"','ACTIVE','t','t')");
            sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'code','n')");
            sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,role_snapshot_json,adapter_key,external_session_id,created_at,updated_at) VALUES('a','n',1,1,'STOPPING','{}','"+sha+"','{}','model.write.v1','s','t','t')");
            sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='a' WHERE id='n'");
            sql.execute("INSERT INTO workflow_writer_queue VALUES('a','r','p','/tmp/existing','original-fingerprint',9,'ADMITTED','queued','admitted',NULL,4)");
            sql.execute("INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,state,acquired_at,heartbeat_at,version,holder_workflow_attempt_id) VALUES('/tmp/existing','original-fingerprint','DIRECT','RELEASE_PENDING','acquired','heartbeat',7,'a')");
            sql.execute("INSERT INTO workflow_attempt_stop VALUES('a','ABORT_CONFIRMED','s','{ \"original\" : true }','proof-time')");
            sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('readonly','r','read','{}','"+sha+"','ACTIVE','t','t')");
            sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'read','readonly')");
            sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,role_snapshot_json,adapter_key,external_session_id,created_at,updated_at) VALUES('old-model','readonly',1,1,'RUNNING','{}','"+sha+"','{}','model.readonly.v1','old-session','t','t')");
            sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='old-model' WHERE id='readonly'");
            sql.execute("INSERT INTO workflow_model_launch(attempt_id,requirement_id,state,directory,model_json,creation_plan_json,prompt_json,prompt_sha256,created_at,updated_at) VALUES('old-model','r','RUNNING','/tmp/existing','{}','{ \"frozen\" : \"original plan\" }','{ \"text\" : \"原始提示\" }','"+sha+"','old-created','old-updated')");
            originalSchema=schema(sql); history=rows(sql,tables);
        }
        // First verify the exact code-snapshot/writer-launch migrations; later migrations intentionally extend some tables.
        var migration=Flyway.configure().dataSource(url,null,null).target("148").load(); migration.migrate(); migration.validate();
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            var after=schema(sql); originalSchema.forEach((key,value)->{
                if (key.equals("trigger:trg_workflow_launch_owner")) {
                    assertThat(value).contains("a.adapter_key='model.readonly.v1'");
                    assertThat(after.get(key)).contains("a.adapter_key='model.readonly.v1' OR", "a.adapter_key='model.write.v1'",
                            "workflow_writer_queue", "q.state IN ('QUEUED','ADMITTED')");
                } else assertThat(after.get(key)).as(key).isEqualTo(value);
            });
            assertThat(rows(sql,tables)).isEqualTo(history);
            for (String table:List.of("workflow_code_snapshot","workflow_code_manifest"))
                try(var rows=sql.executeQuery("SELECT count(*) FROM "+table)) { assertThat(rows.getInt(1)).isZero(); }
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
        var latest=Flyway.configure().dataSource(url,null,null).load();latest.migrate();latest.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            var upgraded=rows(sql,tables);assertThat(upgraded).containsEntry("workspace_lease:/tmp/existing:holder_writeback_id",null);
            upgraded.remove("workspace_lease:/tmp/existing:holder_writeback_id");assertThat(upgraded).isEqualTo(history);
            for(String table:List.of("workflow_code_snapshot","workflow_code_manifest","workflow_node_delivery","workflow_template_plan_source"))
                try(var rows=sql.executeQuery("SELECT count(*) FROM "+table)){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(rows.next()).isFalse();}
        }
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    private Map<String,Object> rows(Statement sql,List<String> tables) throws Exception {
        var result=new LinkedHashMap<String,Object>();
        for(String table:tables) try(var rows=sql.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
            while(rows.next()) for(int column=1;column<=rows.getMetaData().getColumnCount();column++)
                result.put(table+":"+rows.getString(1)+":"+rows.getMetaData().getColumnName(column),rows.getObject(column));
        }
        return result;
    }
    private Map<String, String> schema(Statement sql) throws Exception {
        var result = new LinkedHashMap<String, String>();
        try (var rows=sql.executeQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL")) {
            while(rows.next()) result.put(rows.getString(1) + ":" + rows.getString(2), rows.getString(3));
        }
        return result;
    }
}
