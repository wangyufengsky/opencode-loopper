package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class WorkflowControlMigrationTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"149","150","151","152","153","154","155","156","157","158","159","160","161","162","163","164","165","166","167","168","169","170","171","172","173","174","175"})
    void upgradeKeepsLegacyRunsAndDoesNotGrantContinuousExecutionOrInventCheckpoints(String from) throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("control-upgrade.db")+"?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target(from).load().migrate();
        String hash="0".repeat(64);Map<String,Object> before;
        var tables=new ArrayList<>(List.of("workflow_template","workflow_template_revision","workflow_requirement","workflow_plan_revision",
                "workflow_node_run","workflow_plan_node","workflow_node_attempt","workflow_model_launch","workflow_node_delivery"));
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','原项目','/tmp/history','t','t')");
            sql.execute("INSERT INTO workflow_template(id,title,description,builtin,head_revision,layout_json,created_at,updated_at) VALUES('t','原模板','',0,1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_template_revision VALUES('t',1,'{}','"+hash+"','t')");
            sql.execute("INSERT INTO workflow_requirement(id,project_id,title,objective,state,head_revision,source_template_id,source_revision,layout_json,created_at,updated_at) VALUES('r','p','原需求','原目标','RUNNING',1,'t',1,'{}','t','t')");
            sql.execute("INSERT INTO workflow_plan_revision VALUES('r',1,'{}','"+hash+"','TEMPLATE',NULL,'t')");
            sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('n','r','work','{\"pauseAfter\":true,\"kind\":\"WORK\"}','"+hash+"','ACTIVE','t','t')");
            sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'work','n')");
            sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,role_snapshot_json,adapter_key,external_session_id,created_at,updated_at) VALUES('a','n',1,1,'RUNNING','{ \"original\" : true }','"+hash+"','{}','model.readonly.v1','original-session','t','t')");
            sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='a' WHERE id='n'");
            sql.execute("INSERT INTO workflow_model_launch(attempt_id,requirement_id,state,directory,model_json,creation_plan_json,prompt_json,prompt_sha256,created_at,updated_at) VALUES('a','r','RUNNING','/tmp/history','{}','{ \"original\" : true }','{ \"text\" : \"原提示\" }','"+hash+"','t','t')");
            if(from.equals("150")) {
                sql.execute("INSERT INTO workflow_run_control(requirement_id,plan_revision,mode,target_key,state,created_at,updated_at) VALUES('r',1,'SINGLE','work','PAUSED','t','t')");
                tables.add("workflow_run_control");
            }
            sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('old-node','r','old','{}','"+hash+"','FAILED','t','t')");
            sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'old','old-node')");
            // A completed legacy proof must survive the stop-table rebuild byte-for-byte.
            sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,role_snapshot_json,adapter_key,external_session_id,created_at,updated_at) VALUES('old','old-node',1,1,'RUNNING','{}','"+hash+"','{}','model.readonly.v1','old-session','t','t')");
            sql.execute("INSERT INTO workflow_node_delivery VALUES('old','{ \"summary\" : \"历史交付\", \"outputs\" : {} }','"+hash+"',NULL,'t')");
            sql.execute("INSERT INTO workflow_attempt_stop VALUES('old','SESSION_TERMINAL','old-session','{ \"evidence\" : \"原停止证明\" }','t')");
            sql.execute("UPDATE workflow_node_attempt SET state='FAILED' WHERE id='old'");
            tables.add("workflow_attempt_stop");
            legacyWriter(sql,hash,Integer.parseInt(from)>=166);
            tables.addAll(List.of("workflow_workspace","workflow_code_snapshot","workflow_code_manifest","workflow_writer_queue","workspace_lease"));
            if(Integer.parseInt(from)>=166)tables.addAll(List.of("workflow_test_baseline","workflow_test_scope"));
            if(Integer.parseInt(from)>=160){legacyDocument(sql,hash);tables.addAll(List.of("workflow_document","workflow_document_file"));}
            if(Integer.parseInt(from)>=169){legacyNative(sql,hash);tables.addAll(List.of("workflow_command_run","workflow_native_test_evidence","workflow_source_read","workflow_design_input_read"));}
            before=rows(sql,tables);
            before.keySet().removeIf(key->key.endsWith(":object_repository"));
        }
        var flyway=Flyway.configure().dataSource(url,null,null).load();flyway.migrate();flyway.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            var after=rows(sql,tables);
            for(String key:List.of("workflow_workspace:writer:object_repository","workflow_code_snapshot:code:object_repository",
                    "workspace_lease:/tmp/history:holder_writeback_id")) {
                assertThat(after).containsEntry(key,null);after.remove(key);
            }
            assertThat(after).isEqualTo(before);
            if(Integer.parseInt(from)>=166) {
                assertThatThrownBy(()->sql.execute("UPDATE workflow_test_baseline SET files_json='{}' WHERE attempt_id='writer'")).hasMessageContaining("immutable");
                assertThatThrownBy(()->sql.execute("DELETE FROM workflow_test_scope WHERE attempt_id='writer'")).hasMessageContaining("retained");
            }
            if(Integer.parseInt(from)>=169) {
                assertThatThrownBy(()->sql.execute("UPDATE workflow_native_test_evidence SET report_json='{}' WHERE attempt_id='native'")).hasMessageContaining("immutable");
                // The active V1 reviewer retains its original Session and can continue reading after migration.
                sql.execute("INSERT INTO workflow_source_read VALUES('review','code','SecondTest.java','"+hash+"',1,1,1,'continued V1 read','t2')");
                sql.execute("INSERT INTO workflow_design_input_read VALUES('review','test','"+hash+"',1,2,2,'t2')");
            }
            try(var result=sql.executeQuery("SELECT count(*) FROM workflow_finish_intent")){assertThat(result.getInt(1)).isZero();}
            try(var result=sql.executeQuery("SELECT count(*) FROM workflow_source_snapshot")){assertThat(result.getInt(1)).isZero();}
            for(String table:List.of("workflow_source_read","workflow_design_input_read","workflow_document","workflow_document_file","workflow_directory_preparation","workflow_directory_result","workflow_directory_apply","workflow_test_baseline","workflow_test_scope","workflow_native_test_evidence","workflow_document_read","workflow_document_input_read"))
                if(!tables.contains(table))try(var result=sql.executeQuery("SELECT count(*) FROM "+table)){assertThat(result.getInt(1)).isZero();}
            for(String kind:List.of("SESSION_ABSENT","CREATION_STOP_CONFIRMED","NO_SESSION_CREATED","NO_EXTERNAL_WORK","COMMAND_TERMINAL","COMMAND_NOT_LAUNCHED"))
                assertThatThrownBy(()->sql.execute("INSERT INTO workflow_attempt_stop VALUES('a','"+kind+"','original-session','{}','t')")).isInstanceOf(SQLException.class);
            for(String table:from.equals("150")?List.of("workflow_node_checkpoint","workflow_plan_candidate"):List.of("workflow_run_control","workflow_node_checkpoint","workflow_plan_candidate"))
                try(var result=sql.executeQuery("SELECT count(*) FROM "+table)){assertThat(result.getInt(1)).isZero();}
            if(Integer.parseInt(from)>=160){assertThatThrownBy(()->sql.execute("UPDATE workflow_document_file SET content='changed' WHERE attempt_id='doc'")).hasMessageContaining("immutable");assertThatThrownBy(()->sql.execute("DELETE FROM workflow_document WHERE attempt_id='doc'")).hasMessageContaining("immutable");}
            try(var result=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(result.next()).isFalse();}
            try(var result=sql.executeQuery("PRAGMA integrity_check")){assertThat(result.getString(1)).isEqualTo("ok");}
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    private void legacyNative(Statement sql,String hash)throws Exception {
        String definition="{\"kind\":\"SYSTEM\",\"moduleId\":\"system.source.test-run\",\"moduleVersion\":1}";
        sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('native-node','r','test','"+definition+"','"+hash+"','ACTIVE','t','t')");
        sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'test','native-node')");
        sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,adapter_key,created_at,updated_at) VALUES('native','native-node',1,1,'RUNNING','{}','"+hash+"','system.verify.command.v1','t','t')");
        sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='native' WHERE id='native-node'");
        sql.execute("INSERT INTO workflow_command_run(attempt_id,requirement_id,project_id,state,created_at,updated_at) VALUES('native','r','p','PREPARING','t','t')");
        sql.execute("UPDATE workflow_command_run SET request_json='{ \"id\":\"native\" }',request_sha256='"+hash+"',state='READY' WHERE attempt_id='native'");
        String registration="{ \"requestSha256\":\""+hash+"\",\"worker\":{\"pid\":123,\"startedAt\":\"t\"}}";
        sql.execute("UPDATE workflow_command_run SET registration_json='"+registration+"',state='RUNNING' WHERE attempt_id='native'");
        String result="{ \"requestSha256\":\""+hash+"\",\"worker\":{\"pid\":123,\"startedAt\":\"t\"},\"stopConfirmed\":true }";
        sql.execute("INSERT INTO workflow_native_test_evidence VALUES('native','"+hash+"','"+result+"','"+hash+"','{ \"version\":1,\"valid\":true,\"passed\":true,\"original\":\"历史测试报告\" }','"+hash+"','t')");
        sql.execute("UPDATE workflow_command_run SET result_json='"+result+"',result_sha256='"+hash+"' WHERE attempt_id='native'");
        sql.execute("INSERT INTO workflow_node_delivery VALUES('native','{ \"summary\":\"旧版原生测试\",\"outputs\":{} }','"+hash+"','PASS','t')");
        sql.execute("INSERT INTO workflow_attempt_stop VALUES('native','COMMAND_TERMINAL',NULL,'{\"requestSha256\":\""+hash+"\",\"resultSha256\":\""+hash+"\"}','t')");
        sql.execute("UPDATE workflow_node_attempt SET state='SUCCEEDED' WHERE id='native'");
        sql.execute("UPDATE workflow_command_run SET state='SUCCEEDED' WHERE attempt_id='native'");
        definition="{\"kind\":\"WORK\",\"moduleId\":\"source.test-review\",\"moduleVersion\":1}";
        sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('review-node','r','review','"+definition+"','"+hash+"','ACTIVE','t','t')");
        sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'review','review-node')");
        String inputs="{ \"values\":[{\"name\":\"code\",\"kind\":\"CODE\"},{\"name\":\"test\",\"kind\":\"JSON\"}]}";
        sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,role_snapshot_json,adapter_key,external_session_id,created_at,updated_at) VALUES('review','review-node',1,1,'RUNNING','"+inputs+"','"+hash+"','{\"original\":true}','model.readonly.v1','original-review-session','t','t')");
        sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='review' WHERE id='review-node'");
        sql.execute("INSERT INTO workflow_model_launch(attempt_id,requirement_id,state,directory,model_json,creation_plan_json,prompt_json,prompt_sha256,created_at,updated_at) VALUES('review','r','RUNNING','/tmp/history','{}','{}','{\"text\":\"原V1复核\"}','"+hash+"','t','t')");
        sql.execute("INSERT INTO workflow_source_read VALUES('review','code','OriginalTest.java','"+hash+"',1,1,1,'original V1 read','t')");
        sql.execute("INSERT INTO workflow_design_input_read VALUES('review','test','"+hash+"',0,1,2,'t')");
    }
    private void legacyDocument(Statement sql,String hash)throws Exception {
        sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('doc-node','r','document','{\"kind\":\"SYSTEM\",\"moduleId\":\"system.source.design-document\",\"moduleVersion\":1}','"+hash+"','ACTIVE','t','t')");
        sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'document','doc-node')");
        sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,adapter_key,created_at,updated_at) VALUES('doc','doc-node',1,1,'RUNNING','{}','"+hash+"','system.source.design-document.v1','t','t')");
        sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='doc' WHERE id='doc-node'");
        sql.execute("INSERT INTO workflow_document VALUES('doc','{ \"version\":1,\"type\":\"DESIGN_DOCUMENT\",\"files\":[{\"path\":\"summary.md\",\"sizeBytes\":7,\"sha256\":\""+hash+"\"}]}','"+hash+"','t')");
        sql.execute("INSERT INTO workflow_document_file VALUES('doc','summary.md',7,'"+hash+"','history')");
        sql.execute("INSERT INTO workflow_attempt_stop VALUES('doc','NO_EXTERNAL_WORK',NULL,'{}','t')");
    }
    private void legacyWriter(Statement sql,String hash,boolean testScope)throws Exception {
        String definition=testScope?"{\"kind\":\"WORK\",\"moduleId\":\"source.test-write\",\"moduleVersion\":1}":"{}";
        sql.execute("INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,created_at,updated_at) VALUES('writer-node','r','write','"+definition+"','"+hash+"','ACTIVE','t','t')");
        sql.execute("INSERT INTO workflow_plan_node VALUES('r',1,'write','writer-node')");
        sql.execute("INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,role_snapshot_json,adapter_key,created_at,updated_at) VALUES('writer','writer-node',1,1,'PREPARING','{}','"+hash+"','{}','model.write.v1','t','t')");
        sql.execute("UPDATE workflow_node_run SET attempt_count=1,latest_attempt_id='writer' WHERE id='writer-node'");
        sql.execute("INSERT INTO workflow_writer_queue VALUES('writer','r','p','/tmp/history','fingerprint',1,'ADMITTED','t','t',NULL,0)");
        sql.execute("INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,state,heartbeat_at,holder_workflow_attempt_id) VALUES('/tmp/history','fingerprint','DIRECT','HELD','t','writer')");
        sql.execute("INSERT INTO workflow_workspace(attempt_id,requirement_id,project_id,state,project_directory,canonical_root,root_fingerprint,source_branch,source_commit,base_tree,branch,checkpoint_ref,created_at,updated_at) VALUES('writer','r','p','PREPARING','/tmp/history','/tmp/history','fingerprint','main','"+"1".repeat(40)+"','"+"2".repeat(40)+"','loopper/workflow-writer','refs/loopper/checkpoints/r/writer','t','t')");
        if(testScope) {
            sql.execute("INSERT INTO workflow_model_launch(attempt_id,requirement_id,state,directory,model_json,created_at,updated_at) VALUES('writer','r','PREPARING','/tmp/history','{}','t','t')");
            sql.execute("UPDATE workflow_workspace SET state='READY' WHERE attempt_id='writer'");
            sql.execute("INSERT INTO workflow_test_baseline VALUES('writer','"+hash+"','{ \"original\" : \"原始测试基线\" }','"+hash+"','t')");
            sql.execute("UPDATE workflow_model_launch SET state='RUNNING' WHERE attempt_id='writer'");
        }
        sql.execute("UPDATE workflow_node_attempt SET state='RUNNING',external_session_id='writer-session' WHERE id='writer'");
        sql.execute("INSERT INTO workflow_attempt_stop VALUES('writer','SESSION_TERMINAL','writer-session','{ \"original\" : true }','t')");
        sql.execute("UPDATE workflow_workspace SET state='CAPTURING' WHERE attempt_id='writer'");
        sql.execute("UPDATE workflow_workspace SET checkpoint_commit='"+"3".repeat(40)+"',checkpoint_tree='"+"4".repeat(40)+"',stash_commit='"+"5".repeat(40)+"' WHERE attempt_id='writer'");
        sql.execute("UPDATE workflow_workspace SET state='FROZEN' WHERE attempt_id='writer'");
        if(testScope)sql.execute("INSERT INTO workflow_test_scope VALUES('writer','"+"4".repeat(40)+"','{ \"original\" : \"原始测试范围\" }','"+hash+"','{ \"version\":1,\"type\":\"SOURCE_TEST_SCOPE\",\"passed\":true,\"testsExecuted\":false }','"+hash+"',1,'t')");
        sql.execute("INSERT INTO workflow_code_snapshot(id,attempt_id,project_id,requirement_id,repository,root_fingerprint,project_prefix,base_tree,result_tree,inputs_sha256,created_at) VALUES('code','writer','p','r','/tmp/history','fingerprint','','"+"2".repeat(40)+"','"+"4".repeat(40)+"','"+hash+"','t')");
        sql.execute("INSERT INTO workflow_code_manifest VALUES('code','{ \"original\" : \"保留原字节\" }','"+hash+"','t')");
    }
    private Map<String,Object> rows(Statement sql,List<String> tables) throws Exception {
        var result=new LinkedHashMap<String,Object>();
        for(String table:tables)try(var rows=sql.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
            while(rows.next())for(int column=1;column<=rows.getMetaData().getColumnCount();column++)
                result.put(table+":"+rows.getString(1)+":"+rows.getMetaData().getColumnName(column),rows.getObject(column));
        }
        return result;
    }
}
