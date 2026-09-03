-- MCP candidates may be corrected without a submission-count ceiling. The
-- immutable max_attempts value remains the legacy repair budget / identity
-- metadata; owner, source, launch, runtime, terminal and idempotency guards stay.
-- As in V59, disable FK rewriting outside Flyway's transaction. The explicit
-- transaction makes the table copy and every guard restoration atomic.
PRAGMA foreign_keys=OFF;
PRAGMA legacy_alter_table=ON;
SAVEPOINT candidate_submission_v69;

ALTER TABLE ai_candidate_submission_run RENAME TO ai_candidate_submission_run_v68;

CREATE TABLE ai_candidate_submission_run (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT REFERENCES designer_session(id) ON DELETE CASCADE,
    task_id TEXT REFERENCES task(id) ON DELETE CASCADE,
    project_id TEXT REFERENCES project(id) ON DELETE CASCADE,
    owner_type TEXT NOT NULL CHECK (owner_type IN (
        'TASK_DECOMPOSITION','LOOP_SPEC_COMPILATION','DESIGN_WORK_PACKAGE',
        'TASK_PACKAGE_PLAN_REVISION','ANALYSIS_REPORT','PROJECT_CONVENTION_DRAFT','JUDGE_RUN')),
    owner_id TEXT NOT NULL CHECK (length(owner_id) > 0),
    candidate_kind TEXT NOT NULL CHECK (candidate_kind IN (
        'DECOMPOSITION_PLAN_V2','ACCEPTANCE_CLOSED_CHOICE_V7','PACKAGE_DESIGN_V1',
        'ROLLING_PACKAGE_PLAN_V1','REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')),
    workflow_step TEXT NOT NULL,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 0),
    owner_version INTEGER NOT NULL CHECK (owner_version >= 0),
    submission_channel TEXT NOT NULL CHECK (
        submission_channel IN ('INTERNAL_MCP','IN_PROCESS_LEGACY')),
    contract_version TEXT NOT NULL,
    runtime_generation_id TEXT NOT NULL,
    external_session_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (
        state IN ('OPEN','ACCEPTED','WAITING_INPUT','FALLBACK_REQUIRED','CLOSED')),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 5),
    attempts_used INTEGER NOT NULL DEFAULT 0 CHECK (
        attempts_used >= 0 AND (submission_channel='INTERNAL_MCP' OR attempts_used <= max_attempts)),
    terminal_attempt_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0, close_reason TEXT
    CHECK (close_reason IS NULL OR close_reason IN (
        'NORMAL_COMPLETION_ZERO_SUBMISSION','INTERACTION_FORBIDDEN','TIMEOUT',
        'REMOTE_FAILED','OWNER_REQUESTED')),
    FOREIGN KEY(external_session_id,runtime_generation_id)
        REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id) ON DELETE RESTRICT,
    CHECK ((designer_session_id IS NOT NULL) + (task_id IS NOT NULL) + (project_id IS NOT NULL) = 1),
    CHECK (
        (candidate_kind='DECOMPOSITION_PLAN_V2'
            AND owner_type='TASK_DECOMPOSITION' AND designer_session_id IS NOT NULL)
        OR
        (candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
            AND owner_type='LOOP_SPEC_COMPILATION' AND designer_session_id IS NOT NULL)
        OR
        (candidate_kind='PACKAGE_DESIGN_V1'
            AND owner_type='DESIGN_WORK_PACKAGE' AND designer_session_id IS NOT NULL)
        OR
        (candidate_kind='ROLLING_PACKAGE_PLAN_V1'
            AND owner_type='TASK_PACKAGE_PLAN_REVISION' AND task_id IS NOT NULL)
        OR
        (candidate_kind='REVIEWER_REPORT_V1'
            AND owner_type='ANALYSIS_REPORT' AND designer_session_id IS NOT NULL)
        OR
        (candidate_kind='PROJECT_CONVENTION_V1'
            AND owner_type='PROJECT_CONVENTION_DRAFT' AND project_id IS NOT NULL)
        OR
        (candidate_kind='JUDGE_DECISION_V1'
            AND owner_type='JUDGE_RUN' AND task_id IS NOT NULL)
    ),
    CHECK (candidate_kind!='ACCEPTANCE_CLOSED_CHOICE_V7' OR max_attempts <= 2),
    CHECK (candidate_kind!='PACKAGE_DESIGN_V1' OR max_attempts <= 3),
    CHECK (candidate_kind!='ROLLING_PACKAGE_PLAN_V1' OR max_attempts <= 3),
    CHECK (candidate_kind!='REVIEWER_REPORT_V1' OR max_attempts <= 3),
    CHECK (candidate_kind!='PROJECT_CONVENTION_V1' OR max_attempts <= 3),
    CHECK (candidate_kind!='JUDGE_DECISION_V1' OR max_attempts <= 2)
);

