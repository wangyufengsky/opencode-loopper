-- Generic durable internal-MCP launch protocol for the reserved Reviewer,
-- project-convention and Judge candidate kinds. Acceptance V51-V55 remains a
-- separate historical protocol; no Acceptance row is copied or reinterpreted.
CREATE TABLE ai_candidate_internal_launch (
    id TEXT PRIMARY KEY,
    candidate_run_id TEXT NOT NULL UNIQUE,
    candidate_kind TEXT NOT NULL CHECK (candidate_kind IN (
      'REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')),
    designer_session_id TEXT REFERENCES designer_session(id) ON DELETE RESTRICT,
    task_id TEXT REFERENCES task(id) ON DELETE RESTRICT,
    project_id TEXT REFERENCES project(id) ON DELETE RESTRICT,
    owner_type TEXT NOT NULL CHECK (owner_type IN (
      'ANALYSIS_REPORT','PROJECT_CONVENTION_DRAFT','JUDGE_RUN')),
    owner_id TEXT NOT NULL CHECK (length(owner_id)>0),
    analysis_report_id TEXT REFERENCES analysis_report(id) ON DELETE RESTRICT,
    project_convention_draft_id TEXT REFERENCES project_convention_draft(id) ON DELETE RESTRICT,
    judge_run_id TEXT REFERENCES judge_run(id) ON DELETE RESTRICT,
    workflow_step TEXT NOT NULL CHECK (length(workflow_step)>0),
    source_revision INTEGER NOT NULL CHECK (source_revision>=0),
    contract_version TEXT NOT NULL CHECK (length(contract_version)>0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 3),
    state TEXT NOT NULL CHECK (state IN (
      'PREPARED','CREATING','CREATED','DISCONNECTED','STOPPING',
      'SETTLED','FAILED_STOPPED','CANCELLED','STALE')),
    prepared_owner_version INTEGER NOT NULL CHECK (prepared_owner_version>=0),
    settled_owner_version INTEGER CHECK (settled_owner_version>=0),
    settled_at TEXT,
    exact_title TEXT NOT NULL UNIQUE CHECK (length(trim(exact_title))>0),
    canonical_directory TEXT NOT NULL CHECK (length(trim(canonical_directory))>0),
    runtime_generation_id TEXT NOT NULL CHECK (length(trim(runtime_generation_id))>0),
    managed INTEGER NOT NULL CHECK (managed=1),
    internal_mcp_server TEXT NOT NULL CHECK (length(trim(internal_mcp_server))>0),
    endpoint_fingerprint TEXT NOT NULL CHECK (length(endpoint_fingerprint)=64),
    model_provider_id TEXT,
    model_id TEXT,
    thinking INTEGER CHECK (thinking IS NULL OR thinking IN (0,1)),
    profile TEXT NOT NULL CHECK (length(trim(profile))>0),
    permission_policy_json TEXT NOT NULL CHECK (
      json_valid(permission_policy_json) AND json_type(permission_policy_json)='array'),
    permission_policy_digest TEXT NOT NULL CHECK (length(permission_policy_digest)=64),
    create_request_sha256 TEXT NOT NULL CHECK (length(create_request_sha256)=64),
    creation_credential TEXT NOT NULL UNIQUE CHECK (
      length(creation_credential)=43 AND creation_credential NOT GLOB '*[^A-Za-z0-9_-]*'),
    attestation_type TEXT NOT NULL CHECK (attestation_type='LOCAL_REQUEST_ATTESTED'),
    create_claim_owner TEXT,
    create_claim_token TEXT,
    create_claim_expires_at TEXT,
    create_fence INTEGER NOT NULL DEFAULT 0 CHECK (create_fence>=0),
    create_dispatch_attempted INTEGER NOT NULL DEFAULT 0 CHECK (create_dispatch_attempted IN (0,1)),
    create_dispatch_started_at TEXT,
    external_session_id TEXT,
    external_attested_at TEXT,
    termination_proof TEXT CHECK (termination_proof IS NULL OR termination_proof IN (
      'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    proof_at TEXT,
    failure_phase TEXT CHECK (failure_phase IS NULL OR failure_phase IN (
      'CREATE_LOOKUP','CREATE_POST','REMOTE_ATTESTATION','REMOTE_STOP',
      'OWNER_REVALIDATION','SETTLEMENT')),
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version>=0),
    FOREIGN KEY(external_session_id,runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id)
      ON DELETE RESTRICT,
    CHECK ((designer_session_id IS NOT NULL)+(task_id IS NOT NULL)+(project_id IS NOT NULL)=1),
    CHECK ((analysis_report_id IS NOT NULL)+(project_convention_draft_id IS NOT NULL)
      +(judge_run_id IS NOT NULL)=1),
    CHECK (
      (candidate_kind='REVIEWER_REPORT_V1' AND owner_type='ANALYSIS_REPORT'
        AND workflow_step='REVIEWER_REPORT_V1' AND contract_version='REVIEWER_REPORT_V1'
        AND designer_session_id IS NOT NULL AND task_id IS NULL AND project_id IS NULL
        AND analysis_report_id IS NOT NULL AND owner_id=analysis_report_id
        AND project_convention_draft_id IS NULL AND judge_run_id IS NULL AND max_attempts=3)
      OR
      (candidate_kind='PROJECT_CONVENTION_V1' AND owner_type='PROJECT_CONVENTION_DRAFT'
        AND workflow_step='PROJECT_CONVENTION_V1' AND contract_version='PROJECT_CONVENTION_V1'
        AND project_id IS NOT NULL AND designer_session_id IS NULL AND task_id IS NULL
        AND project_convention_draft_id IS NOT NULL AND owner_id=project_convention_draft_id
        AND analysis_report_id IS NULL AND judge_run_id IS NULL AND max_attempts=3)
      OR
      (candidate_kind='JUDGE_DECISION_V1' AND owner_type='JUDGE_RUN'
        AND workflow_step='JUDGE_DECISION_V1' AND contract_version='JUDGE_DECISION_V1'
        AND task_id IS NOT NULL AND designer_session_id IS NULL AND project_id IS NULL
        AND judge_run_id IS NOT NULL AND owner_id=judge_run_id
        AND analysis_report_id IS NULL AND project_convention_draft_id IS NULL AND max_attempts=2)),
    CHECK ((model_provider_id IS NULL)=(model_id IS NULL)),
    CHECK ((settled_owner_version IS NULL)=(settled_at IS NULL)),
    CHECK ((external_session_id IS NULL)=(external_attested_at IS NULL)),
    CHECK ((termination_proof IS NULL)=(proof_at IS NULL)),
    CHECK ((create_claim_owner IS NULL)=(create_claim_token IS NULL)),
    CHECK ((create_claim_owner IS NULL)=(create_claim_expires_at IS NULL)),
    CHECK ((create_dispatch_attempted=0 AND create_dispatch_started_at IS NULL)
      OR (create_dispatch_attempted=1 AND create_dispatch_started_at IS NOT NULL)),
    CHECK (external_session_id IS NULL OR create_dispatch_attempted=1),
    CHECK (termination_proof IS NULL OR external_session_id IS NOT NULL),
    CHECK (create_claim_owner IS NULL OR state IN (
      'PREPARED','CREATING','CREATED','DISCONNECTED','STOPPING')),
    CHECK (state<>'PREPARED' OR (create_dispatch_attempted=0 AND external_session_id IS NULL
      AND settled_owner_version IS NULL AND termination_proof IS NULL)),
    CHECK (state<>'CREATING' OR (create_dispatch_attempted=1 AND create_claim_owner IS NOT NULL)),
    CHECK (state<>'CREATED' OR (create_dispatch_attempted=1 AND create_claim_owner IS NOT NULL
      AND external_session_id IS NOT NULL)),
    CHECK (state<>'SETTLED' OR (external_session_id IS NOT NULL
      AND settled_owner_version=prepared_owner_version+1 AND termination_proof IS NULL
      AND create_claim_owner IS NULL)),
    CHECK (state='SETTLED' OR settled_owner_version IS NULL),
    CHECK (state NOT IN ('FAILED_STOPPED','CANCELLED','STALE')
      OR external_session_id IS NULL OR termination_proof IS NOT NULL),
    CHECK (termination_proof IS NULL OR state IN ('FAILED_STOPPED','CANCELLED','STALE')),
    CHECK (state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
      OR create_claim_owner IS NULL)
);

CREATE UNIQUE INDEX ux_ai_candidate_internal_launch_remote
  ON ai_candidate_internal_launch(external_session_id) WHERE external_session_id IS NOT NULL;
CREATE UNIQUE INDEX ux_ai_candidate_internal_launch_active_owner
  ON ai_candidate_internal_launch(owner_type,owner_id,workflow_step)
  WHERE state IN ('PREPARED','CREATING','CREATED','DISCONNECTED','STOPPING','SETTLED');
CREATE INDEX idx_ai_candidate_internal_launch_recovery
  ON ai_candidate_internal_launch(state,updated_at,id);

CREATE TABLE ai_candidate_internal_launch_cleanup_remote (
    launch_id TEXT NOT NULL REFERENCES ai_candidate_internal_launch(id) ON DELETE RESTRICT,
    external_session_id TEXT NOT NULL,
    runtime_generation_id TEXT NOT NULL,
    endpoint_fingerprint TEXT NOT NULL CHECK (length(endpoint_fingerprint)=64),
    directory_sha256 TEXT NOT NULL CHECK (length(directory_sha256)=64),
    title_sha256 TEXT NOT NULL CHECK (length(title_sha256)=64),
    purpose TEXT NOT NULL CHECK (purpose IN ('LAUNCH_AMBIGUITY','TERMINATION')),
    state TEXT NOT NULL CHECK (state IN ('DISCOVERED','STOPPING','STOPPED','DISCONNECTED')),
    termination_proof TEXT CHECK (termination_proof IS NULL OR termination_proof IN (
      'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    proof_at TEXT,
    stop_claim_owner TEXT,
    stop_claim_token TEXT,
    stop_claim_expires_at TEXT,
    stop_fence INTEGER NOT NULL DEFAULT 0 CHECK (stop_fence>=0),
    stop_dispatch_attempted INTEGER NOT NULL DEFAULT 0 CHECK (stop_dispatch_attempted IN (0,1)),
    stop_dispatch_started_at TEXT,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version>=0),
    PRIMARY KEY(launch_id,external_session_id),
    FOREIGN KEY(external_session_id,runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id)
      ON DELETE RESTRICT,
    CHECK ((termination_proof IS NULL)=(proof_at IS NULL)),
    CHECK ((state='STOPPED')=(termination_proof IS NOT NULL)),
    CHECK ((stop_claim_owner IS NULL)=(stop_claim_token IS NULL)),
    CHECK ((stop_claim_owner IS NULL)=(stop_claim_expires_at IS NULL)),
    CHECK ((stop_dispatch_attempted=0 AND stop_dispatch_started_at IS NULL)
      OR (stop_dispatch_attempted=1 AND stop_dispatch_started_at IS NOT NULL)),
    CHECK (state<>'STOPPING' OR stop_claim_owner IS NOT NULL)
);

CREATE INDEX idx_ai_candidate_internal_cleanup_active
  ON ai_candidate_internal_launch_cleanup_remote(launch_id,state,external_session_id);

CREATE TABLE ai_candidate_internal_launch_settlement_certificate (
    launch_id TEXT NOT NULL,
    candidate_run_id TEXT NOT NULL,
    settled_owner_version INTEGER NOT NULL CHECK (settled_owner_version>=0),
    settled_at TEXT NOT NULL,
    PRIMARY KEY(launch_id,candidate_run_id),
    UNIQUE(launch_id),
    UNIQUE(candidate_run_id),
    FOREIGN KEY(launch_id) REFERENCES ai_candidate_internal_launch(id) ON DELETE RESTRICT,
    FOREIGN KEY(candidate_run_id) REFERENCES ai_candidate_submission_run(id) ON DELETE RESTRICT
);

CREATE TABLE ai_candidate_internal_launch_run_requirement (
    candidate_run_id TEXT PRIMARY KEY,
    launch_id TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    FOREIGN KEY(candidate_run_id) REFERENCES ai_candidate_submission_run(id) ON DELETE RESTRICT,
    FOREIGN KEY(launch_id) REFERENCES ai_candidate_internal_launch(id) ON DELETE RESTRICT,
    FOREIGN KEY(launch_id,candidate_run_id)
      REFERENCES ai_candidate_internal_launch_settlement_certificate(launch_id,candidate_run_id)
      ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE ai_candidate_internal_termination_intent (
    id TEXT PRIMARY KEY,
    launch_id TEXT NOT NULL UNIQUE REFERENCES ai_candidate_internal_launch(id) ON DELETE RESTRICT,
    candidate_run_id TEXT NOT NULL UNIQUE,
    intent_kind TEXT NOT NULL CHECK (intent_kind IN (
      'OWNER_CANCEL','OWNER_REPLACEMENT','PROTOCOL_FAILURE')),
    target_launch_state TEXT NOT NULL CHECK (target_launch_state IN (
      'FAILED_STOPPED','CANCELLED','STALE')),
    state TEXT NOT NULL CHECK (state IN ('REQUESTED','DISCONNECTED','READY','COMPLETED')),
    reason_code TEXT,
    anchor_owner_version INTEGER NOT NULL CHECK (anchor_owner_version>=0),
    ready_at TEXT,
    completed_at TEXT,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version>=0),
    CHECK ((state IN ('READY','COMPLETED'))=(ready_at IS NOT NULL)),
    CHECK ((state='COMPLETED')=(completed_at IS NOT NULL))
);

CREATE INDEX idx_ai_candidate_internal_termination_state
  ON ai_candidate_internal_termination_intent(state,updated_at,id);

ALTER TABLE ai_candidate_prompt_dispatch
  ADD COLUMN candidate_launch_id TEXT
    REFERENCES ai_candidate_internal_launch(id) ON DELETE RESTRICT;

CREATE INDEX idx_candidate_prompt_dispatch_candidate_launch
  ON ai_candidate_prompt_dispatch(candidate_launch_id,state,id);

CREATE TRIGGER ai_candidate_internal_launch_owner_anchor_insert
BEFORE INSERT ON ai_candidate_internal_launch
BEGIN
  SELECT CASE
    WHEN NEW.candidate_kind='REVIEWER_REPORT_V1' AND NOT EXISTS (
      SELECT 1 FROM analysis_report owner
      WHERE owner.id=NEW.analysis_report_id
        AND owner.designer_session_id=NEW.designer_session_id
        AND owner.state='RUNNING' AND owner.external_session_id IS NULL
        AND owner.version=NEW.prepared_owner_version)
      THEN RAISE(ABORT,'generic candidate launch Reviewer owner anchor mismatch')
    WHEN NEW.candidate_kind='PROJECT_CONVENTION_V1' AND NOT EXISTS (
      SELECT 1 FROM project_convention_draft owner
      WHERE owner.id=NEW.project_convention_draft_id
        AND owner.project_id=NEW.project_id
        AND owner.state='RUNNING' AND owner.external_session_id IS NULL
        AND owner.version=NEW.prepared_owner_version)
      THEN RAISE(ABORT,'generic candidate launch Convention owner anchor mismatch')
    WHEN NEW.candidate_kind='JUDGE_DECISION_V1' AND NOT EXISTS (
      SELECT 1 FROM judge_run owner
      WHERE owner.id=NEW.judge_run_id AND owner.task_id=NEW.task_id
        AND owner.state='CREATING' AND owner.external_session_id IS NULL
        AND owner.version=NEW.prepared_owner_version)
      THEN RAISE(ABORT,'generic candidate launch Judge owner anchor mismatch')
  END;
END;

CREATE TRIGGER ai_candidate_internal_launch_identity_immutable
BEFORE UPDATE ON ai_candidate_internal_launch
WHEN NEW.id<>OLD.id OR NEW.candidate_run_id<>OLD.candidate_run_id
  OR NEW.candidate_kind<>OLD.candidate_kind
  OR NEW.designer_session_id IS NOT OLD.designer_session_id
  OR NEW.task_id IS NOT OLD.task_id OR NEW.project_id IS NOT OLD.project_id
  OR NEW.owner_type<>OLD.owner_type OR NEW.owner_id<>OLD.owner_id
  OR NEW.analysis_report_id IS NOT OLD.analysis_report_id
  OR NEW.project_convention_draft_id IS NOT OLD.project_convention_draft_id
  OR NEW.judge_run_id IS NOT OLD.judge_run_id
  OR NEW.workflow_step<>OLD.workflow_step OR NEW.source_revision<>OLD.source_revision
  OR NEW.contract_version<>OLD.contract_version OR NEW.max_attempts<>OLD.max_attempts
  OR NEW.prepared_owner_version<>OLD.prepared_owner_version
  OR NEW.exact_title<>OLD.exact_title OR NEW.canonical_directory<>OLD.canonical_directory
  OR NEW.runtime_generation_id<>OLD.runtime_generation_id OR NEW.managed<>OLD.managed
  OR NEW.internal_mcp_server<>OLD.internal_mcp_server
  OR NEW.endpoint_fingerprint<>OLD.endpoint_fingerprint
  OR NEW.model_provider_id IS NOT OLD.model_provider_id OR NEW.model_id IS NOT OLD.model_id
  OR NEW.thinking IS NOT OLD.thinking OR NEW.profile<>OLD.profile
  OR NEW.permission_policy_json<>OLD.permission_policy_json
  OR NEW.permission_policy_digest<>OLD.permission_policy_digest
  OR NEW.create_request_sha256<>OLD.create_request_sha256
  OR NEW.creation_credential<>OLD.creation_credential
  OR NEW.attestation_type<>OLD.attestation_type OR NEW.created_at<>OLD.created_at
BEGIN
  SELECT RAISE(ABORT,'generic candidate launch identity and plan are immutable');
END;

CREATE TRIGGER ai_candidate_internal_launch_remote_attestation
BEFORE UPDATE ON ai_candidate_internal_launch
WHEN OLD.external_session_id IS NULL AND NEW.external_session_id IS NOT NULL
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM open_code_session_runtime_binding binding
    WHERE binding.external_session_id=NEW.external_session_id
      AND binding.runtime_generation_id=NEW.runtime_generation_id
      AND binding.ownership_mode='MANAGED'
      AND binding.endpoint_fingerprint=NEW.endpoint_fingerprint
      AND binding.internal_mcp_server=NEW.internal_mcp_server)
    THEN RAISE(ABORT,'generic candidate launch remote attestation mismatch') END;
END;

CREATE TRIGGER ai_candidate_internal_launch_irreversible_evidence
BEFORE UPDATE ON ai_candidate_internal_launch
WHEN (OLD.create_dispatch_attempted=1 AND (
       NEW.create_dispatch_attempted<>1 OR NEW.create_dispatch_started_at IS NOT OLD.create_dispatch_started_at))
  OR (OLD.external_session_id IS NOT NULL AND (
       NEW.external_session_id IS NOT OLD.external_session_id
       OR NEW.external_attested_at IS NOT OLD.external_attested_at))
  OR (OLD.termination_proof IS NOT NULL AND (
       NEW.termination_proof IS NOT OLD.termination_proof OR NEW.proof_at IS NOT OLD.proof_at))
  OR (OLD.settled_at IS NOT NULL AND (
       NEW.settled_at IS NOT OLD.settled_at
       OR NEW.settled_owner_version IS NOT OLD.settled_owner_version))
  OR NEW.create_fence<OLD.create_fence
BEGIN
  SELECT RAISE(ABORT,'generic candidate launch durable evidence is irreversible');
END;

CREATE TRIGGER ai_candidate_internal_launch_cleanup_insert
BEFORE INSERT ON ai_candidate_internal_launch_cleanup_remote
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch launch
    WHERE launch.id=NEW.launch_id
      AND (launch.state IN ('CREATING','CREATED','DISCONNECTED','STOPPING')
        OR (launch.state='SETTLED' AND NEW.purpose='TERMINATION' AND EXISTS (
          SELECT 1 FROM ai_candidate_internal_termination_intent intent
          WHERE intent.launch_id=launch.id AND intent.state<>'COMPLETED'))))
    THEN RAISE(ABORT,'generic candidate cleanup parent is not accepting registrations') END;
