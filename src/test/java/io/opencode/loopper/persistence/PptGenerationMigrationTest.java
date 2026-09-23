package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PptGenerationMigrationTest {
    @TempDir Path root;

    @Test void upgradePreservesEditableWorksAndEnforcesGenerationOwnership() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("120").load().migrate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            document(sql, "a"); document(sql, "b");
            sql.execute("INSERT INTO ppt_agent_run(id,document_id,idempotency_key,input_sha,user_text,scope_json,source_revision,phase,model_json,root_path,context_json,state,message_id,created_at,updated_at) "
                    + "VALUES('run-b','b','run-key','" + "a".repeat(64) + "','制作','{}',0,'BRIEFING','{}','/ppt','{}','PREPARED','msg-b','now','now')");
            sql.execute("INSERT INTO ppt_job(id,document_id,kind,revision,state,total,request_key,digest,created_at,updated_at) "
                    + "VALUES('job-b','b','PREVIEW',0,'PREPARED',1,'preview','hash','now','now')");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
        flyway.validate();
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            try (var rows = sql.executeQuery("SELECT deck_json,plan_json FROM ppt_revision WHERE document_id='a'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("{\"slides\":[{\"notes\":\"原始讲稿\"}]}");
                assertThat(rows.getString(2)).isEqualTo("{\"brief\":\"原始方案\"}");
            }
            sql.execute(generation("g-a", "a"));
            try (var rows = sql.executeQuery("SELECT requirements_confirmed FROM ppt_generation WHERE id='g-a'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            sql.execute("UPDATE ppt_generation SET requirements_confirmed=1 WHERE id='g-a'");
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_generation SET requirements_confirmed=2 WHERE id='g-a'")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql.execute(generation("g-overlap", "a"))).hasMessageContaining("UNIQUE");
            sql.execute("UPDATE ppt_generation SET state='WAITING_INPUT' WHERE id='g-a'");
            assertThatThrownBy(() -> sql.execute(generation("g-wait", "a"))).hasMessageContaining("UNIQUE");
            sql.execute("UPDATE ppt_generation SET state='STOPPING' WHERE id='g-a'");
            assertThatThrownBy(() -> sql.execute(generation("g-stop", "a"))).hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_generation SET run_id='run-b' WHERE id='g-a'"))
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_generation SET job_id='job-b' WHERE id='g-a'"))
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_generation SET preview_job_id='job-b' WHERE id='g-a'"))
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_generation SET output_revision=99 WHERE id='g-a'"))
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql.execute(request("b", "key", "g-a"))).hasMessageContaining("FOREIGN KEY");
            sql.execute(request("a", "key", "g-a"));
            assertThatThrownBy(() -> sql.execute(request("a", "key", "g-a"))).hasMessageContaining("UNIQUE");
            sql.execute("UPDATE ppt_generation SET state='STOPPED' WHERE id='g-a'");
            sql.execute(generation("g-next", "a"));
            assertThatThrownBy(() -> sql.execute("UPDATE ppt_generation SET mode='MANUAL' WHERE id='g-next'"))
                    .hasMessageContaining("CHECK");
            try (var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
            try (var rows = sql.executeQuery("SELECT count(*) FROM task")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test void freshDatabaseHasNoImplicitGenerationGrants() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("fresh.db") + "?foreign_keys=on";
        var flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate(); flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("126");
        try (var db = DriverManager.getConnection(url); var sql = db.createStatement()) {
            for (String table : new String[]{"ppt_generation", "ppt_generation_request"}) {
                try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    private void document(Statement sql, String id) throws Exception {
        sql.execute("INSERT INTO ppt_document(id,title,model,phase,create_digest,created_at,updated_at) "
                + "VALUES('" + id + "','原始作品','fake/model','BRIEFING','hash','now','now')");
        sql.execute("INSERT INTO ppt_revision VALUES('" + id
                + "',0,'{\"slides\":[{\"notes\":\"原始讲稿\"}]}','{\"brief\":\"原始方案\"}','创建','now')");
    }

    private String generation(String id, String document) {
        return "INSERT INTO ppt_generation(id,document_id,idempotency_key,input_sha,prompt,mode,scope_json,source_revision,dispatch_revision,state,step,agent_key,created_at,updated_at) VALUES('"
                + id + "','" + document + "','" + id + "','" + "a".repeat(64)
                + "','生成演示','CREATE','{\"kind\":\"DOCUMENT\"}',0,0,'PLANNING','PLANNING','agent_" + id + "','now','now')";
    }

    private String request(String document, String key, String generation) {
        return "INSERT INTO ppt_generation_request VALUES('" + document + "','" + key + "','"
                + "a".repeat(64) + "','" + generation + "','GENERATE','now')";
    }

    @Test void recoveryUpgradeLeavesLegacyAuthorizationOptOutAndPreservesFrozenPrompt() throws Exception {
        String url="jdbc:sqlite:"+root.resolve("recovery-upgrade.db")+"?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("125").load().migrate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            document(sql,"legacy");sql.execute(generation("old-generation","legacy"));
        }
        var upgrade=Flyway.configure().dataSource(url,null,null).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);upgrade.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()) {
            try(var rows=sql.executeQuery("SELECT prompt,state FROM ppt_generation WHERE id='old-generation'")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("生成演示");assertThat(rows.getString(2)).isEqualTo("PLANNING");
            }
            try(var rows=sql.executeQuery("SELECT count(*) FROM ppt_generation_recovery")) {assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
            assertThatThrownBy(()->sql.execute("INSERT INTO ppt_generation_recovery(generation_id) VALUES('missing')")).hasMessageContaining("FOREIGN KEY");
            sql.execute("INSERT INTO ppt_generation_recovery(generation_id) VALUES('old-generation')");
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")) {assertThat(rows.next()).isFalse();}
        }
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
