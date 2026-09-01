-- Generalize V47/V48 candidate ownership without weakening the role-specific
-- owner/scope relationship. Scope keeps a real aggregate FK while the typed
-- owner reference is checked during historical copy and every later insert,
-- then cleaned when its row is deleted.
ALTER TABLE package_design_candidate_accepted_result
    RENAME TO package_design_candidate_accepted_result_v48;
ALTER TABLE ai_candidate_submission_attempt RENAME TO ai_candidate_submission_attempt_v48;
ALTER TABLE ai_candidate_submission_run RENAME TO ai_candidate_submission_run_v48;

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
        attempts_used >= 0 AND attempts_used <= max_attempts),
    terminal_attempt_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
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

CREATE TABLE ai_candidate_submission_attempt (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES ai_candidate_submission_run(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    request_sha256 TEXT NOT NULL CHECK (length(request_sha256) = 64),
    outcome TEXT NOT NULL CHECK (
        outcome IN ('REJECTED','WAITING_INPUT','FALLBACK_REQUIRED','ACCEPTED')),
    retryable INTEGER NOT NULL CHECK (retryable IN (0,1)),
    problems_json TEXT NOT NULL CHECK (length(problems_json) <= 32768),
    response_json TEXT NOT NULL CHECK (length(response_json) <= 32768),
    canonical_result_sha256 TEXT CHECK (
        canonical_result_sha256 IS NULL OR length(canonical_result_sha256) = 64),
    created_at TEXT NOT NULL,
    UNIQUE(run_id,ordinal),
    UNIQUE(run_id,idempotency_key)
);

CREATE TABLE package_design_candidate_accepted_result (
    candidate_run_id TEXT PRIMARY KEY
        REFERENCES ai_candidate_submission_run(id) ON DELETE CASCADE,
    design_work_package_id TEXT NOT NULL
        REFERENCES design_work_package(id) ON DELETE CASCADE,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 0),
    owner_version INTEGER NOT NULL CHECK (owner_version >= 0),
    contract_version TEXT NOT NULL CHECK (length(contract_version) > 0),
    canonical_candidate_json TEXT NOT NULL CHECK (length(canonical_candidate_json) > 0),
    canonical_markdown TEXT NOT NULL CHECK (length(canonical_markdown) > 0),
    compiled_result_json TEXT NOT NULL CHECK (length(compiled_result_json) > 0),
    canonical_result_sha256 TEXT NOT NULL CHECK (length(canonical_result_sha256) = 64),
    settled_compilation_id TEXT REFERENCES loop_spec_compilation(id) ON DELETE RESTRICT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE(design_work_package_id,source_revision,owner_version)
);

-- Install the owner/scope guard before copying V47/V48 history. This makes the
-- migration itself fail closed instead of silently normalizing a legacy run
-- whose owner belongs to another aggregate scope.
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