END;

CREATE TRIGGER ai_candidate_internal_cleanup_immutable
BEFORE UPDATE ON ai_candidate_internal_launch_cleanup_remote
WHEN NEW.launch_id<>OLD.launch_id OR NEW.external_session_id<>OLD.external_session_id
  OR NEW.runtime_generation_id<>OLD.runtime_generation_id
  OR NEW.endpoint_fingerprint<>OLD.endpoint_fingerprint
  OR NEW.directory_sha256<>OLD.directory_sha256 OR NEW.title_sha256<>OLD.title_sha256
  OR NEW.purpose<>OLD.purpose OR NEW.created_at<>OLD.created_at
  OR NEW.stop_fence<OLD.stop_fence
  OR (OLD.stop_dispatch_attempted=1 AND (
       NEW.stop_dispatch_attempted<>1 OR NEW.stop_dispatch_started_at IS NOT OLD.stop_dispatch_started_at))
  OR (OLD.termination_proof IS NOT NULL AND (
       NEW.termination_proof IS NOT OLD.termination_proof OR NEW.proof_at IS NOT OLD.proof_at))
BEGIN
  SELECT RAISE(ABORT,'generic candidate cleanup durable evidence is immutable');
END;

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

CREATE TRIGGER ai_candidate_internal_launch_settlement_gate
BEFORE UPDATE OF state ON ai_candidate_internal_launch
WHEN NEW.state='SETTLED' AND OLD.state<>'SETTLED'
BEGIN
  SELECT CASE WHEN OLD.state<>'CREATED'
    THEN RAISE(ABORT,'generic candidate launch settlement requires CREATED') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup WHERE cleanup.launch_id=NEW.id)
    THEN RAISE(ABORT,'generic candidate launch settlement forbids cleanup remotes') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_internal_termination_intent intent
    WHERE intent.launch_id=NEW.id AND intent.state<>'COMPLETED')
    THEN RAISE(ABORT,'generic candidate launch settlement blocked by termination intent') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    JOIN ai_candidate_internal_launch_run_requirement requirement
      ON requirement.candidate_run_id=run.id AND requirement.launch_id=NEW.id
    WHERE run.id=NEW.candidate_run_id AND run.candidate_kind=NEW.candidate_kind
      AND run.owner_type=NEW.owner_type AND run.owner_id=NEW.owner_id
      AND run.designer_session_id IS NEW.designer_session_id
      AND run.task_id IS NEW.task_id AND run.project_id IS NEW.project_id
      AND run.workflow_step=NEW.workflow_step AND run.source_revision=NEW.source_revision
      AND run.owner_version=NEW.settled_owner_version
      AND run.submission_channel='INTERNAL_MCP' AND run.contract_version=NEW.contract_version
      AND run.runtime_generation_id=NEW.runtime_generation_id
      AND run.external_session_id=NEW.external_session_id
      AND run.state='OPEN' AND run.max_attempts=NEW.max_attempts AND run.attempts_used=0
      AND NEW.settled_owner_version=NEW.prepared_owner_version+1)
    THEN RAISE(ABORT,'generic candidate launch settlement requires its exact OPEN run') END;
