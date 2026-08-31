-- Bind every persistent OpenCode Session to its creation-time runtime identity.
-- Secrets and endpoint URLs are deliberately absent; the fingerprint is non-reversible.
CREATE TABLE open_code_session_runtime_binding (
    external_session_id TEXT PRIMARY KEY,
    runtime_generation_id TEXT NOT NULL,
    ownership_mode TEXT NOT NULL CHECK (ownership_mode IN ('MANAGED','EXTERNAL','LEGACY_UNKNOWN')),
    endpoint_fingerprint TEXT NOT NULL CHECK (length(endpoint_fingerprint) = 64),
    internal_mcp_server TEXT,
    created_at TEXT NOT NULL,
    UNIQUE(external_session_id,runtime_generation_id)
);
CREATE INDEX idx_open_code_session_runtime_generation
    ON open_code_session_runtime_binding(runtime_generation_id,created_at);

-- V46 and older never persisted enough server identity to prove which runtime
-- owns a recovered remote id. Preserve those ids, but mark them fail-closed.
INSERT OR IGNORE INTO open_code_session_runtime_binding(
    external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
    internal_mcp_server,created_at)
SELECT external_session_id,
       'legacy-unbound-' || lower(hex(external_session_id)),
       'LEGACY_UNKNOWN',
       '0000000000000000000000000000000000000000000000000000000000000000',
       NULL,
       strftime('%Y-%m-%dT%H:%M:%fZ','now')
FROM (
    SELECT external_session_id FROM execution_session WHERE external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM judge_run WHERE external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM designer_session WHERE external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM project_convention_draft WHERE external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM loop_spec_compilation WHERE external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM task_decomposition WHERE external_session_id IS NOT NULL
    UNION SELECT designer_external_session_id FROM design_work_package
        WHERE designer_external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM task_profile_router_run WHERE external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM analysis_report WHERE external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM task_package_plan_revision WHERE external_session_id IS NOT NULL
    UNION SELECT old_external_session_id FROM designer_attachment_submission
        WHERE old_external_session_id IS NOT NULL
    UNION SELECT new_external_session_id FROM designer_attachment_submission
        WHERE new_external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM interaction WHERE external_session_id IS NOT NULL
    UNION SELECT external_session_id FROM model_token_usage WHERE external_session_id IS NOT NULL
) legacy_sessions;

-- One bounded authoritative submission run belongs to exactly one existing
-- machine-role owner. The kind/owner and submission-channel CHECKs prevent a
-- candidate from crossing roles or entering an MCP-only run through a legacy path.
CREATE TABLE ai_candidate_submission_run (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    task_decomposition_id TEXT REFERENCES task_decomposition(id) ON DELETE CASCADE,
    loop_spec_compilation_id TEXT REFERENCES loop_spec_compilation(id) ON DELETE CASCADE,
    candidate_kind TEXT NOT NULL CHECK (candidate_kind IN (
        'DECOMPOSITION_PLAN_V2','ACCEPTANCE_CLOSED_CHOICE_V7')),
    workflow_step TEXT NOT NULL,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 0),
    owner_version INTEGER NOT NULL CHECK (owner_version >= 0),
    submission_channel TEXT NOT NULL CHECK (
        submission_channel IN ('INTERNAL_MCP','IN_PROCESS_LEGACY')),
    contract_version TEXT NOT NULL,
    runtime_generation_id TEXT NOT NULL,
    external_session_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN','ACCEPTED','WAITING_INPUT','CLOSED')),
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
            AND task_decomposition_id IS NOT NULL AND loop_spec_compilation_id IS NULL)
        OR
        (candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
            AND task_decomposition_id IS NULL AND loop_spec_compilation_id IS NOT NULL)
    ),
    CHECK (candidate_kind!='ACCEPTANCE_CLOSED_CHOICE_V7' OR max_attempts <= 2)
);
CREATE UNIQUE INDEX ux_candidate_submission_open_decomposition
    ON ai_candidate_submission_run(task_decomposition_id,workflow_step)
    WHERE state='OPEN' AND task_decomposition_id IS NOT NULL;
CREATE UNIQUE INDEX ux_candidate_submission_open_compilation
    ON ai_candidate_submission_run(loop_spec_compilation_id,workflow_step)
    WHERE state='OPEN' AND loop_spec_compilation_id IS NOT NULL;
CREATE INDEX idx_candidate_submission_session_state
    ON ai_candidate_submission_run(designer_session_id,state,updated_at);
CREATE INDEX idx_candidate_submission_external_session
    ON ai_candidate_submission_run(external_session_id,created_at);

-- Attempts retain hashes, bounded diagnostics, and the exact safe response for
-- idempotent replay. There is intentionally no raw candidate payload column.
CREATE TABLE ai_candidate_submission_attempt (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES ai_candidate_submission_run(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    request_sha256 TEXT NOT NULL CHECK (length(request_sha256) = 64),
    outcome TEXT NOT NULL CHECK (outcome IN ('REJECTED','WAITING_INPUT','ACCEPTED')),
    retryable INTEGER NOT NULL CHECK (retryable IN (0,1)),
    problems_json TEXT NOT NULL CHECK (length(problems_json) <= 32768),
    response_json TEXT NOT NULL CHECK (length(response_json) <= 32768),
    canonical_result_sha256 TEXT CHECK (
        canonical_result_sha256 IS NULL OR length(canonical_result_sha256) = 64),
    created_at TEXT NOT NULL,
    UNIQUE(run_id,ordinal),
    UNIQUE(run_id,idempotency_key)
);
CREATE INDEX idx_candidate_submission_attempt_run
    ON ai_candidate_submission_attempt(run_id,ordinal);
