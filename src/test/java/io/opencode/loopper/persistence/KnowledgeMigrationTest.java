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
        var flyway = Flyway.configure().dataSource(url,null,null).load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3); flyway.validate();
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
        var flyway = Flyway.configure().dataSource(url,null,null).load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2); flyway.validate();
        try (var connection=DriverManager.getConnection(url);var sql=connection.createStatement();var rows=sql.executeQuery("SELECT answer,thinking,state FROM knowledge_turn WHERE id='t'")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("保留原答案");
            assertThat(rows.getString(2)).isEmpty(); assertThat(rows.getString(3)).isEqualTo("COMPLETED");
        }
    }
}