END;

CREATE TRIGGER ai_candidate_internal_launch_settlement_certificate_insert
AFTER UPDATE OF state ON ai_candidate_internal_launch
WHEN NEW.state='SETTLED' AND OLD.state<>'SETTLED'
BEGIN
  INSERT INTO ai_candidate_internal_launch_settlement_certificate(
    launch_id,candidate_run_id,settled_owner_version,settled_at)
  VALUES(NEW.id,NEW.candidate_run_id,NEW.settled_owner_version,NEW.settled_at);
END;

CREATE TRIGGER ai_candidate_internal_requirement_insert_gate
BEFORE INSERT ON ai_candidate_internal_launch_run_requirement
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    JOIN ai_candidate_internal_launch launch
      ON launch.id=NEW.launch_id AND launch.candidate_run_id=run.id
    WHERE run.id=NEW.candidate_run_id AND run.candidate_kind=launch.candidate_kind
      AND run.submission_channel='INTERNAL_MCP' AND run.state='OPEN' AND launch.state='CREATED')
    THEN RAISE(ABORT,'generic candidate launch run requirement cannot be forged') END;
END;

CREATE TRIGGER ai_candidate_internal_certificate_insert_gate
BEFORE INSERT ON ai_candidate_internal_launch_settlement_certificate
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch launch
    JOIN ai_candidate_submission_run run ON run.id=NEW.candidate_run_id
    JOIN ai_candidate_internal_launch_run_requirement requirement
      ON requirement.launch_id=launch.id AND requirement.candidate_run_id=run.id
    WHERE launch.id=NEW.launch_id AND launch.candidate_run_id=run.id
      AND launch.state='SETTLED' AND launch.settled_owner_version=NEW.settled_owner_version
      AND launch.settled_at=NEW.settled_at AND run.state='OPEN')
    THEN RAISE(ABORT,'generic candidate launch settlement certificate cannot be forged') END;
