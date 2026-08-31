-- Package-design candidates share the V47 authoritative submission protocol,
-- but settle through an immutable accepted-result handoff owned by the package.
ALTER TABLE loop_spec_compilation ADD COLUMN compilation_source TEXT
    CHECK (compilation_source IS NULL OR compilation_source IN ('MCP_ACCEPTED','MARKDOWN_FALLBACK'));
ALTER TABLE loop_spec_compilation ADD COLUMN fallback_reason TEXT;

-- Rebuild both sides of the V47 run/attempt foreign key so SQLite keeps every
-- existing row while extending the closed enum and owner CHECK constraints.
ALTER TABLE ai_candidate_submission_attempt RENAME TO ai_candidate_submission_attempt_v47;
ALTER TABLE ai_candidate_submission_run RENAME TO ai_candidate_submission_run_v47;

CREATE TABLE ai_candidate_submission_run (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    task_decomposition_id TEXT REFERENCES task_decomposition(id) ON DELETE CASCADE,
    loop_spec_compilation_id TEXT REFERENCES loop_spec_compilation(id) ON DELETE CASCADE,
    design_work_package_id TEXT REFERENCES design_work_package(id) ON DELETE CASCADE,
    candidate_kind TEXT NOT NULL CHECK (candidate_kind IN (
        'DECOMPOSITION_PLAN_V2','ACCEPTANCE_CLOSED_CHOICE_V7','PACKAGE_DESIGN_V1')),
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
    CHECK (
        (candidate_kind='DECOMPOSITION_PLAN_V2'
            AND task_decomposition_id IS NOT NULL
            AND loop_spec_compilation_id IS NULL AND design_work_package_id IS NULL)
        OR
        (candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
            AND task_decomposition_id IS NULL
            AND loop_spec_compilation_id IS NOT NULL AND design_work_package_id IS NULL)
        OR
        (candidate_kind='PACKAGE_DESIGN_V1'
            AND task_decomposition_id IS NULL
            AND loop_spec_compilation_id IS NULL AND design_work_package_id IS NOT NULL)
    ),
    CHECK (candidate_kind!='ACCEPTANCE_CLOSED_CHOICE_V7' OR max_attempts <= 2),
    CHECK (candidate_kind!='PACKAGE_DESIGN_V1' OR max_attempts <= 3)
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

INSERT INTO ai_candidate_submission_run(
    id,designer_session_id,task_decomposition_id,loop_spec_compilation_id,design_work_package_id,
    candidate_kind,workflow_step,source_revision,owner_version,submission_channel,contract_version,
    runtime_generation_id,external_session_id,state,max_attempts,attempts_used,terminal_attempt_id,
    created_at,updated_at,version)
SELECT id,designer_session_id,task_decomposition_id,loop_spec_compilation_id,NULL,
    candidate_kind,workflow_step,source_revision,owner_version,submission_channel,contract_version,
    runtime_generation_id,external_session_id,state,max_attempts,attempts_used,terminal_attempt_id,
    created_at,updated_at,version
FROM ai_candidate_submission_run_v47;

INSERT INTO ai_candidate_submission_attempt(
    id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
    canonical_result_sha256,created_at)
SELECT id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
    canonical_result_sha256,created_at
FROM ai_candidate_submission_attempt_v47;

DROP TABLE ai_candidate_submission_attempt_v47;
DROP TABLE ai_candidate_submission_run_v47;

CREATE UNIQUE INDEX ux_candidate_submission_open_decomposition
    ON ai_candidate_submission_run(task_decomposition_id,workflow_step)
    WHERE state='OPEN' AND task_decomposition_id IS NOT NULL;
CREATE UNIQUE INDEX ux_candidate_submission_open_compilation
    ON ai_candidate_submission_run(loop_spec_compilation_id,workflow_step)
    WHERE state='OPEN' AND loop_spec_compilation_id IS NOT NULL;
CREATE UNIQUE INDEX ux_candidate_submission_open_package
    ON ai_candidate_submission_run(design_work_package_id,workflow_step)
    WHERE state='OPEN' AND design_work_package_id IS NOT NULL;
CREATE INDEX idx_candidate_submission_session_state
    ON ai_candidate_submission_run(designer_session_id,state,updated_at);
CREATE INDEX idx_candidate_submission_external_session
    ON ai_candidate_submission_run(external_session_id,created_at);
CREATE INDEX idx_candidate_submission_attempt_run
    ON ai_candidate_submission_attempt(run_id,ordinal);

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
CREATE INDEX idx_package_design_result_unsettled
    ON package_design_candidate_accepted_result(settled_compilation_id,created_at,candidate_run_id);
CREATE INDEX idx_package_design_result_package_latest
    ON package_design_candidate_accepted_result(design_work_package_id,created_at DESC,candidate_run_id DESC);
