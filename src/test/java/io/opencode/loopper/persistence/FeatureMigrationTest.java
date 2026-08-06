package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeatureMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void migratesBothEmptyAndV11DatabasesToV14() throws Exception {
        assertMigratesToV14(temporaryDirectory.resolve("empty.db"), false);
        assertMigratesToV14(temporaryDirectory.resolve("upgrade.db"), true);
    }

    private void assertMigratesToV14(Path database, boolean stopAtV11) throws Exception {
        String url = "jdbc:sqlite:" + database;
        if (stopAtV11) {
            Flyway.configure().dataSource(url, null, null).target(MigrationVersion.fromVersion("11")).load().migrate();
        }
        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table'")) {
            try (var result = statement.executeQuery()) {
                var names = new java.util.ArrayList<String>();
                while (result.next()) names.add(result.getString(1));
                assertThat(names).containsAll(List.of(
                        "workspace_lease", "task_queue", "interaction", "task_lineage",
                        "session_checkpoint", "session_usage", "binary_artifact",
                        "loopspec_template", "loopspec_template_version", "automation_rule", "automation_run"));
            }
        }
        assertAutomationApprovalAndImmutabilityGuards(url);
    }

    private void assertAutomationApprovalAndImmutabilityGuards(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','P','/tmp/p','now','now')");
            statement.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('task-scope','p','T','READY','now','now')");
            statement.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('task-other','p','Other','READY','now','now')");
            statement.executeUpdate("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at) VALUES('stage','task-scope',0,'S','[]','[]','[]','[]','SUCCEEDED','now','now')");
            statement.executeUpdate("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at) VALUES('attempt','task-scope','stage',1,'SUCCEEDED','now')");
            statement.executeUpdate("INSERT INTO execution_session(id,task_id,stage_id,attempt_id,state,created_at) VALUES('session','task-scope','stage','attempt','COMPLETED','now')");
            statement.executeUpdate("INSERT INTO judge_run(id,task_id,attempt_id,role,ordinal,state,created_at) VALUES('judge','task-scope','attempt','REQUIREMENT',1,'COMPLETED','now')");
            statement.executeUpdate("INSERT INTO session_usage(id,task_id,execution_session_id,external_message_id,idempotency_key,reliable,observed_at) VALUES('usage-session','task-scope','session','m1','session:m1',0,'now')");
            statement.executeUpdate("INSERT INTO session_usage(id,task_id,judge_run_id,external_message_id,idempotency_key,reliable,observed_at) VALUES('usage-judge','task-scope','judge','m2','judge:m2',1,'now')");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO session_usage(id,task_id,execution_session_id,judge_run_id,external_message_id,idempotency_key,reliable,observed_at) VALUES('usage-both','task-scope','session','judge','m3','both:m3',1,'now')"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO session_usage(id,task_id,external_message_id,idempotency_key,reliable,observed_at) VALUES('usage-neither','task-scope','m4','neither:m4',0,'now')"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO session_usage(id,task_id,execution_session_id,external_message_id,idempotency_key,reliable,observed_at) VALUES('usage-cross-task','task-other','session','m5','cross:m5',1,'now')"))
                    .hasMessageContaining("session usage must reference a session from the same task");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE session_usage SET external_message_id='rewritten' WHERE id='usage-session'"))
                    .hasMessageContaining("session usage identity is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO interaction(id,scope_type,scope_id,task_id,external_session_id,external_request_id,kind,state,payload_json,created_at,updated_at) VALUES('bad-scope','TASK','different-task','task-scope','remote','request','QUESTION','PENDING','{}','now','now')"))
                    .hasMessageContaining("CHECK constraint failed");
            statement.executeUpdate("INSERT INTO loopspec_template(id,name,description,state,created_at,updated_at) VALUES('tpl','T','','ACTIVE','now','now')");
            statement.executeUpdate("INSERT INTO loopspec_template_version(id,template_id,version_number,spec_json,spec_sha256,auto_start_approved,created_at) VALUES('v-unapproved','tpl',1,'{}','sha-1',0,'now')");
            statement.executeUpdate("INSERT INTO automation_rule(id,name,project_id,template_version_id,trigger_type,state,approval_mode,trigger_config_json,created_at,updated_at) VALUES('review','R','p','v-unapproved','MANUAL','DISABLED','REVIEW_REQUIRED','{}','now','now')");

            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO automation_rule(id,name,project_id,template_version_id,trigger_type,state,approval_mode,trigger_config_json,created_at,updated_at) VALUES('unsafe','U','p','v-unapproved','MANUAL','ENABLED','AUTO_START','{}','now','now')"))
                    .hasMessageContaining("AUTO_START requires an approved immutable template version");

            statement.executeUpdate("INSERT INTO loopspec_template_version(id,template_id,version_number,spec_json,spec_sha256,auto_start_approved,created_at) VALUES('v-approved','tpl',2,'{}','sha-2',1,'now')");
            statement.executeUpdate("INSERT INTO automation_rule(id,name,project_id,template_version_id,trigger_type,state,approval_mode,trigger_config_json,created_at,updated_at) VALUES('safe','S','p','v-approved','MANUAL','ENABLED','AUTO_START','{}','now','now')");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE loopspec_template_version SET spec_json='{\"changed\":true}' WHERE id='v-approved'"))
                    .hasMessageContaining("template versions are immutable");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE automation_rule SET template_version_id='v-unapproved' WHERE id='safe'"))
                    .hasMessageContaining("AUTO_START requires an approved immutable template version");
        }
    }
}