END;

CREATE TRIGGER ai_candidate_internal_requirement_immutable
BEFORE UPDATE ON ai_candidate_internal_launch_run_requirement
BEGIN SELECT RAISE(ABORT,'generic candidate launch run requirement is immutable'); END;
CREATE TRIGGER ai_candidate_internal_requirement_delete
BEFORE DELETE ON ai_candidate_internal_launch_run_requirement
BEGIN SELECT RAISE(ABORT,'generic candidate launch run requirement cannot be deleted'); END;
CREATE TRIGGER ai_candidate_internal_certificate_immutable
BEFORE UPDATE ON ai_candidate_internal_launch_settlement_certificate
BEGIN SELECT RAISE(ABORT,'generic candidate launch settlement certificate is immutable'); END;
CREATE TRIGGER ai_candidate_internal_certificate_delete
BEFORE DELETE ON ai_candidate_internal_launch_settlement_certificate
BEGIN SELECT RAISE(ABORT,'generic candidate launch settlement certificate cannot be deleted'); END;

CREATE TRIGGER ai_candidate_prompt_launch_reference_insert
BEFORE INSERT ON ai_candidate_prompt_dispatch
BEGIN
  SELECT CASE WHEN NEW.internal_launch_id IS NOT NULL AND NEW.candidate_launch_id IS NOT NULL
    THEN RAISE(ABORT,'candidate prompt launch references are mutually exclusive') END;
  SELECT CASE WHEN NEW.candidate_launch_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run WHERE run.id=NEW.run_id
      AND run.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
      AND run.submission_channel='INTERNAL_MCP')
    THEN RAISE(ABORT,'candidate_launch_id is reserved for generic V1 INTERNAL_MCP prompts') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run WHERE run.id=NEW.run_id
      AND run.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
      AND run.submission_channel='INTERNAL_MCP')
    AND NOT EXISTS (
      SELECT 1 FROM ai_candidate_submission_run run
      JOIN ai_candidate_internal_launch launch ON launch.id=NEW.candidate_launch_id
      JOIN ai_candidate_internal_launch_settlement_certificate certificate
        ON certificate.launch_id=launch.id AND certificate.candidate_run_id=run.id
      JOIN ai_candidate_internal_launch_run_requirement requirement
        ON requirement.launch_id=launch.id AND requirement.candidate_run_id=run.id
      WHERE run.id=NEW.run_id AND run.state='OPEN' AND run.submission_channel='INTERNAL_MCP'
        AND launch.state='SETTLED' AND launch.candidate_run_id=run.id
        AND launch.candidate_kind=run.candidate_kind AND launch.owner_type=run.owner_type
        AND launch.owner_id=run.owner_id
        AND launch.external_session_id=NEW.external_session_id
        AND launch.external_session_id=run.external_session_id
        AND launch.runtime_generation_id=NEW.runtime_generation_id
        AND launch.runtime_generation_id=run.runtime_generation_id)
    THEN RAISE(ABORT,'generic candidate prompt requires its exact SETTLED launch') END;
  SELECT CASE WHEN NEW.dispatch_kind='CORRECTION' AND NEW.candidate_launch_id IS NOT NULL
    AND NOT EXISTS (
      SELECT 1 FROM ai_candidate_prompt_dispatch initial
      WHERE initial.run_id=NEW.run_id AND initial.dispatch_kind='INITIAL'
        AND initial.candidate_launch_id=NEW.candidate_launch_id
        AND initial.state='ACKNOWLEDGED' AND initial.acknowledged=1
        AND initial.external_session_id=NEW.external_session_id
        AND initial.runtime_generation_id=NEW.runtime_generation_id)
    THEN RAISE(ABORT,'generic candidate correction requires acknowledged INITIAL with same launch') END;
  SELECT CASE WHEN NEW.candidate_launch_id IS NOT NULL AND EXISTS (
    SELECT 1 FROM ai_candidate_internal_termination_intent intent
    WHERE intent.launch_id=NEW.candidate_launch_id AND intent.state<>'COMPLETED')
    THEN RAISE(ABORT,'generic candidate prompt blocked by termination intent') END;
