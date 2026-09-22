package io.opencode.loopper.persistence;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class KnowledgeMigrationTest {
    @TempDir Path root;
    @Test void upgradesCurrentReleaseWithoutConvertingDesignsAndKeepsExistingPolicies() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("upgrade.db");
        Flyway.configure().dataSource(url,null,null).target("113").load().migrate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','existing','/project','now','now')");
            sql.execute("INSERT INTO assist_tool_policy VALUES('','@loopper-assist','query_database_readonly',0,7,'now')");
        }
        var flyway = Flyway.configure().dataSource(url,null,null).load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(9); flyway.validate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement()) {
            try(var rows=sql.executeQuery("SELECT count(*) FROM knowledge_conversation")) { assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero(); }
            try(var rows=sql.executeQuery("SELECT name FROM project WHERE id='p'")) { assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("existing"); }
            try(var rows=sql.executeQuery("SELECT enabled,version FROM assist_tool_policy WHERE tool_name='query_database_readonly'")) { assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();assertThat(rows.getInt(2)).isEqualTo(7); }
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @Test void upgradesExistingKnowledgeAnswersWithEmptyThinkingWithoutRewritingHistory() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("knowledge-upgrade.db");
        Flyway.configure().dataSource(url,null,null).target("114").load().migrate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','existing','/project','now','now')");
            sql.execute("INSERT INTO knowledge_conversation(id,project_id,root_path,title,model_json,sources_json,connections_json,created_at,updated_at) VALUES('c','p','/project','原问题','{}','[]','[]','now','now')");
            sql.execute("INSERT INTO knowledge_turn(id,conversation_id,ordinal,idempotency_key,message_id,state,user_text,answer,created_at,updated_at) VALUES('t','c',1,'key','msg','COMPLETED','原问题','保留原答案','now','now')");
        }
        var flyway = Flyway.configure().dataSource(url,null,null).load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8); flyway.validate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement();var rows=sql.executeQuery("SELECT answer,thinking,state FROM knowledge_turn WHERE id='t'")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("保留原答案");
            assertThat(rows.getString(2)).isEmpty(); assertThat(rows.getString(3)).isEqualTo("COMPLETED");
        }
    }
    @Test void researchUpgradePreservesFrozenV2HistoryAndAddsEmptyContinuationStore() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("research-upgrade.db");
        Flyway.configure().dataSource(url,null,null).target("117").load().migrate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','existing','/project','now','now')");
            sql.execute("INSERT INTO knowledge_conversation(id,project_id,root_path,title,model_json,sources_json,connections_json,created_at,updated_at) VALUES('c','p','/project','原问题','{}','[]','[]','then','now')");
            sql.execute("INSERT INTO knowledge_conversation_options(conversation_id,contract_version,timezone,last_activity_at) VALUES('c',2,'Asia/Shanghai','now')");
            sql.execute("INSERT INTO knowledge_turn(id,conversation_id,ordinal,idempotency_key,message_id,state,user_text,answer,thinking,created_at,updated_at) VALUES('t','c',1,'key','msg','RUNNING','原问题','进行中答案','已采集思考','now','now')");
        }
        var flyway = Flyway.configure().dataSource(url,null,null).load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5); flyway.validate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement()) {
            try(var row=sql.executeQuery("SELECT contract_version FROM knowledge_conversation_options WHERE conversation_id='c'")) {
                assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(2);
            }
            try(var row=sql.executeQuery("SELECT state,answer,thinking,message_id FROM knowledge_turn WHERE id='t'")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("RUNNING");
                assertThat(row.getString(2)).isEqualTo("进行中答案"); assertThat(row.getString(3)).isEqualTo("已采集思考");
                assertThat(row.getString(4)).isEqualTo("msg");
            }
            try(var row=sql.executeQuery("SELECT count(*) FROM knowledge_research_round")) {
                assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isZero();
            }
        }
    }
    @Test void v2UpgradeFreezesLegacyContractAndPreservesCurrentAnswerAndRevokedGitPolicy() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("v2-upgrade.db");
        Flyway.configure().dataSource(url,null,null).target("116").load().migrate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','existing','/project','now','now')");
            sql.execute("INSERT INTO knowledge_conversation(id,project_id,root_path,title,model_json,sources_json,connections_json,created_at,updated_at) VALUES('c','p','/project','原问题','{}','[]','[]','then','now')");
            sql.execute("INSERT INTO knowledge_turn(id,conversation_id,ordinal,idempotency_key,message_id,state,user_text,answer,thinking,created_at,updated_at) VALUES('t','c',1,'key','msg','RUNNING','原问题','进行中答案','已采集思考','now','now')");
            sql.execute("INSERT INTO assist_tool_policy VALUES('','@loopper-assist','inspect_knowledge_git',0,4,'now')");
        }
        var flyway = Flyway.configure().dataSource(url,null,null).load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6); flyway.validate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement()) {
            try(var row=sql.executeQuery("SELECT contract_version,timezone,archived_at,last_activity_at FROM knowledge_conversation_options WHERE conversation_id='c'")) {
                assertThat(row.next()).isTrue();assertThat(row.getInt(1)).isEqualTo(1);assertThat(row.getString(2)).isEqualTo("UTC");
                assertThat(row.getString(3)).isNull();assertThat(row.getString(4)).isEqualTo("now");
            }
            try(var row=sql.executeQuery("SELECT state,answer,thinking FROM knowledge_turn WHERE id='t'")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("RUNNING");
                assertThat(row.getString(2)).isEqualTo("进行中答案");assertThat(row.getString(3)).isEqualTo("已采集思考");
            }
            try(var row=sql.executeQuery("SELECT enabled,version FROM assist_tool_policy WHERE tool_name='inspect_knowledge_git'")) {
                assertThat(row.next()).isTrue();assertThat(row.getInt(1)).isZero();assertThat(row.getInt(2)).isEqualTo(4);
            }
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

}