INSERT INTO ai_candidate_submission_run SELECT * FROM ai_candidate_submission_run_v68;
DROP TABLE ai_candidate_submission_run_v68;

-- Restore the original indexes and triggers in their original creation order.
CREATE TRIGGER trg_candidate_owner_scope_insert
BEFORE INSERT ON ai_candidate_submission_run
BEGIN
    SELECT CASE
        WHEN NEW.owner_type='TASK_DECOMPOSITION' AND NOT EXISTS (
            SELECT 1 FROM task_decomposition owner
            WHERE owner.id=NEW.owner_id AND owner.designer_session_id=NEW.designer_session_id)
            THEN RAISE(ABORT,'candidate owner scope mismatch')
        WHEN NEW.owner_type='LOOP_SPEC_COMPILATION' AND NOT EXISTS (
            SELECT 1 FROM loop_spec_compilation owner
            WHERE owner.id=NEW.owner_id AND owner.designer_session_id=NEW.designer_session_id)
            THEN RAISE(ABORT,'candidate owner scope mismatch')
        WHEN NEW.owner_type='DESIGN_WORK_PACKAGE' AND NOT EXISTS (
            SELECT 1 FROM design_work_package owner
            WHERE owner.id=NEW.owner_id AND owner.designer_session_id=NEW.designer_session_id)
            THEN RAISE(ABORT,'candidate owner scope mismatch')
        WHEN NEW.owner_type='TASK_PACKAGE_PLAN_REVISION' AND NOT EXISTS (
            SELECT 1 FROM task_package_plan_revision owner
            WHERE owner.id=NEW.owner_id AND owner.task_id=NEW.task_id)
            THEN RAISE(ABORT,'candidate owner scope mismatch')
        WHEN NEW.owner_type='ANALYSIS_REPORT' AND NOT EXISTS (
            SELECT 1 FROM analysis_report owner
            WHERE owner.id=NEW.owner_id AND owner.designer_session_id=NEW.designer_session_id)
            THEN RAISE(ABORT,'candidate owner scope mismatch')
        WHEN NEW.owner_type='PROJECT_CONVENTION_DRAFT' AND NOT EXISTS (
            SELECT 1 FROM project_convention_draft owner
            WHERE owner.id=NEW.owner_id AND owner.project_id=NEW.project_id)
            THEN RAISE(ABORT,'candidate owner scope mismatch')
        WHEN NEW.owner_type='JUDGE_RUN' AND NOT EXISTS (
            SELECT 1 FROM judge_run owner
            WHERE owner.id=NEW.owner_id AND owner.task_id=NEW.task_id)
            THEN RAISE(ABORT,'candidate owner scope mismatch')
    END;
END;

CREATE UNIQUE INDEX ux_candidate_submission_open_owner
    ON ai_candidate_submission_run(owner_type,owner_id,workflow_step)
    WHERE state='OPEN';

CREATE INDEX idx_candidate_submission_scope_state
    ON ai_candidate_submission_run(designer_session_id,task_id,project_id,state,updated_at);

CREATE INDEX idx_candidate_submission_external_session
    ON ai_candidate_submission_run(external_session_id,created_at);

CREATE TRIGGER trg_candidate_owner_scope_update
BEFORE UPDATE OF designer_session_id,task_id,project_id,owner_type,owner_id,candidate_kind
ON ai_candidate_submission_run
BEGIN
    SELECT RAISE(ABORT,'candidate owner and scope are immutable');
END;

CREATE TRIGGER candidate_zero_submission_requires_initial_prompt_ack
BEFORE UPDATE OF state,close_reason ON ai_candidate_submission_run
WHEN NEW.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
  AND NEW.submission_channel='INTERNAL_MCP'
  AND NEW.state='CLOSED'
  AND NEW.close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION'
BEGIN
  SELECT CASE WHEN NEW.attempts_used<>0
    THEN RAISE(ABORT,'zero-submission candidate close requires zero attempts') END;
  SELECT CASE WHEN NOT EXISTS (
      SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
      WHERE dispatch.run_id=NEW.id
        AND dispatch.dispatch_kind='INITIAL'
        AND dispatch.model_call_consumed=1
        AND dispatch.dispatch_attempted=1
        AND dispatch.acknowledged=1)
    THEN RAISE(ABORT,'zero-submission candidate close requires acknowledged initial prompt') END;