END;

CREATE TRIGGER ai_candidate_prompt_launch_reference_update
BEFORE UPDATE OF internal_launch_id,candidate_launch_id ON ai_candidate_prompt_dispatch
WHEN NEW.internal_launch_id IS NOT OLD.internal_launch_id
  OR NEW.candidate_launch_id IS NOT OLD.candidate_launch_id
BEGIN
  SELECT RAISE(ABORT,'candidate prompt launch reference is immutable');
END;

DROP TRIGGER candidate_prompt_dispatch_initial_insert;
CREATE TRIGGER candidate_prompt_dispatch_initial_insert
BEFORE INSERT ON ai_candidate_prompt_dispatch
WHEN NEW.dispatch_kind='INITIAL'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=NEW.run_id AND run.state='OPEN' AND run.attempts_used=0
      AND run.submission_channel='INTERNAL_MCP'
      AND run.external_session_id=NEW.external_session_id
      AND run.runtime_generation_id=NEW.runtime_generation_id
      AND NOT EXISTS (SELECT 1 FROM ai_candidate_submission_attempt attempt WHERE attempt.run_id=run.id)
      AND (
        (run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
          AND run.workflow_step='ACCEPTANCE_CLOSED_CHOICE_V7'
          AND run.contract_version='ACCEPTANCE_CLOSED_CHOICE_V7' AND run.max_attempts=2)
        OR
        (run.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
          AND ((run.candidate_kind='JUDGE_DECISION_V1' AND run.max_attempts=2)
            OR (run.candidate_kind<>'JUDGE_DECISION_V1' AND run.max_attempts=3)))))
    THEN RAISE(ABORT,'initial candidate prompt requires an exact open zero-attempt INTERNAL_MCP run') END;
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

