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
    void migratesEmptyV21AndV24DatabasesToLatestWithoutInventingAuditHistory() throws Exception {
        assertMigratesToLatest(temporaryDirectory.resolve("empty.db"), null);
        assertMigratesToLatest(temporaryDirectory.resolve("upgrade-v21.db"), "21");
        assertMigratesToLatest(temporaryDirectory.resolve("upgrade-v24.db"), "24");
    }

    @Test
    void v22ActiveStructuredRowsRemainReadableWithIndependentRepairBudgetsAfterV24Upgrade() throws Exception {
        Path database = temporaryDirectory.resolve("v22-active.db");
        String url = "jdbc:sqlite:" + database;
        Flyway.configure().dataSource(url, null, null).target(MigrationVersion.fromVersion("22")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','P','/tmp/p','now','now')");
            statement.executeUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) VALUES('d','p','G','{}','DRAFT_READY','now','now')");
            statement.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) VALUES('s','p','RUNNING','READ_ONLY','d','now','now')");
            statement.executeUpdate("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) VALUES('m','s',1,'ASSISTANT','design','PERSISTED','now')");
            statement.executeUpdate("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) VALUES('r','s',1,'m','requirement','[]',0,'ACTIVE','now','now')");
            statement.executeUpdate("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,source_draft_version,created_at,updated_at) VALUES('dec','s','r','RUNNING',0,'now','now')");
            statement.executeUpdate("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,source_design_message_id,source_draft_version,created_at,updated_at) VALUES('cmp','s',1,'RUNNING','m',0,'now','now')");
        }

        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            for (String query : List.of(
                    "SELECT workflow_step,planning_json,planning_repair_count FROM task_decomposition WHERE id='dec'",
                    "SELECT workflow_step,planning_json,planning_repair_count FROM loop_spec_compilation WHERE id='cmp'")) {
                try (var result = statement.executeQuery(query)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("workflow_step")).isEqualTo("FINAL_JSON");
                    assertThat(result.getString("planning_json")).isNull();
                    assertThat(result.getInt("planning_repair_count")).isZero();
                }
            }
        }
    }

    private void assertMigratesToLatest(Path database, String startingVersion) throws Exception {
        String url = "jdbc:sqlite:" + database;
        if (startingVersion != null) {
            Flyway.configure().dataSource(url, null, null).target(MigrationVersion.fromVersion(startingVersion)).load().migrate();
            try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('legacy-project','Legacy','/tmp/legacy','now','now')");
                statement.executeUpdate("INSERT INTO task(id,project_id,title,state,branch_name,created_at,updated_at) VALUES('legacy-task','legacy-project','Legacy merged elsewhere','SUCCEEDED','loopper/legacy','now','now')");
            }
        }
        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("25");
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table'")) {
            try (var result = statement.executeQuery()) {
                var names = new java.util.ArrayList<String>();
                while (result.next()) names.add(result.getString(1));
                assertThat(names).containsAll(List.of(
                        "workspace_lease", "task_queue", "interaction", "task_lineage",
                        "session_checkpoint", "session_usage", "binary_artifact",
                        "loopspec_template", "loopspec_template_version", "automation_rule", "automation_run",
                        "state_transition_event", "local_sync_conflict_session", "local_sync_conflict_file",
                        "verifier_runtime", "task_publication", "loop_spec_compilation", "stage_java_baseline",
                        "stage_workspace_baseline",
                        "design_requirement_revision", "task_decomposition", "design_work_package"));
            }
        }
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            if (startingVersion != null) {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM task_publication WHERE task_id='legacy-task'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM state_transition_event")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            try (var result = statement.executeQuery("PRAGMA table_info(automation_run)")) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) columns.add(result.getString("name"));
                assertThat(columns).contains("version");
            }
            try (var result = statement.executeQuery("PRAGMA table_info(task)")) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) columns.add(result.getString("name"));
                assertThat(columns).contains("source_branch");
            }
            try (var result = statement.executeQuery("PRAGMA table_info(designer_session)")) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) columns.add(result.getString("name"));
                assertThat(columns).contains("workflow_phase", "design_revision", "redesign_count",
                        "current_requirement_revision", "active_work_package_id");
            }
            try (var result = statement.executeQuery("PRAGMA table_info(designer_message)")) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) columns.add(result.getString("name"));
                assertThat(columns).contains("actor", "requirement_revision", "work_package_id");
            }
            try (var result = statement.executeQuery("PRAGMA table_info(stage)")) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) columns.add(result.getString("name"));
                assertThat(columns).contains("work_package_id");
            }
            for (String table : List.of("task_decomposition", "loop_spec_compilation")) {
                try (var result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
                    var columns = new java.util.ArrayList<String>();
                    while (result.next()) columns.add(result.getString("name"));
                    assertThat(columns).contains("workflow_step", "planning_json", "planning_repair_count");
                }
            }
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('baseline-project','Baseline','/tmp/baseline','now','now')");
            statement.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('baseline-task','baseline-project','Baseline','READY','now','now')");
            statement.executeUpdate("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at) VALUES('baseline-stage','baseline-task',0,'baseline','[]','[]','[]','[]','PENDING','now','now')");
            statement.executeUpdate("INSERT INTO stage_workspace_baseline(stage_id,task_id,baseline_ref,created_at) VALUES('baseline-stage','baseline-task','stage:baseline-task:baseline-stage:0123456789012345678901234567890123456789','now')");
            statement.executeUpdate("DELETE FROM stage WHERE id='baseline-stage'");
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM stage_workspace_baseline WHERE stage_id='baseline-stage'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
        assertAutomationApprovalAndImmutabilityGuards(url);
    }

    private void assertAutomationApprovalAndImmutabilityGuards(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','P','/tmp/p','now','now')");
            statement.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('task-scope','p','T','READY','now','now')");
            statement.executeUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('task-other','p','Other','READY','now','now')");
            statement.executeUpdate("INSERT INTO task_lineage(child_task_id,parent_task_id,recovery_mode,workspace_fingerprint,created_at) VALUES('task-other','task-scope','REWORK_ALL_STAGES','baseline','now')");
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
