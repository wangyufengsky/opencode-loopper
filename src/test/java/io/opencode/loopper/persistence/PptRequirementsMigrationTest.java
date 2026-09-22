package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PptRequirementsMigrationTest {
    @TempDir Path root;

    @Test void upgradeKeepsHistoricalAnswersWithoutInventingRequirementsConsent() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("requirements-upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("122").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO ppt_document(id,title,model,phase,create_digest,created_at,updated_at) VALUES('d','PPT','fake/model','BRIEFING','digest','now','now')");
            sql.execute("INSERT INTO ppt_agent_run(id,document_id,idempotency_key,input_sha,user_text,scope_json,source_revision,phase,model_json,root_path,context_json,state,message_id,created_at,updated_at) "
                    + "VALUES('r','d','request','" + "a".repeat(64) + "','制作','{}',0,'BRIEFING','{}','/ppt','{}','PREPARED','msg_r','now','now')");
            sql.execute("INSERT INTO ppt_agent_question(id,run_id,document_id,prompt,options_json,state,answer,created_at) VALUES('q','r','d','采用商务风格？','[]','ANSWERED','同意','now')");
        }
        var migration = Flyway.configure().dataSource(url, null, null).target("123").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1); migration.validate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT answer,kind,confirmed FROM ppt_agent_question WHERE id='q'")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString("answer")).isEqualTo("同意");
                assertThat(result.getString("kind")).isEqualTo("CLARIFICATION"); assertThat(result.getObject("confirmed")).isNull();
            }
            try (var result = sql.executeQuery("SELECT context_json FROM ppt_agent_run WHERE id='r'")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("{}");
            }
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_agent_question SET confirmed=1 WHERE id='q'")).hasMessageContaining("CHECK");
            sql.execute("INSERT INTO ppt_agent_question(id,run_id,document_id,prompt,options_json,state,created_at,kind) VALUES('confirm','r','d','需求摘要','[]','PENDING','later','REQUIREMENTS_CONFIRMATION')");
            sql.execute("UPDATE ppt_agent_question SET state='ANSWERED',answer='确认',confirmed=1 WHERE id='confirm'");
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_agent_question SET confirmed=2 WHERE id='confirm'")).hasMessageContaining("CHECK");
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
