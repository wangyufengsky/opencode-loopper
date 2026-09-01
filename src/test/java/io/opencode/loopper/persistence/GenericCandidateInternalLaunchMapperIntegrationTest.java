package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h",
        "loopper.data-dir=target/generic-candidate-internal-launch-mapper-test"
})
class GenericCandidateInternalLaunchMapperIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoopperMapper mapper;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        insertReviewerOwner();
    }

    @Test
    void roundTripsFrozenLaunchAndSeparatesClaimFromCreateDispatchCheckpoint() {
        GenericCandidateInternalLaunchRow prepared = preparedLaunch();

        assertThat(mapper.insertGenericCandidateInternalLaunch(prepared)).isEqualTo(1);
        assertThat(mapper.findGenericCandidateInternalLaunch("generic-launch")).contains(prepared);
        assertThat(mapper.claimGenericCandidateInternalLaunchCreate(
                "generic-launch", 0, "PREPARED", "worker", "claim",
                "2099-01-01T00:00:00Z", 1, "2026-09-02T10:00:00Z", "claimed"))
                .isEqualTo(1);

        GenericCandidateInternalLaunchRow claimed = mapper
                .findGenericCandidateInternalLaunch("generic-launch").orElseThrow();
        assertThat(claimed.state()).isEqualTo("PREPARED");
        assertThat(claimed.createDispatchAttempted()).isFalse();
        assertThat(claimed.createFence()).isEqualTo(1);

        assertThat(mapper.markGenericCandidateInternalLaunchCreateDispatchStarted(
                "generic-launch", 1, "worker", "claim", 1,
                "2026-09-02T10:01:00Z", "dispatched")).isEqualTo(1);
        GenericCandidateInternalLaunchRow dispatched = mapper
                .findGenericCandidateInternalLaunch("generic-launch").orElseThrow();
        assertThat(dispatched.state()).isEqualTo("CREATING");
        assertThat(dispatched.createDispatchAttempted()).isTrue();
        assertThat(dispatched.createDispatchStartedAt()).isEqualTo("2026-09-02T10:01:00Z");
    }

    private GenericCandidateInternalLaunchRow preparedLaunch() {
        return new GenericCandidateInternalLaunchRow(
                "generic-launch", "generic-run", "REVIEWER_REPORT_V1",
                "designer", null, null, "ANALYSIS_REPORT", "report",
                "report", null, null, "REVIEWER_REPORT_V1", 7,
                "REVIEWER_REPORT_V1", 3, "PREPARED", 0, null, null,
                "Reviewer candidate candidate_launch_id=generic-launch", "/tmp/project",
                "generation-1", true, "loopper_internal_generic", "a".repeat(64),
                null, null, null, "REVIEWER_CANDIDATE_READ_ONLY", "[]",
                "b".repeat(64), "c".repeat(64), "R".repeat(43),
                "LOCAL_REQUEST_ATTESTED", null, null, null, 0, false, null,
                null, null, null, null, null, null, null,
                "created", "created", 0);
    }

    private void insertReviewerOwner() {
        jdbc.update("INSERT INTO project(id,name,root_path,created_at,updated_at) "
                + "VALUES('project','Project','/tmp/project','now','now')");
        jdbc.update("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('draft','project','Goal','{}','DRAFT_READY','now','now')");
        jdbc.update("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) "
                + "VALUES('designer','project','RUNNING','READ_ONLY','draft','now','now')");
        jdbc.update("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) "
                + "VALUES('message','designer',1,'ASSISTANT','requirement','PERSISTED','now')");
        jdbc.update("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,"
                + "requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) "
                + "VALUES('revision','designer',1,'message','requirement','[]',0,'ACTIVE','now','now')");
        jdbc.update("INSERT INTO designer_task_profile(id,designer_session_id,requirement_revision_id,state,intent,"
                + "workflow_template,mutation_mode,artifact_kinds_json,technologies_json,test_policy,"
                + "execution_strategy,role_pack_id,role_pack_version,confidence,evidence_json,resolution_source,"
                + "decision_required,created_at,updated_at) VALUES('profile','designer','revision','FROZEN',"
                + "'READ_ONLY_REVIEW','REVIEWER_REPORT','READ_ONLY','[]','[]','NOT_APPLICABLE','OPENCODE',"
                + "'reviewer','2026-08-dynamic-v7',90,'[]','USER_CONFIRMED',0,'now','now')");
        jdbc.update("INSERT INTO analysis_report(id,designer_session_id,task_profile_id,state,title,markdown,"
                + "evidence_json,source_requirement,source_requirement_revision,created_at,updated_at,version) "
                + "VALUES('report','designer','profile','RUNNING','Report','','[]','requirement',7,"
                + "'now','now',0)");
    }
}