CREATE TRIGGER ai_candidate_internal_termination_insert
BEFORE INSERT ON ai_candidate_internal_termination_intent
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch launch
    WHERE launch.id=NEW.launch_id AND launch.candidate_run_id=NEW.candidate_run_id
      AND launch.prepared_owner_version<=NEW.anchor_owner_version
      AND launch.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE'))
    THEN RAISE(ABORT,'generic candidate termination intent launch anchor mismatch') END;
END;

CREATE TRIGGER ai_candidate_internal_termination_identity_immutable
BEFORE UPDATE ON ai_candidate_internal_termination_intent
WHEN NEW.id<>OLD.id OR NEW.launch_id<>OLD.launch_id
  OR NEW.candidate_run_id<>OLD.candidate_run_id OR NEW.intent_kind<>OLD.intent_kind
  OR NEW.target_launch_state<>OLD.target_launch_state
  OR NEW.anchor_owner_version<>OLD.anchor_owner_version OR NEW.created_at<>OLD.created_at
  OR (OLD.ready_at IS NOT NULL AND NEW.ready_at IS NOT OLD.ready_at)
  OR (OLD.completed_at IS NOT NULL AND NEW.completed_at IS NOT OLD.completed_at)
BEGIN
  SELECT RAISE(ABORT,'generic candidate termination intent identity is immutable');