INSERT INTO ai_candidate_submission_run(
    id,designer_session_id,task_id,project_id,owner_type,owner_id,candidate_kind,workflow_step,
    source_revision,owner_version,submission_channel,contract_version,runtime_generation_id,
    external_session_id,state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
SELECT id,designer_session_id,NULL,NULL,
    CASE candidate_kind
        WHEN 'DECOMPOSITION_PLAN_V2' THEN 'TASK_DECOMPOSITION'
        WHEN 'ACCEPTANCE_CLOSED_CHOICE_V7' THEN 'LOOP_SPEC_COMPILATION'
        WHEN 'PACKAGE_DESIGN_V1' THEN 'DESIGN_WORK_PACKAGE'
    END,
    COALESCE(task_decomposition_id,loop_spec_compilation_id,design_work_package_id),
    candidate_kind,workflow_step,source_revision,owner_version,submission_channel,contract_version,
    runtime_generation_id,external_session_id,state,max_attempts,attempts_used,terminal_attempt_id,
    created_at,updated_at,version
FROM ai_candidate_submission_run_v48;

INSERT INTO ai_candidate_submission_attempt(
    id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
    canonical_result_sha256,created_at)
SELECT id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
    canonical_result_sha256,created_at
FROM ai_candidate_submission_attempt_v48;

INSERT INTO package_design_candidate_accepted_result(
    candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
    canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
    settled_compilation_id,created_at,updated_at,version)
SELECT candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
    canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
    settled_compilation_id,created_at,updated_at,version
FROM package_design_candidate_accepted_result_v48;

DROP TABLE package_design_candidate_accepted_result_v48;
DROP TABLE ai_candidate_submission_attempt_v48;
DROP TABLE ai_candidate_submission_run_v48;

CREATE UNIQUE INDEX ux_candidate_submission_open_owner
    ON ai_candidate_submission_run(owner_type,owner_id,workflow_step)
    WHERE state='OPEN';
CREATE INDEX idx_candidate_submission_scope_state
    ON ai_candidate_submission_run(designer_session_id,task_id,project_id,state,updated_at);
CREATE INDEX idx_candidate_submission_external_session
    ON ai_candidate_submission_run(external_session_id,created_at);
CREATE INDEX idx_candidate_submission_attempt_run
    ON ai_candidate_submission_attempt(run_id,ordinal);
CREATE INDEX idx_package_design_result_unsettled
    ON package_design_candidate_accepted_result(settled_compilation_id,created_at,candidate_run_id);
CREATE INDEX idx_package_design_result_package_latest
    ON package_design_candidate_accepted_result(design_work_package_id,created_at DESC,candidate_run_id DESC);

CREATE TRIGGER trg_candidate_owner_scope_update
BEFORE UPDATE OF designer_session_id,task_id,project_id,owner_type,owner_id,candidate_kind
ON ai_candidate_submission_run
BEGIN
    SELECT RAISE(ABORT,'candidate owner and scope are immutable');
END;

CREATE TRIGGER trg_candidate_owner_decomposition_delete
AFTER DELETE ON task_decomposition
BEGIN
    DELETE FROM ai_candidate_submission_run
    WHERE owner_type='TASK_DECOMPOSITION' AND owner_id=OLD.id;
END;
CREATE TRIGGER trg_candidate_owner_compilation_delete
AFTER DELETE ON loop_spec_compilation
BEGIN
    DELETE FROM ai_candidate_submission_run
    WHERE owner_type='LOOP_SPEC_COMPILATION' AND owner_id=OLD.id;
END;
CREATE TRIGGER trg_candidate_owner_package_delete
AFTER DELETE ON design_work_package
BEGIN
    DELETE FROM ai_candidate_submission_run
    WHERE owner_type='DESIGN_WORK_PACKAGE' AND owner_id=OLD.id;
END;
CREATE TRIGGER trg_candidate_owner_plan_revision_delete
AFTER DELETE ON task_package_plan_revision
BEGIN
    DELETE FROM ai_candidate_submission_run
    WHERE owner_type='TASK_PACKAGE_PLAN_REVISION' AND owner_id=OLD.id;
END;
CREATE TRIGGER trg_candidate_owner_report_delete
AFTER DELETE ON analysis_report
BEGIN
    DELETE FROM ai_candidate_submission_run
    WHERE owner_type='ANALYSIS_REPORT' AND owner_id=OLD.id;
END;
CREATE TRIGGER trg_candidate_owner_convention_delete
AFTER DELETE ON project_convention_draft
BEGIN
    DELETE FROM ai_candidate_submission_run
    WHERE owner_type='PROJECT_CONVENTION_DRAFT' AND owner_id=OLD.id;
END;
CREATE TRIGGER trg_candidate_owner_judge_delete
AFTER DELETE ON judge_run
BEGIN
    DELETE FROM ai_candidate_submission_run
    WHERE owner_type='JUDGE_RUN' AND owner_id=OLD.id;
END;