END;

CREATE TRIGGER acceptance_candidate_internal_run_requirement_v53
AFTER INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
  AND NEW.submission_channel='INTERNAL_MCP'
BEGIN
  INSERT INTO acceptance_candidate_internal_launch_run_requirement(
    candidate_run_id,launch_id,created_at)
  SELECT NEW.id,launch.id,NEW.created_at
  FROM acceptance_candidate_internal_launch launch
  WHERE launch.candidate_run_id=NEW.id AND launch.state='CREATED';
END;

CREATE TRIGGER acceptance_candidate_internal_run_update_gate_v53
BEFORE UPDATE ON ai_candidate_submission_run
WHEN OLD.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
  AND OLD.submission_channel='INTERNAL_MCP'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch launch
    JOIN acceptance_candidate_internal_launch_settlement_certificate certificate
      ON certificate.launch_id=launch.id AND certificate.candidate_run_id=OLD.id
    JOIN acceptance_candidate_internal_launch_run_requirement requirement
      ON requirement.launch_id=launch.id AND requirement.candidate_run_id=OLD.id
    WHERE launch.candidate_run_id=OLD.id
      AND launch.state='SETTLED'
      AND launch.compilation_id=OLD.owner_id
      AND launch.designer_session_id=OLD.designer_session_id
      AND launch.source_design_revision=OLD.source_revision
      AND launch.settled_owner_version=OLD.owner_version
      AND launch.external_session_id=OLD.external_session_id
      AND launch.runtime_generation_id=OLD.runtime_generation_id
  ) THEN RAISE(ABORT,'Acceptance INTERNAL_MCP run update requires its SETTLED internal launch') END;
END;

CREATE TRIGGER acceptance_candidate_live_run_delete_v53
BEFORE DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND (
  OLD.state='OPEN' OR EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    WHERE dispatch.run_id=OLD.id AND dispatch.state NOT IN ('STOPPED','CANCELLED')))
BEGIN
  SELECT RAISE(ABORT,'live Acceptance candidate run cannot be deleted');
END;

CREATE TRIGGER acceptance_candidate_run_launch_gate_v53
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
BEGIN
  SELECT CASE WHEN NEW.submission_channel='INTERNAL_MCP' AND NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch launch
    JOIN loop_spec_compilation compilation ON compilation.id=launch.compilation_id
    WHERE launch.candidate_run_id=NEW.id
      AND launch.compilation_id=NEW.owner_id
      AND launch.designer_session_id=NEW.designer_session_id
      AND launch.source_design_revision=NEW.source_revision
      AND launch.contract_version=NEW.contract_version
      AND launch.workflow_step=NEW.workflow_step
      AND launch.runtime_generation_id=NEW.runtime_generation_id
      AND launch.external_session_id=NEW.external_session_id
      AND launch.state='CREATED'
      AND launch.settled_owner_version IS NULL
      AND launch.termination_proof IS NULL
      AND launch.prepared_owner_version+1=NEW.owner_version
      AND compilation.designer_session_id=NEW.designer_session_id
      AND compilation.work_package_id=launch.work_package_id
      AND compilation.design_revision=NEW.source_revision
      AND compilation.source_design_message_id=launch.source_design_message_id
      AND compilation.source_draft_version=launch.source_draft_version
      AND compilation.state='RUNNING'
      AND compilation.external_session_id=NEW.external_session_id
      AND compilation.external_session_state='CANDIDATE_PROMPT_PENDING'
      AND compilation.version=NEW.owner_version
      AND NEW.owner_type='LOOP_SPEC_COMPILATION'
      AND NEW.state='OPEN' AND NEW.attempts_used=0 AND NEW.max_attempts=2
      AND NOT EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
        WHERE cleanup.launch_id=launch.id))
    THEN RAISE(ABORT,'Acceptance INTERNAL_MCP run requires its exact CREATED internal launch gate') END;
  SELECT CASE WHEN NEW.submission_channel='IN_PROCESS_LEGACY' AND NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
    JOIN loop_spec_compilation compilation ON compilation.id=handoff.compilation_id
    WHERE handoff.compilation_id=NEW.owner_id
      AND handoff.designer_session_id=NEW.designer_session_id
      AND handoff.source_design_revision=NEW.source_revision
      AND handoff.contract_version=NEW.contract_version
      AND handoff.legacy_external_session_id=NEW.external_session_id
      AND handoff.legacy_runtime_generation_id=NEW.runtime_generation_id
      AND handoff.legacy_external_state='CREATED'
      AND handoff.state='LEGACY_CREATED'
      AND handoff.current_owner_version+1=NEW.owner_version
      AND (handoff.old_external_session_id IS NULL OR handoff.old_termination_proof IS NOT NULL)
      AND compilation.designer_session_id=NEW.designer_session_id
      AND compilation.work_package_id=handoff.work_package_id
      AND compilation.design_revision=NEW.source_revision
      AND compilation.source_design_message_id=handoff.source_design_message_id
      AND compilation.source_draft_version=handoff.source_draft_version
      AND compilation.state='RUNNING'
      AND compilation.external_session_id=NEW.external_session_id
      AND compilation.external_session_state='CANDIDATE_LEGACY_RUNNING'
      AND compilation.version=NEW.owner_version
      AND NEW.owner_type='LOOP_SPEC_COMPILATION'
      AND NEW.workflow_step='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND NEW.state='OPEN' AND NEW.attempts_used=0 AND NEW.max_attempts=2
      AND NOT EXISTS (SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
        WHERE cleanup.handoff_id=handoff.id AND cleanup.state<>'STOPPED'))
    THEN RAISE(ABORT,'Acceptance IN_PROCESS_LEGACY run requires its exact LEGACY_CREATED handoff gate') END;
  SELECT CASE WHEN NEW.submission_channel NOT IN ('INTERNAL_MCP','IN_PROCESS_LEGACY')
    THEN RAISE(ABORT,'Acceptance run submission channel is outside the closed compatibility set') END;
