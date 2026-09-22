package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PptKnowledgeMigrationTest {
    @TempDir Path root;

    @Test void upgradePreservesQuestionsAndProjectAssociationsWithoutInventingKnowledgeGrants() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("122").load().migrate();
        try (var database = DriverManager.getConnection(url); var sql = database.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('project','原有项目','/project','now','now')");
            document(sql, "document", "project");
            run(sql, "run", "document");
            sql.execute("INSERT INTO ppt_agent_question(id,run_id,document_id,prompt,options_json,state,answer,reply_key,reply_sha,created_at) "
                    + "VALUES('question','run','document','受众？','[\"管理层\"]','ANSWERED','管理层','reply','hash','now')");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).target("124").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2); flyway.validate();
        try (var database = DriverManager.getConnection(url); var sql = database.createStatement()) {
            try (var rows = sql.executeQuery("SELECT project_id,title FROM ppt_document WHERE id='document'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("project_id")).isEqualTo("project");
                assertThat(rows.getString("title")).isEqualTo("原有汇报");
            }
            try (var rows = sql.executeQuery("SELECT plan_json,deck_json FROM ppt_revision WHERE document_id='document'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("plan_json")).isEqualTo("{\"brief\":\"原方案\"}");
                assertThat(rows.getString("deck_json")).isEqualTo("{\"slides\":[{\"notes\":\"原讲稿\"}]}");
            }
            try (var rows = sql.executeQuery("SELECT answer,state,kind,confirmed FROM ppt_agent_question WHERE id='question'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("answer")).isEqualTo("管理层");
                assertThat(rows.getString("state")).isEqualTo("ANSWERED");
                assertThat(rows.getString("kind")).isEqualTo("CLARIFICATION"); assertThat(rows.getObject("confirmed")).isNull();
            }
            assertEmpty(sql, "ppt_knowledge_scope"); assertEmpty(sql, "ppt_knowledge_evidence");
            assertForeignKeys(sql);
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test void freshSchemaKeepsEvidenceOwnedByOneDocumentAndRunAndDeduplicatesIdenticalCaptures() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("fresh.db") + "?foreign_keys=on";
        var flyway = Flyway.configure().dataSource(url, null, null).target("124").load();
        flyway.migrate(); flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("124");
        try (var database = DriverManager.getConnection(url); var sql = database.createStatement()) {
            assertEmpty(sql, "ppt_knowledge_scope"); assertEmpty(sql, "ppt_knowledge_evidence");
            document(sql, "a", null); document(sql, "b", null); run(sql, "run-a", "a"); run(sql, "run-b", "b");
            sql.execute("INSERT INTO ppt_knowledge_scope VALUES('a','project','授权项目','{\"sources\":[],\"connections\":[]}','[]','now')");
            assertThatThrownBy(() -> sql.execute("INSERT INTO ppt_knowledge_scope VALUES('a','different','其他项目','{}','[]','now')"))
                    .hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> sql.execute("INSERT INTO ppt_knowledge_scope VALUES('absent','project','不存在作品','{}','[]','now')"))
                    .hasMessageContaining("FOREIGN KEY");
            sql.execute(evidence("original", "a", "run-a", "message-a", "digest-a"));
            assertThatThrownBy(() -> sql.execute(evidence("wrong-document", "b", "run-a", "message-a", "digest-b")))
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql.execute(evidence("wrong-run", "a", "run-b", "message-b", "digest-b")))
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql.execute(evidence("duplicate", "a", "run-a", "message-a", "digest-a")))
                    .hasMessageContaining("UNIQUE");
            sql.execute(evidence("new-round", "a", "run-a", "next-message", "digest-a"));
            try (var rows = sql.executeQuery("SELECT count(*) FROM ppt_knowledge_evidence WHERE document_id='a'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2);
            }
            assertEmpty(sql, "task"); assertForeignKeys(sql);
        }
    }

    private void document(Statement sql, String id, String project) throws Exception {
        sql.execute("INSERT INTO ppt_document(id,title,project_id,model,phase,create_digest,created_at,updated_at) VALUES('"
                + id + "','原有汇报'," + (project == null ? "NULL" : "'" + project + "'")
                + ",'fake/model','BRIEFING','digest','now','now')");
        sql.execute("INSERT INTO ppt_revision VALUES('" + id + "',0,'{\"slides\":[{\"notes\":\"原讲稿\"}]}','{\"brief\":\"原方案\"}','创建','now')");
    }
    private void run(Statement sql, String id, String document) throws Exception {
        sql.execute("INSERT INTO ppt_agent_run(id,document_id,idempotency_key,input_sha,user_text,scope_json,source_revision,phase,model_json,root_path,context_json,state,message_id,created_at,updated_at) VALUES('"
                + id + "','" + document + "','" + id + "','" + "a".repeat(64)
                + "','原需求','{}',0,'BRIEFING','{}','/ppt','{}','RUNNING','msg_" + id + "','now','now')");
    }
    private String evidence(String id, String document, String run, String message, String digest) {
        return "INSERT INTO ppt_knowledge_evidence VALUES('" + id + "','" + document + "','" + run + "','" + message
                + "','code','{\"text\":\"已保存来源\"}','" + digest + "','now')";
    }
    private void assertEmpty(Statement sql, String table) throws Exception {
        try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
        }
    }
    private void assertForeignKeys(Statement sql) throws Exception {
        try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
    }
}
