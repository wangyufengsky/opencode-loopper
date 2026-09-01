-- Durable saga for the unopened v7 internal-candidate -> Legacy handoff.
-- The old termination proof and the successor writer identity deliberately live
-- outside loop_spec_compilation's single current-session projection.
CREATE TABLE acceptance_candidate_legacy_handoff (
    id TEXT PRIMARY KEY,
    compilation_id TEXT NOT NULL UNIQUE REFERENCES loop_spec_compilation(id) ON DELETE CASCADE,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    work_package_id TEXT NOT NULL,
    source_design_revision INTEGER NOT NULL CHECK (source_design_revision > 0),
    source_design_message_id TEXT NOT NULL REFERENCES designer_message(id),
    source_draft_version INTEGER NOT NULL CHECK (source_draft_version >= 0),
    source_design_sha256 TEXT NOT NULL CHECK (length(source_design_sha256)=64),
    contract_version TEXT NOT NULL CHECK (contract_version='ACCEPTANCE_CLOSED_CHOICE_V7'),
    state TEXT NOT NULL CHECK (state IN (
      'STOPPING_OLD','OLD_STOPPED','CREATING_LEGACY','LEGACY_CREATED','LEGACY_OPENED',
      'PROMPTING','HANDED_OFF','SETTLED','STOPPING_LEGACY','FAILED_STOPPED','CANCELLED','STALE')),
    prepared_owner_version INTEGER NOT NULL CHECK (prepared_owner_version >= 0),
    current_owner_version INTEGER NOT NULL CHECK (current_owner_version >= 0),
    old_external_session_id TEXT,
    old_runtime_generation_id TEXT,
    old_endpoint_fingerprint TEXT CHECK (
      old_endpoint_fingerprint IS NULL OR length(old_endpoint_fingerprint)=64),
    old_external_state TEXT NOT NULL,
    old_termination_proof TEXT CHECK (old_termination_proof IS NULL OR old_termination_proof IN (
      'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    old_proof_at TEXT,
    legacy_creation_key TEXT NOT NULL UNIQUE,
    successor_exact_title TEXT NOT NULL,
    successor_canonical_directory TEXT NOT NULL,
    successor_runtime_generation_id TEXT NOT NULL,
    successor_managed INTEGER NOT NULL CHECK (successor_managed IN (0,1)),
    successor_internal_mcp_server TEXT,
    successor_endpoint_fingerprint TEXT NOT NULL CHECK (length(successor_endpoint_fingerprint)=64),
    successor_model_provider_id TEXT,
    successor_model_id TEXT,
    successor_thinking INTEGER CHECK (successor_thinking IS NULL OR successor_thinking IN (0,1)),
    successor_profile TEXT NOT NULL CHECK (successor_profile='COMPILER_BINDING_NO_TOOLS'),
    successor_permission_policy_json TEXT NOT NULL CHECK (
      json_valid(successor_permission_policy_json) AND json_type(successor_permission_policy_json)='array'),
    successor_permission_policy_digest TEXT NOT NULL CHECK (length(successor_permission_policy_digest)=64),
    successor_create_request_sha256 TEXT NOT NULL CHECK (length(successor_create_request_sha256)=64),
    successor_creation_credential TEXT NOT NULL UNIQUE CHECK (
      length(successor_creation_credential)=43
      AND successor_creation_credential NOT GLOB '*[^A-Za-z0-9_-]*'),
    successor_attestation_type TEXT NOT NULL CHECK (successor_attestation_type='LOCAL_REQUEST_ATTESTED'),
    create_claim_owner TEXT,
    create_claim_token TEXT,
    create_claim_expires_at TEXT,
    create_fence INTEGER NOT NULL DEFAULT 0 CHECK (create_fence >= 0),
    create_dispatch_attempted INTEGER NOT NULL DEFAULT 0 CHECK (create_dispatch_attempted IN (0,1)),
    create_dispatch_started_at TEXT,
    legacy_external_session_id TEXT,
    legacy_runtime_generation_id TEXT,
    legacy_endpoint_fingerprint TEXT CHECK (
      legacy_endpoint_fingerprint IS NULL OR length(legacy_endpoint_fingerprint)=64),
    legacy_external_state TEXT,
    legacy_termination_proof TEXT CHECK (legacy_termination_proof IS NULL OR legacy_termination_proof IN (
      'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    legacy_proof_at TEXT,
    legacy_prompt_message_id TEXT NOT NULL UNIQUE,
    legacy_prompt_sha256 TEXT,
    legacy_prompt_dispatch_attempted INTEGER NOT NULL DEFAULT 0 CHECK (
      legacy_prompt_dispatch_attempted IN (0,1)),
    legacy_prompt_dispatch_started_at TEXT,
    prompt_claim_owner TEXT,
    prompt_claim_token TEXT,
    prompt_claim_expires_at TEXT,
    prompt_fence INTEGER NOT NULL DEFAULT 0 CHECK (prompt_fence >= 0),
    model_call_consumed INTEGER NOT NULL DEFAULT 0 CHECK (model_call_consumed IN (0,1)),
    model_call_consumed_at TEXT,
    failure_phase TEXT CHECK (failure_phase IS NULL OR failure_phase IN (
      'OLD_STOP','LEGACY_CREATE','LEGACY_OPEN','LEGACY_PROMPT','LEGACY_STOP','OWNER_REVALIDATION')),
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(old_external_session_id,old_runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id) ON DELETE RESTRICT,
    FOREIGN KEY(legacy_external_session_id,legacy_runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id) ON DELETE RESTRICT,
    CHECK ((old_external_session_id IS NULL)=(old_runtime_generation_id IS NULL)),
    CHECK ((old_external_session_id IS NULL)=(old_endpoint_fingerprint IS NULL)),
    CHECK ((legacy_external_session_id IS NULL)=(legacy_runtime_generation_id IS NULL)),
    CHECK ((legacy_external_session_id IS NULL)=(legacy_endpoint_fingerprint IS NULL)),
    CHECK ((old_termination_proof IS NULL)=(old_proof_at IS NULL)),
    CHECK ((legacy_termination_proof IS NULL)=(legacy_proof_at IS NULL)),
    CHECK ((successor_model_provider_id IS NULL)=(successor_model_id IS NULL)),
    CHECK ((successor_managed=0 AND successor_internal_mcp_server IS NULL)
      OR (successor_managed=1 AND successor_internal_mcp_server IS NOT NULL)),
    CHECK ((create_claim_owner IS NULL)=(create_claim_token IS NULL)),
    CHECK ((create_claim_owner IS NULL)=(create_claim_expires_at IS NULL)),
    CHECK ((create_dispatch_attempted=0 AND create_dispatch_started_at IS NULL)
      OR (create_dispatch_attempted=1 AND create_dispatch_started_at IS NOT NULL)),
    CHECK ((prompt_claim_owner IS NULL)=(prompt_claim_token IS NULL)),
    CHECK ((prompt_claim_owner IS NULL)=(prompt_claim_expires_at IS NULL)),
    CHECK ((legacy_prompt_dispatch_attempted=0 AND legacy_prompt_dispatch_started_at IS NULL)
      OR (legacy_prompt_dispatch_attempted=1 AND legacy_prompt_dispatch_started_at IS NOT NULL)),
    CHECK ((model_call_consumed=0 AND model_call_consumed_at IS NULL)
      OR (model_call_consumed=1 AND model_call_consumed_at IS NOT NULL)),
    CHECK (state NOT IN ('PROMPTING','HANDED_OFF','SETTLED')
      OR (legacy_prompt_sha256 IS NOT NULL AND length(legacy_prompt_sha256)=64
        AND legacy_prompt_dispatch_attempted=1 AND model_call_consumed=1)),
    CHECK (state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
      OR legacy_external_session_id IS NULL OR legacy_termination_proof IS NOT NULL),
    CHECK (state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
      OR old_external_session_id IS NULL OR old_termination_proof IS NOT NULL),
    CHECK (state<>'SETTLED' OR (legacy_external_session_id IS NOT NULL
      AND legacy_termination_proof IS NOT NULL)),
    CHECK (state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE','HANDED_OFF')
      OR (create_claim_owner IS NULL AND prompt_claim_owner IS NULL)),
    CHECK (create_claim_owner IS NULL OR state IN ('CREATING_LEGACY','STOPPING_LEGACY')),
    CHECK (prompt_claim_owner IS NULL OR state IN ('PROMPTING','STOPPING_LEGACY')),
    CHECK (legacy_external_session_id IS NULL OR old_external_session_id IS NULL
      OR old_termination_proof IS NOT NULL),
    CHECK (model_call_consumed=0 OR legacy_external_session_id IS NOT NULL)
);

CREATE UNIQUE INDEX uq_acceptance_legacy_handoff_old_remote
    ON acceptance_candidate_legacy_handoff(old_external_session_id);
CREATE UNIQUE INDEX uq_acceptance_legacy_handoff_new_remote
    ON acceptance_candidate_legacy_handoff(legacy_external_session_id)
    WHERE legacy_external_session_id IS NOT NULL;
CREATE INDEX idx_acceptance_legacy_handoff_active
    ON acceptance_candidate_legacy_handoff(state,updated_at,id);
CREATE INDEX idx_acceptance_legacy_handoff_designer
    ON acceptance_candidate_legacy_handoff(designer_session_id,state);

CREATE TRIGGER acceptance_legacy_handoff_owner_source_insert
BEFORE INSERT ON acceptance_candidate_legacy_handoff
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM loop_spec_compilation compilation
    JOIN designer_message message
      ON message.id=NEW.source_design_message_id
     AND message.designer_session_id=NEW.designer_session_id
    JOIN design_work_package work_package
      ON work_package.designer_session_id=NEW.designer_session_id
     AND work_package.package_id=NEW.work_package_id
     AND work_package.design_revision=NEW.source_design_revision
     AND work_package.design_message_id=NEW.source_design_message_id
     AND work_package.id=(
       SELECT current_work_package.id FROM design_work_package current_work_package
       WHERE current_work_package.designer_session_id=NEW.designer_session_id
         AND current_work_package.package_id=NEW.work_package_id
       ORDER BY current_work_package.plan_revision DESC,current_work_package.created_at DESC LIMIT 1)
    JOIN design_requirement_revision revision
      ON revision.id=work_package.requirement_revision_id
     AND revision.designer_session_id=NEW.designer_session_id
     AND revision.source_draft_version=NEW.source_draft_version
    WHERE compilation.id=NEW.compilation_id
      AND compilation.designer_session_id=NEW.designer_session_id
      AND compilation.work_package_id=NEW.work_package_id
      AND compilation.design_revision=NEW.source_design_revision
      AND compilation.source_design_message_id=NEW.source_design_message_id
      AND compilation.source_draft_version=NEW.source_draft_version
  ) THEN RAISE(ABORT,'acceptance candidate handoff owner/source anchor mismatch') END;
END;

CREATE TRIGGER acceptance_legacy_handoff_identity_immutable
BEFORE UPDATE ON acceptance_candidate_legacy_handoff
WHEN NEW.compilation_id<>OLD.compilation_id
  OR NEW.designer_session_id<>OLD.designer_session_id
  OR NEW.work_package_id<>OLD.work_package_id
  OR NEW.source_design_revision<>OLD.source_design_revision
  OR NEW.source_design_message_id<>OLD.source_design_message_id
  OR NEW.source_draft_version<>OLD.source_draft_version
  OR NEW.source_design_sha256<>OLD.source_design_sha256
  OR NEW.contract_version<>OLD.contract_version
  OR NEW.prepared_owner_version<>OLD.prepared_owner_version
  OR NEW.old_external_session_id IS NOT OLD.old_external_session_id
  OR NEW.old_runtime_generation_id IS NOT OLD.old_runtime_generation_id
  OR NEW.old_endpoint_fingerprint IS NOT OLD.old_endpoint_fingerprint
  OR NEW.legacy_creation_key<>OLD.legacy_creation_key
  OR NEW.successor_exact_title<>OLD.successor_exact_title
  OR NEW.successor_canonical_directory<>OLD.successor_canonical_directory
  OR NEW.successor_runtime_generation_id<>OLD.successor_runtime_generation_id
  OR NEW.successor_managed<>OLD.successor_managed
  OR NEW.successor_internal_mcp_server IS NOT OLD.successor_internal_mcp_server
  OR NEW.successor_endpoint_fingerprint<>OLD.successor_endpoint_fingerprint
  OR NEW.successor_model_provider_id IS NOT OLD.successor_model_provider_id
  OR NEW.successor_model_id IS NOT OLD.successor_model_id
  OR NEW.successor_thinking IS NOT OLD.successor_thinking
  OR NEW.successor_profile<>OLD.successor_profile
  OR NEW.successor_permission_policy_json<>OLD.successor_permission_policy_json
  OR NEW.successor_permission_policy_digest<>OLD.successor_permission_policy_digest
  OR NEW.successor_create_request_sha256<>OLD.successor_create_request_sha256
  OR NEW.successor_creation_credential<>OLD.successor_creation_credential
  OR NEW.successor_attestation_type<>OLD.successor_attestation_type
  OR NEW.legacy_prompt_message_id<>OLD.legacy_prompt_message_id
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate legacy handoff identity is immutable');
END;

CREATE TRIGGER acceptance_legacy_handoff_prompt_dispatch_immutable
BEFORE UPDATE ON acceptance_candidate_legacy_handoff
WHEN OLD.legacy_prompt_dispatch_attempted=1 AND (
  NEW.legacy_prompt_dispatch_attempted<>1
  OR NEW.legacy_prompt_dispatch_started_at<>OLD.legacy_prompt_dispatch_started_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate prompt dispatch checkpoint is immutable');
END;

CREATE TRIGGER acceptance_legacy_handoff_create_dispatch_immutable
BEFORE UPDATE ON acceptance_candidate_legacy_handoff
WHEN OLD.create_dispatch_attempted=1 AND (
  NEW.create_dispatch_attempted<>1
  OR NEW.create_dispatch_started_at<>OLD.create_dispatch_started_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate create dispatch checkpoint is immutable');
END;

CREATE TRIGGER acceptance_legacy_handoff_old_proof_irreversible
BEFORE UPDATE ON acceptance_candidate_legacy_handoff
WHEN OLD.old_termination_proof IS NOT NULL AND (
  NEW.old_termination_proof IS NOT OLD.old_termination_proof
  OR NEW.old_proof_at IS NOT OLD.old_proof_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate old termination proof is irreversible');
END;

CREATE TRIGGER acceptance_legacy_handoff_legacy_proof_irreversible
BEFORE UPDATE ON acceptance_candidate_legacy_handoff
WHEN OLD.legacy_termination_proof IS NOT NULL AND (
  NEW.legacy_termination_proof IS NOT OLD.legacy_termination_proof
  OR NEW.legacy_proof_at IS NOT OLD.legacy_proof_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate legacy termination proof is irreversible');
END;

CREATE TRIGGER acceptance_legacy_handoff_model_call_irreversible
BEFORE UPDATE ON acceptance_candidate_legacy_handoff
WHEN OLD.model_call_consumed=1 AND (
  NEW.model_call_consumed<>1
  OR NEW.model_call_consumed_at IS NOT OLD.model_call_consumed_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate model call evidence is irreversible');
END;

-- Every exact-title match is durably registered before any remote is stopped.
-- Once ambiguity has been observed, later recovery follows this ledger and can
-- never adopt a remaining singleton as the successor.
CREATE TABLE acceptance_candidate_handoff_cleanup_remote (
    handoff_id TEXT NOT NULL REFERENCES acceptance_candidate_legacy_handoff(id) ON DELETE CASCADE,
    external_session_id TEXT NOT NULL,
    runtime_generation_id TEXT NOT NULL,
    endpoint_fingerprint TEXT NOT NULL CHECK (length(endpoint_fingerprint)=64),
    directory_sha256 TEXT NOT NULL CHECK (length(directory_sha256)=64),
    title_sha256 TEXT NOT NULL CHECK (length(title_sha256)=64),
    state TEXT NOT NULL CHECK (state IN ('DISCOVERED','STOPPING','STOPPED','DISCONNECTED')),
    termination_proof TEXT CHECK (termination_proof IS NULL OR termination_proof IN (
      'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    proof_at TEXT,
    stop_claim_owner TEXT,
    stop_claim_token TEXT,
    stop_claim_expires_at TEXT,
    stop_fence INTEGER NOT NULL DEFAULT 0 CHECK (stop_fence >= 0),
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(handoff_id,external_session_id),
    FOREIGN KEY(external_session_id,runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id) ON DELETE RESTRICT,
    CHECK ((termination_proof IS NULL)=(proof_at IS NULL)),
    CHECK ((state='STOPPED')=(termination_proof IS NOT NULL)),
    CHECK ((stop_claim_owner IS NULL)=(stop_claim_token IS NULL)),
    CHECK ((stop_claim_owner IS NULL)=(stop_claim_expires_at IS NULL)),
    CHECK ((state='STOPPING')=(stop_claim_owner IS NOT NULL))
);

CREATE INDEX idx_acceptance_handoff_cleanup_active
    ON acceptance_candidate_handoff_cleanup_remote(handoff_id,state,external_session_id);

CREATE TRIGGER acceptance_handoff_cleanup_parent_accepting_insert
BEFORE INSERT ON acceptance_candidate_handoff_cleanup_remote
WHEN NOT EXISTS (
  SELECT 1 FROM acceptance_candidate_legacy_handoff parent
  WHERE parent.id=NEW.handoff_id AND parent.state IN ('CREATING_LEGACY','STOPPING_LEGACY')
)
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate cleanup remote parent is not accepting registrations');
END;

CREATE TRIGGER acceptance_legacy_handoff_cleanup_stopped_before_terminal
BEFORE UPDATE OF state ON acceptance_candidate_legacy_handoff
WHEN NEW.state IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
  AND EXISTS (
    SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
    WHERE cleanup.handoff_id=NEW.id AND cleanup.state<>'STOPPED'
  )
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate cleanup remotes must be stopped before terminal handoff');
END;

CREATE TRIGGER acceptance_handoff_cleanup_identity_immutable
BEFORE UPDATE ON acceptance_candidate_handoff_cleanup_remote
WHEN NEW.handoff_id<>OLD.handoff_id
  OR NEW.external_session_id<>OLD.external_session_id
  OR NEW.runtime_generation_id<>OLD.runtime_generation_id
  OR NEW.endpoint_fingerprint<>OLD.endpoint_fingerprint
  OR NEW.directory_sha256<>OLD.directory_sha256
  OR NEW.title_sha256<>OLD.title_sha256
BEGIN
  SELECT RAISE(ABORT,'acceptance handoff cleanup remote identity is immutable');
END;

CREATE TRIGGER acceptance_handoff_cleanup_proof_irreversible
BEFORE UPDATE ON acceptance_candidate_handoff_cleanup_remote
WHEN OLD.termination_proof IS NOT NULL AND (
  NEW.termination_proof IS NOT OLD.termination_proof
  OR NEW.proof_at IS NOT OLD.proof_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance candidate cleanup termination proof is irreversible');
END;

-- Generic durable prompt dispatch checkpoint shared by candidate workflows.
-- INITIAL authorizes the first Acceptance v7 INTERNAL_MCP prompt before any
-- submission. CORRECTION remains tied to one retryable rejected attempt.
CREATE TABLE ai_candidate_prompt_dispatch (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    dispatch_kind TEXT NOT NULL CHECK (dispatch_kind IN ('INITIAL','CORRECTION')),
    source_attempt_ordinal INTEGER CHECK (source_attempt_ordinal > 0),
    external_session_id TEXT NOT NULL,
    runtime_generation_id TEXT NOT NULL,
    message_id TEXT NOT NULL UNIQUE,
    request_json TEXT NOT NULL CHECK (
      json_valid(request_json) AND json_type(request_json)='object'),
    request_sha256 TEXT NOT NULL CHECK (length(request_sha256)=64),
    state TEXT NOT NULL CHECK (state IN (
      'PROMPTING','ACKNOWLEDGED','STOPPING','STOPPED','DISCONNECTED','CANCELLED')),
    model_call_consumed INTEGER NOT NULL DEFAULT 0 CHECK (model_call_consumed IN (0,1)),
    model_call_consumed_at TEXT,
    claim_owner TEXT,
    claim_token TEXT,
    claim_expires_at TEXT,
    fence INTEGER NOT NULL DEFAULT 0 CHECK (fence >= 0),
    dispatch_attempted INTEGER NOT NULL DEFAULT 0 CHECK (dispatch_attempted IN (0,1)),
    dispatch_started_at TEXT,
    acknowledged INTEGER NOT NULL DEFAULT 0 CHECK (acknowledged IN (0,1)),
    acked_at TEXT,
    termination_proof TEXT CHECK (termination_proof IS NULL OR termination_proof IN (
      'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    termination_proof_at TEXT,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY(run_id)
      REFERENCES ai_candidate_submission_run(id)
      ON DELETE CASCADE,
    FOREIGN KEY(run_id,source_attempt_ordinal)
      REFERENCES ai_candidate_submission_attempt(run_id,ordinal)
      ON DELETE CASCADE,
    FOREIGN KEY(external_session_id,runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id)
      ON DELETE RESTRICT,
    CHECK ((model_call_consumed=0 AND model_call_consumed_at IS NULL)
      OR (model_call_consumed=1 AND model_call_consumed_at IS NOT NULL)),
    CHECK (state NOT IN ('PROMPTING','ACKNOWLEDGED') OR model_call_consumed=1),
    CHECK ((claim_owner IS NULL)=(claim_token IS NULL)),
    CHECK ((claim_owner IS NULL)=(claim_expires_at IS NULL)),
    CHECK (claim_owner IS NULL OR state IN ('PROMPTING','STOPPING','DISCONNECTED')),
    CHECK ((dispatch_attempted=0 AND dispatch_started_at IS NULL)
      OR (dispatch_attempted=1 AND dispatch_started_at IS NOT NULL)),
    CHECK ((acknowledged=0 AND acked_at IS NULL)
      OR (acknowledged=1 AND acked_at IS NOT NULL)),
    CHECK (acknowledged=0 OR dispatch_attempted=1),
    CHECK (state<>'ACKNOWLEDGED' OR acknowledged=1),
    CHECK ((termination_proof IS NULL)=(termination_proof_at IS NULL)),
    CHECK (state<>'STOPPED' OR termination_proof IS NOT NULL),
    CHECK (state<>'CANCELLED'
      OR (dispatch_attempted=0 AND acknowledged=0)
      OR termination_proof IS NOT NULL),
    CHECK ((dispatch_kind='INITIAL' AND source_attempt_ordinal IS NULL)
      OR (dispatch_kind='CORRECTION' AND source_attempt_ordinal IS NOT NULL))
);

CREATE UNIQUE INDEX ux_candidate_prompt_dispatch_initial
    ON ai_candidate_prompt_dispatch(run_id) WHERE dispatch_kind='INITIAL';
CREATE UNIQUE INDEX ux_candidate_prompt_dispatch_correction
    ON ai_candidate_prompt_dispatch(run_id,source_attempt_ordinal) WHERE dispatch_kind='CORRECTION';
CREATE INDEX idx_candidate_prompt_dispatch_active
    ON ai_candidate_prompt_dispatch(state,updated_at,id);
CREATE INDEX idx_candidate_prompt_dispatch_remote
    ON ai_candidate_prompt_dispatch(external_session_id,state,updated_at,id);

CREATE TRIGGER candidate_prompt_dispatch_initial_insert
BEFORE INSERT ON ai_candidate_prompt_dispatch
WHEN NEW.dispatch_kind='INITIAL'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM ai_candidate_submission_run run
    WHERE run.id=NEW.run_id
      AND run.state='OPEN'
      AND run.attempts_used=0
      AND run.submission_channel='INTERNAL_MCP'
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.workflow_step='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.contract_version='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.max_attempts=2
      AND run.external_session_id=NEW.external_session_id
      AND run.runtime_generation_id=NEW.runtime_generation_id
      AND NOT EXISTS (SELECT 1 FROM ai_candidate_submission_attempt attempt
                      WHERE attempt.run_id=run.id)
  ) THEN RAISE(ABORT,'initial candidate prompt requires an open zero-attempt Acceptance v7 INTERNAL_MCP run') END;
END;

CREATE TRIGGER candidate_prompt_dispatch_correction_insert
BEFORE INSERT ON ai_candidate_prompt_dispatch
WHEN NEW.dispatch_kind='CORRECTION'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM ai_candidate_submission_attempt attempt
    WHERE attempt.run_id=NEW.run_id
      AND attempt.ordinal=NEW.source_attempt_ordinal
      AND attempt.outcome='REJECTED'
      AND attempt.retryable=1
  ) THEN RAISE(ABORT,'candidate prompt dispatch source attempt must be retryable REJECTED') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=NEW.run_id
      AND run.state='OPEN'
      AND run.attempts_used=NEW.source_attempt_ordinal
      AND run.external_session_id=NEW.external_session_id
      AND run.runtime_generation_id=NEW.runtime_generation_id
  ) THEN RAISE(ABORT,'candidate prompt dispatch runtime binding mismatch') END;
END;

CREATE TRIGGER candidate_prompt_dispatch_identity_request_immutable
BEFORE UPDATE ON ai_candidate_prompt_dispatch
WHEN NEW.id<>OLD.id
  OR NEW.run_id<>OLD.run_id
  OR NEW.dispatch_kind<>OLD.dispatch_kind
  OR NEW.source_attempt_ordinal IS NOT OLD.source_attempt_ordinal
  OR NEW.external_session_id<>OLD.external_session_id
  OR NEW.runtime_generation_id<>OLD.runtime_generation_id
  OR NEW.message_id<>OLD.message_id
  OR NEW.request_json<>OLD.request_json
  OR NEW.request_sha256<>OLD.request_sha256
  OR NEW.created_at<>OLD.created_at
BEGIN
  SELECT RAISE(ABORT,'candidate prompt dispatch identity and request are immutable');
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

CREATE TRIGGER candidate_prompt_dispatch_model_call_irreversible
BEFORE UPDATE ON ai_candidate_prompt_dispatch
WHEN OLD.model_call_consumed=1 AND (
  NEW.model_call_consumed<>1
  OR NEW.model_call_consumed_at IS NOT OLD.model_call_consumed_at)
BEGIN
  SELECT RAISE(ABORT,'candidate prompt dispatch model call evidence is irreversible');
END;

CREATE TRIGGER candidate_prompt_dispatch_attempt_irreversible
BEFORE UPDATE ON ai_candidate_prompt_dispatch
WHEN OLD.dispatch_attempted=1 AND (
  NEW.dispatch_attempted<>1
  OR NEW.dispatch_started_at IS NOT OLD.dispatch_started_at)
BEGIN
  SELECT RAISE(ABORT,'candidate prompt dispatch attempt evidence is irreversible');
END;

CREATE TRIGGER candidate_prompt_dispatch_acknowledgement_irreversible
BEFORE UPDATE ON ai_candidate_prompt_dispatch
WHEN OLD.acknowledged=1 AND (
  NEW.acknowledged<>1
  OR NEW.acked_at IS NOT OLD.acked_at)
BEGIN
  SELECT RAISE(ABORT,'candidate prompt dispatch acknowledgement evidence is irreversible');
END;

CREATE TRIGGER candidate_prompt_dispatch_termination_proof_irreversible
BEFORE UPDATE ON ai_candidate_prompt_dispatch
WHEN OLD.termination_proof IS NOT NULL AND (
  NEW.termination_proof IS NOT OLD.termination_proof
  OR NEW.termination_proof_at IS NOT OLD.termination_proof_at)
BEGIN
  SELECT RAISE(ABORT,'candidate prompt dispatch termination proof is irreversible');
END;