END;

CREATE TRIGGER acceptance_internal_intent_run_insert_gate_v54
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND NEW.submission_channel='INTERNAL_MCP'
  AND EXISTS (SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.candidate_run_id=NEW.id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'active acceptance internal termination blocks run insert'); END;

CREATE TRIGGER acceptance_internal_intent_run_progress_gate_v54
BEFORE UPDATE OF state ON ai_candidate_submission_run
WHEN OLD.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
  AND OLD.submission_channel='INTERNAL_MCP' AND NEW.state<>OLD.state
  AND NEW.state NOT IN ('ACCEPTED','WAITING_INPUT','FALLBACK_REQUIRED','CLOSED')
  AND EXISTS (SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.candidate_run_id=OLD.id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'active acceptance internal termination blocks run progress'); END;

CREATE TRIGGER ai_candidate_internal_launch_run_insert_gate
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
  AND NEW.submission_channel='INTERNAL_MCP'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch launch
    WHERE launch.candidate_run_id=NEW.id AND launch.candidate_kind=NEW.candidate_kind
      AND launch.owner_type=NEW.owner_type AND launch.owner_id=NEW.owner_id
      AND launch.designer_session_id IS NEW.designer_session_id
      AND launch.task_id IS NEW.task_id AND launch.project_id IS NEW.project_id
      AND launch.workflow_step=NEW.workflow_step AND launch.source_revision=NEW.source_revision
      AND launch.contract_version=NEW.contract_version AND launch.max_attempts=NEW.max_attempts
      AND launch.prepared_owner_version+1=NEW.owner_version
      AND launch.runtime_generation_id=NEW.runtime_generation_id
      AND launch.external_session_id=NEW.external_session_id
      AND launch.state='CREATED' AND launch.settled_owner_version IS NULL
      AND launch.termination_proof IS NULL
      AND NEW.state='OPEN' AND NEW.attempts_used=0
      AND NOT EXISTS (SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
                      WHERE cleanup.launch_id=launch.id)
      AND NOT EXISTS (SELECT 1 FROM ai_candidate_internal_termination_intent intent
                      WHERE intent.launch_id=launch.id AND intent.state<>'COMPLETED')
      AND (
        (NEW.candidate_kind='REVIEWER_REPORT_V1' AND EXISTS (
          SELECT 1 FROM analysis_report owner WHERE owner.id=launch.analysis_report_id
            AND owner.designer_session_id=launch.designer_session_id
            AND owner.state='RUNNING' AND owner.external_session_id=NEW.external_session_id
            AND owner.version=NEW.owner_version))
        OR (NEW.candidate_kind='PROJECT_CONVENTION_V1' AND EXISTS (
          SELECT 1 FROM project_convention_draft owner
          WHERE owner.id=launch.project_convention_draft_id AND owner.project_id=launch.project_id
            AND owner.state='RUNNING' AND owner.external_session_id=NEW.external_session_id
            AND owner.version=NEW.owner_version))
        OR (NEW.candidate_kind='JUDGE_DECISION_V1' AND EXISTS (
          SELECT 1 FROM judge_run owner WHERE owner.id=launch.judge_run_id
            AND owner.task_id=launch.task_id AND owner.state='RUNNING'
            AND owner.external_session_id=NEW.external_session_id
            AND owner.version=NEW.owner_version))))
    THEN RAISE(ABORT,'generic candidate run requires its exact CREATED launch and attached owner') END;