END;

CREATE TRIGGER ai_candidate_internal_termination_ready_gate
BEFORE UPDATE OF state ON ai_candidate_internal_termination_intent
WHEN NEW.state IN ('READY','COMPLETED') AND OLD.state<>NEW.state
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch launch
    WHERE launch.id=NEW.launch_id
      AND launch.state IN (NEW.target_launch_state,'FAILED_STOPPED'))
    THEN RAISE(ABORT,'generic candidate termination requires terminal launch') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=NEW.candidate_run_id AND run.state='OPEN')
    THEN RAISE(ABORT,'generic candidate termination requires closed run') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    WHERE dispatch.candidate_launch_id=NEW.launch_id
      AND dispatch.state NOT IN ('STOPPED','CANCELLED'))
    THEN RAISE(ABORT,'generic candidate termination requires quiet prompts') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=NEW.launch_id AND cleanup.state<>'STOPPED')
    THEN RAISE(ABORT,'generic candidate termination requires stopped cleanup remotes') END;
END;

CREATE TRIGGER ai_candidate_internal_termination_no_delete
BEFORE DELETE ON ai_candidate_internal_termination_intent
BEGIN SELECT RAISE(ABORT,'generic candidate termination intent cannot be deleted'); END;

CREATE TRIGGER ai_candidate_internal_active_termination_launch_gate
BEFORE UPDATE OF state ON ai_candidate_internal_launch
WHEN NEW.state IN ('CREATING','CREATED','SETTLED') AND EXISTS (
  SELECT 1 FROM ai_candidate_internal_termination_intent intent
  WHERE intent.launch_id=OLD.id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'generic candidate launch progress blocked by termination intent'); END;

CREATE TRIGGER ai_candidate_internal_active_termination_attempt_gate
BEFORE INSERT ON ai_candidate_submission_attempt
WHEN EXISTS (
  SELECT 1 FROM ai_candidate_internal_launch launch
  JOIN ai_candidate_internal_termination_intent intent ON intent.launch_id=launch.id
  WHERE launch.candidate_run_id=NEW.run_id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'generic candidate submission blocked by termination intent'); END;

