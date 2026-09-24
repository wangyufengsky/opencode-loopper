package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PptAgentMigrationTest {
    @TempDir Path root;
    @Test void additiveUpgradePreservesKnowledgeAndEnforcesWriterQuestionOwnershipAndStopProof() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("118").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','existing','/existing','now','now')");
            sql.execute("INSERT INTO knowledge_conversation(id,project_id,root_path,title,model_json,sources_json,connections_json,created_at,updated_at) VALUES('c','p','/existing','原有问答','{}','[]','[]','now','now')");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).target("120").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2); flyway.validate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT title FROM knowledge_conversation WHERE id='c'")) { assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("原有问答"); }
            for (String id : new String[]{"d1", "d2"}) sql.execute("INSERT INTO ppt_document(id,title,model,phase,create_digest,created_at,updated_at) VALUES('" + id + "','PPT','fake/model','DESIGN','digest','now','now')");
            sql.execute(run("r1", "d1"));
            assertThatThrownBy(() -> sql.execute(run("r2", "d1"))).hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_agent_run SET state='COMPLETED' WHERE id='r1'")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql.execute("INSERT INTO ppt_agent_question(id,run_id,document_id,prompt,options_json,state,created_at) VALUES('q','r1','d2','受众？','[]','PENDING','now')")).hasMessageContaining("FOREIGN KEY");
            sql.execute("UPDATE ppt_agent_run SET state='STOPPED',stop_proof='ACKNOWLEDGED' WHERE id='r1'");
            sql.execute(run("r2", "d1"));
        }
    }
    @Test void activityUpgradePreservesExistingRunWithoutInventingProviderThinking() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("activity-upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("121").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO ppt_document(id,title,model,phase,create_digest,created_at,updated_at) VALUES('d','PPT','fake/model','DESIGN','digest','now','now')");
            sql.execute(run("r", "d"));
            sql.execute("UPDATE ppt_agent_run SET answer='已有回复' WHERE id='r'");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(18); flyway.validate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT answer,state FROM ppt_agent_run WHERE id='r'")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("已有回复"); assertThat(result.getString(2)).isEqualTo("PREPARED");
            }
            try (var result = sql.executeQuery("SELECT count(*) FROM ppt_agent_activity")) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isZero(); }
        }
    }
    private String run(String id, String doc) {
        return "INSERT INTO ppt_agent_run(id,document_id,idempotency_key,input_sha,user_text,scope_json,source_revision,phase,model_json,root_path,context_json,state,message_id,created_at,updated_at) VALUES('"
                + id + "','" + doc + "','" + id + "','" + "a".repeat(64) + "','制作','{}',0,'DESIGN','{}','/ppt','{}','PREPARED','msg_" + id + "','now','now')";
    }
}