END;

CREATE TRIGGER ai_candidate_internal_launch_run_requirement_insert
AFTER INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
  AND NEW.submission_channel='INTERNAL_MCP'
BEGIN
  INSERT INTO ai_candidate_internal_launch_run_requirement(candidate_run_id,launch_id,created_at)
  SELECT NEW.id,launch.id,NEW.created_at FROM ai_candidate_internal_launch launch
  WHERE launch.candidate_run_id=NEW.id AND launch.state='CREATED';
END;

CREATE TRIGGER ai_candidate_zero_submission_requires_initial_prompt_ack
BEFORE UPDATE OF state,close_reason ON ai_candidate_submission_run
WHEN NEW.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
  AND NEW.submission_channel='INTERNAL_MCP' AND NEW.state='CLOSED'
  AND NEW.close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION'
BEGIN
  SELECT CASE WHEN NEW.attempts_used<>0
    THEN RAISE(ABORT,'generic zero-submission close requires zero attempts') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    JOIN ai_candidate_internal_launch launch ON launch.id=dispatch.candidate_launch_id
    WHERE dispatch.run_id=NEW.id AND dispatch.dispatch_kind='INITIAL'
      AND dispatch.model_call_consumed=1 AND dispatch.dispatch_attempted=1
      AND dispatch.acknowledged=1 AND dispatch.state='ACKNOWLEDGED'
      AND launch.candidate_run_id=NEW.id AND launch.state='SETTLED')
    THEN RAISE(ABORT,'generic zero-submission close requires acknowledged initial prompt') END;
END;

CREATE TRIGGER ai_candidate_internal_run_update_gate
BEFORE UPDATE ON ai_candidate_submission_run
WHEN OLD.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
  AND OLD.submission_channel='INTERNAL_MCP'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch launch
    JOIN ai_candidate_internal_launch_settlement_certificate certificate
      ON certificate.launch_id=launch.id AND certificate.candidate_run_id=OLD.id
    JOIN ai_candidate_internal_launch_run_requirement requirement
      ON requirement.launch_id=launch.id AND requirement.candidate_run_id=OLD.id
    WHERE launch.candidate_run_id=OLD.id AND launch.state='SETTLED'
      AND launch.candidate_kind=OLD.candidate_kind AND launch.owner_type=OLD.owner_type
      AND launch.owner_id=OLD.owner_id AND launch.external_session_id=OLD.external_session_id
      AND launch.runtime_generation_id=OLD.runtime_generation_id)
    THEN RAISE(ABORT,'generic candidate run update requires its SETTLED launch') END;
END;

CREATE TRIGGER ai_candidate_internal_run_live_delete
BEFORE DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
  AND OLD.submission_channel='INTERNAL_MCP'
  AND (OLD.state='OPEN' OR EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    WHERE dispatch.run_id=OLD.id AND dispatch.state NOT IN ('STOPPED','CANCELLED')))
BEGIN SELECT RAISE(ABORT,'live generic candidate run cannot be deleted'); END;

CREATE TRIGGER trg_reviewer_report_candidate_run_snapshot_gate
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='REVIEWER_REPORT_V1'
  AND NEW.submission_channel='INTERNAL_MCP'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM reviewer_report_candidate_source_snapshot snapshot
        JOIN analysis_report owner ON owner.id=snapshot.analysis_report_id
        WHERE snapshot.candidate_run_id=NEW.id
          AND snapshot.analysis_report_id=NEW.owner_id
          AND snapshot.source_revision=NEW.source_revision
          AND snapshot.prepared_owner_version+1=NEW.owner_version
          AND snapshot.contract_version=NEW.contract_version
          AND owner.designer_session_id=NEW.designer_session_id
          AND NEW.task_id IS NULL AND NEW.project_id IS NULL
          AND NEW.owner_type='ANALYSIS_REPORT'
          AND NEW.workflow_step='REVIEWER_REPORT_V1'
          AND NEW.contract_version='REVIEWER_REPORT_V1'
          AND NEW.max_attempts=3)
        THEN RAISE(ABORT,'Reviewer candidate run requires exact frozen source snapshot') END;