CREATE TRIGGER ai_candidate_internal_active_termination_prompt_progress_gate
BEFORE UPDATE ON ai_candidate_prompt_dispatch
WHEN NEW.candidate_launch_id IS NOT NULL
  AND (NEW.model_call_consumed>OLD.model_call_consumed
    OR NEW.dispatch_attempted>OLD.dispatch_attempted OR NEW.acknowledged>OLD.acknowledged
    OR NEW.state IN ('PROMPTING','ACKNOWLEDGED'))
  AND EXISTS (SELECT 1 FROM ai_candidate_internal_termination_intent intent
              WHERE intent.launch_id=NEW.candidate_launch_id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'generic candidate prompt progress blocked by termination intent'); END;

CREATE TRIGGER ai_candidate_internal_launch_terminal_gate
BEFORE UPDATE OF state ON ai_candidate_internal_launch
WHEN NEW.state IN ('FAILED_STOPPED','CANCELLED','STALE')
BEGIN
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=NEW.candidate_run_id AND run.state='OPEN')
    THEN RAISE(ABORT,'generic candidate terminal launch requires closed run') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    WHERE dispatch.candidate_launch_id=NEW.id AND dispatch.state NOT IN ('STOPPED','CANCELLED'))
    THEN RAISE(ABORT,'generic candidate terminal launch requires quiet prompts') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=NEW.id AND cleanup.state<>'STOPPED')
    THEN RAISE(ABORT,'generic candidate terminal launch requires stopped cleanup remotes') END;
END;

CREATE TRIGGER ai_candidate_internal_launch_live_delete
BEFORE DELETE ON ai_candidate_internal_launch
WHEN OLD.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE')
  OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
             WHERE run.id=OLD.candidate_run_id AND run.state='OPEN')
  OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
             WHERE dispatch.candidate_launch_id=OLD.id
               AND dispatch.state NOT IN ('STOPPED','CANCELLED'))
  OR EXISTS (SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
             WHERE cleanup.launch_id=OLD.id AND cleanup.state<>'STOPPED')
BEGIN SELECT RAISE(ABORT,'live generic candidate launch cannot be deleted'); END;

CREATE TRIGGER ai_candidate_internal_cleanup_live_delete
BEFORE DELETE ON ai_candidate_internal_launch_cleanup_remote
WHEN OLD.state<>'STOPPED'
BEGIN SELECT RAISE(ABORT,'live generic candidate cleanup cannot be deleted'); END;

CREATE TRIGGER ai_candidate_internal_run_live_delete
BEFORE DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind IN ('REVIEWER_REPORT_V1','PROJECT_CONVENTION_V1','JUDGE_DECISION_V1')
  AND OLD.submission_channel='INTERNAL_MCP'
  AND (OLD.state='OPEN' OR EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    WHERE dispatch.run_id=OLD.id AND dispatch.state NOT IN ('STOPPED','CANCELLED')))
BEGIN SELECT RAISE(ABORT,'live generic candidate run cannot be deleted'); END;

CREATE TRIGGER ai_candidate_internal_report_terminal_guard
BEFORE UPDATE OF state ON analysis_report
WHEN NEW.state IN ('READY','FAILED','SUPERSEDED') AND EXISTS (
  SELECT 1 FROM ai_candidate_internal_launch launch
  WHERE launch.analysis_report_id=OLD.id
    AND (launch.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
      OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
                 WHERE run.id=launch.candidate_run_id AND run.state='OPEN')
      OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
                 WHERE dispatch.candidate_launch_id=launch.id
                   AND dispatch.state NOT IN ('STOPPED','CANCELLED'))))
BEGIN SELECT RAISE(ABORT,'Reviewer owner terminal state has live generic candidate protocol'); END;

CREATE TRIGGER ai_candidate_internal_convention_terminal_guard
BEFORE UPDATE OF state ON project_convention_draft
WHEN NEW.state IN ('READY','APPLIED','FAILED','CANCELLED') AND EXISTS (
  SELECT 1 FROM ai_candidate_internal_launch launch
  WHERE launch.project_convention_draft_id=OLD.id
    AND (launch.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
      OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
                 WHERE run.id=launch.candidate_run_id AND run.state='OPEN')
      OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
                 WHERE dispatch.candidate_launch_id=launch.id
                   AND dispatch.state NOT IN ('STOPPED','CANCELLED'))))
BEGIN SELECT RAISE(ABORT,'Convention owner terminal state has live generic candidate protocol'); END;

CREATE TRIGGER ai_candidate_internal_judge_terminal_guard
BEFORE UPDATE OF state ON judge_run
WHEN NEW.state IN ('COMPLETED','SESSION_ERROR','ABORTED','FAILED','TIMED_OUT') AND EXISTS (
  SELECT 1 FROM ai_candidate_internal_launch launch
  WHERE launch.judge_run_id=OLD.id
    AND (launch.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
      OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
                 WHERE run.id=launch.candidate_run_id AND run.state='OPEN')
      OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
                 WHERE dispatch.candidate_launch_id=launch.id
                   AND dispatch.state NOT IN ('STOPPED','CANCELLED'))))
BEGIN SELECT RAISE(ABORT,'Judge owner terminal state has live generic candidate protocol'); END;
