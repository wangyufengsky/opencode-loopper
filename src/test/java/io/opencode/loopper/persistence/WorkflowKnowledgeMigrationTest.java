package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WorkflowKnowledgeMigrationTest {
    @TempDir Path temp;
    @Test void upgradeOnlyEnablesUntouchedDefaultsAndNeverBackfillsHistoricalOwners() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("upgrade.db");
        Flyway.configure().dataSource(url,null,null).target("139").load().migrate();
        try (var c=DriverManager.getConnection(url); var s=c.createStatement()) {
            s.execute("INSERT INTO assist_tool_policy(scope,server_id,tool_name,enabled,version,updated_at) VALUES('','@loopper-assist','search_project_knowledge',0,0,'t')");
            s.execute("INSERT INTO assist_tool_policy(scope,server_id,tool_name,enabled,version,updated_at) VALUES('','@loopper-assist','list_test_failures',0,1,'t')");
            s.execute("INSERT INTO assist_tool_policy(scope,server_id,tool_name,enabled,version,updated_at) VALUES('','@loopper-assist','read_test_failure',0,0,'t')");
            s.execute("INSERT INTO assist_tool_policy(scope,server_id,tool_name,enabled,version,updated_at) VALUES('project','@loopper-assist','search_project_knowledge',0,0,'t')");
            s.execute("INSERT INTO assist_policy_audit VALUES('explicit','','@loopper-assist','read_test_failure','DISABLE','t')");
        }
        Flyway.configure().dataSource(url,null,null).load().migrate();
        try (var c=DriverManager.getConnection(url); var s=c.createStatement()) {
            try (var r=s.executeQuery("SELECT sum(enabled),sum(version) FROM assist_tool_policy WHERE tool_name IN ('search_project_knowledge','list_test_failures','read_test_failure')")) {
                assertThat(r.getInt(1)).isEqualTo(1); assertThat(r.getInt(2)).isEqualTo(2);
            }
            try (var r=s.executeQuery("SELECT count(*) FROM assist_policy_audit WHERE action='MIGRATION_DEFAULT_ENABLE'")) { assertThat(r.getInt(1)).isEqualTo(1); }
            try (var r=s.executeQuery("SELECT count(*) FROM workflow_knowledge_binding")) { assertThat(r.getInt(1)).isZero(); }
            s.execute("INSERT INTO workflow_knowledge_binding VALUES('TASK','new','project','[]','t')");
            assertThatThrownBy(()->s.execute("UPDATE workflow_knowledge_binding SET sources_json='[1]'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(()->s.execute("DELETE FROM workflow_knowledge_binding"))
                    .hasMessageContaining("immutable");
            try (var r=s.executeQuery("PRAGMA integrity_check")) { assertThat(r.getString(1)).isEqualTo("ok"); }
        }
    }
}