END;

CREATE TRIGGER trg_reviewer_report_candidate_run_snapshot_cleanup
AFTER DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind='REVIEWER_REPORT_V1'
BEGIN
    DELETE FROM reviewer_report_candidate_source_snapshot
    WHERE candidate_run_id=OLD.id;
END;

CREATE TRIGGER trg_project_convention_candidate_run_source_gate
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='PROJECT_CONVENTION_V1'
  AND NEW.submission_channel='INTERNAL_MCP'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM project_convention_candidate_source_snapshot snapshot
    JOIN project_convention_draft owner
      ON owner.id=snapshot.project_convention_draft_id
    WHERE snapshot.candidate_run_id=NEW.id
      AND snapshot.project_id=NEW.project_id
      AND snapshot.project_convention_draft_id=NEW.owner_id
      AND snapshot.source_revision=NEW.source_revision
      AND snapshot.prepared_owner_version+1=NEW.owner_version
      AND snapshot.contract_version=NEW.contract_version
      AND NEW.owner_type='PROJECT_CONVENTION_DRAFT'
      AND NEW.workflow_step='PROJECT_CONVENTION_V1'
      AND NEW.max_attempts=3
      AND owner.project_id=NEW.project_id
      AND owner.response_mode='INTERNAL_MCP'
      AND owner.source_revision=NEW.source_revision
      AND owner.version=NEW.owner_version)
    THEN RAISE(ABORT,'Convention candidate run requires exact frozen source snapshot') END;
END;

CREATE TRIGGER trg_project_convention_candidate_run_snapshot_cleanup
AFTER DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind='PROJECT_CONVENTION_V1'
BEGIN
  DELETE FROM project_convention_candidate_source_snapshot
  WHERE candidate_run_id=OLD.id;
END;

CREATE TRIGGER trg_judge_candidate_run_source_gate
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='JUDGE_DECISION_V1'
  AND NEW.submission_channel='INTERNAL_MCP'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM judge_candidate_source_snapshot snapshot
    JOIN judge_run owner ON owner.id=snapshot.judge_run_id
    JOIN judge_review_batch batch ON batch.id=snapshot.review_batch_id
    WHERE snapshot.candidate_run_id=NEW.id
      AND snapshot.judge_run_id=NEW.owner_id
      AND snapshot.task_id=NEW.task_id
      AND snapshot.source_revision=NEW.source_revision
      AND snapshot.prepared_owner_version+1=NEW.owner_version
      AND snapshot.contract_version=NEW.contract_version
      AND NEW.owner_type='JUDGE_RUN'
      AND NEW.workflow_step='JUDGE_DECISION_V1'
      AND NEW.max_attempts=2
      AND owner.task_id=NEW.task_id
      AND owner.review_batch_id=snapshot.review_batch_id
      AND owner.source_revision=snapshot.source_revision
      AND owner.role=snapshot.role AND owner.ordinal=snapshot.ordinal
      AND owner.state='RUNNING'
      AND owner.external_session_id=NEW.external_session_id
      AND owner.version=NEW.owner_version
      AND batch.task_id=NEW.task_id
      AND batch.generation=snapshot.source_revision)
    THEN RAISE(ABORT,'Judge candidate run requires exact frozen source snapshot') END;
END;

CREATE TRIGGER trg_judge_candidate_run_snapshot_cleanup
AFTER DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind='JUDGE_DECISION_V1'
BEGIN
  DELETE FROM judge_candidate_source_snapshot WHERE candidate_run_id=OLD.id;
END;

-- Check the rebuilt candidate graph without auditing unrelated historical data.
CREATE TEMP TABLE candidate_submission_v69_fk_guard (violations INTEGER CHECK (violations=0));
INSERT INTO candidate_submission_v69_fk_guard SELECT COUNT(*) FROM pragma_foreign_key_check
  WHERE "table"='ai_candidate_submission_run' OR parent='ai_candidate_submission_run';
DROP TABLE candidate_submission_v69_fk_guard;
RELEASE candidate_submission_v69;
PRAGMA legacy_alter_table=OFF;
PRAGMA foreign_keys=ON;
