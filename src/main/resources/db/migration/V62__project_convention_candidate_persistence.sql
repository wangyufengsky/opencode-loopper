-- PROJECT_CONVENTION_V1 freezes every input needed for deterministic
-- compilation before remote create/prompt I/O. The accepted result is the
-- immutable server compilation output; model final text is not authoritative.
ALTER TABLE project_convention_draft
  ADD COLUMN response_mode TEXT
    CHECK (response_mode IS NULL OR response_mode IN ('TEXT_MARKER','INTERNAL_MCP'));
ALTER TABLE project_convention_draft
  ADD COLUMN source_revision INTEGER
    CHECK (source_revision IS NULL OR source_revision>=0);

CREATE TRIGGER trg_project_convention_candidate_restart_identity_insert
BEFORE INSERT ON project_convention_draft
WHEN (NEW.response_mode IS NULL AND NEW.source_revision IS NOT NULL)
  OR (NEW.response_mode IS NOT NULL AND NEW.source_revision IS NULL)
BEGIN
  SELECT RAISE(ABORT,'Convention candidate restart identity is incomplete');
END;

CREATE TRIGGER trg_project_convention_candidate_restart_identity_update
BEFORE UPDATE OF response_mode,source_revision ON project_convention_draft
BEGIN
  SELECT CASE
    WHEN (NEW.response_mode IS NULL AND NEW.source_revision IS NOT NULL)
      OR (NEW.response_mode IS NOT NULL AND NEW.source_revision IS NULL)
      THEN RAISE(ABORT,'Convention candidate restart identity is incomplete')
    WHEN OLD.response_mode IS NOT NULL AND NEW.response_mode IS NOT OLD.response_mode
      THEN RAISE(ABORT,'Convention candidate restart identity is immutable')
    WHEN OLD.source_revision IS NOT NULL AND NEW.source_revision IS NOT OLD.source_revision
      THEN RAISE(ABORT,'Convention candidate restart identity is immutable')
  END;
END;

CREATE TABLE project_convention_candidate_source_snapshot (
    -- The deterministic run ID exists before the remote Session. No FK to the
    -- not-yet-created run is possible here.
    candidate_run_id TEXT PRIMARY KEY CHECK (length(candidate_run_id)>0),
    project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    project_convention_draft_id TEXT NOT NULL
        REFERENCES project_convention_draft(id) ON DELETE CASCADE,
    source_revision INTEGER NOT NULL CHECK (source_revision>=0),
    prepared_owner_version INTEGER NOT NULL CHECK (prepared_owner_version>=0),
    contract_version TEXT NOT NULL CHECK (contract_version='PROJECT_CONVENTION_V1'),
    source_exists INTEGER NOT NULL CHECK (source_exists IN (0,1)),
    source_agents_sha256 TEXT NOT NULL CHECK (length(source_agents_sha256)=64),
    source_content TEXT NOT NULL CHECK (length(source_content)<=262144),
    source_content_sha256 TEXT NOT NULL CHECK (length(source_content_sha256)=64),
    project_stack_profile_id TEXT NOT NULL CHECK (length(project_stack_profile_id)>0),
    stack_fingerprint TEXT NOT NULL CHECK (length(stack_fingerprint)=64),
    canonical_evidence_json TEXT NOT NULL CHECK (
        length(canonical_evidence_json) BETWEEN 2 AND 1048576
        AND json_valid(canonical_evidence_json)
        AND json_type(canonical_evidence_json)='object'),
    evidence_sha256 TEXT NOT NULL CHECK (length(evidence_sha256)=64),
    created_at TEXT NOT NULL,
    UNIQUE(project_convention_draft_id,source_revision,prepared_owner_version)
);

CREATE INDEX idx_project_convention_candidate_source_owner
  ON project_convention_candidate_source_snapshot(
    project_id,project_convention_draft_id,source_revision,prepared_owner_version,created_at);

CREATE TABLE project_convention_candidate_accepted_result (
    candidate_run_id TEXT PRIMARY KEY
        REFERENCES ai_candidate_submission_run(id) ON DELETE CASCADE,
    project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    project_convention_draft_id TEXT NOT NULL
        REFERENCES project_convention_draft(id) ON DELETE CASCADE,
    source_revision INTEGER NOT NULL CHECK (source_revision>=0),
    owner_version INTEGER NOT NULL CHECK (owner_version>=0),
    contract_version TEXT NOT NULL CHECK (contract_version='PROJECT_CONVENTION_V1'),
    canonical_candidate_json TEXT NOT NULL CHECK (
        length(canonical_candidate_json) BETWEEN 2 AND 131072
        AND json_valid(canonical_candidate_json)
        AND json_type(canonical_candidate_json)='object'),
    candidate_payload_sha256 TEXT NOT NULL CHECK (length(candidate_payload_sha256)=64),
    canonical_result_sha256 TEXT NOT NULL CHECK (length(canonical_result_sha256)=64),
    proposed_content TEXT NOT NULL CHECK (length(proposed_content) BETWEEN 1 AND 524288),
    proposed_content_sha256 TEXT NOT NULL CHECK (length(proposed_content_sha256)=64),
    settled_draft_id TEXT REFERENCES project_convention_draft(id) ON DELETE RESTRICT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version>=0),
    UNIQUE(project_convention_draft_id,source_revision,owner_version),
    FOREIGN KEY(candidate_run_id)
        REFERENCES project_convention_candidate_source_snapshot(candidate_run_id) ON DELETE CASCADE
);

CREATE INDEX idx_project_convention_candidate_result_unsettled
  ON project_convention_candidate_accepted_result(
    settled_draft_id,created_at,candidate_run_id);

CREATE TRIGGER trg_project_convention_source_snapshot_insert
BEFORE INSERT ON project_convention_candidate_source_snapshot
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM project_convention_draft owner
    JOIN project_stack_profile profile ON profile.id=owner.project_stack_profile_id
    WHERE owner.id=NEW.project_convention_draft_id
      AND owner.project_id=NEW.project_id
      AND owner.state='RUNNING'
      AND owner.external_session_id IS NULL
      AND owner.version=NEW.prepared_owner_version
      AND owner.response_mode='INTERNAL_MCP'
      AND owner.source_revision=NEW.source_revision
      AND owner.source_exists=NEW.source_exists
      AND owner.source_sha256=NEW.source_agents_sha256
      AND owner.source_content=NEW.source_content
      AND owner.source_sha256=NEW.source_content_sha256
      AND owner.project_stack_profile_id=NEW.project_stack_profile_id
      AND owner.stack_fingerprint=NEW.stack_fingerprint
      AND profile.project_id=NEW.project_id
      AND profile.manifest_fingerprint=NEW.stack_fingerprint)
    THEN RAISE(ABORT,'Convention source snapshot owner mismatch') END;
END;

CREATE TRIGGER trg_project_convention_source_snapshot_update
BEFORE UPDATE ON project_convention_candidate_source_snapshot
BEGIN
  SELECT RAISE(ABORT,'Convention source snapshot is immutable');
END;

CREATE TRIGGER trg_project_convention_source_snapshot_live_delete
BEFORE DELETE ON project_convention_candidate_source_snapshot
WHEN EXISTS (
  SELECT 1 FROM ai_candidate_internal_launch launch
  WHERE launch.candidate_run_id=OLD.candidate_run_id
    AND launch.candidate_kind='PROJECT_CONVENTION_V1')
BEGIN
  SELECT RAISE(ABORT,'Convention source snapshot with protocol history cannot be deleted');
END;

CREATE TRIGGER trg_project_convention_candidate_launch_source_anchor
BEFORE INSERT ON ai_candidate_internal_launch
WHEN NEW.candidate_kind='PROJECT_CONVENTION_V1'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM project_convention_draft owner
    WHERE owner.id=NEW.project_convention_draft_id
      AND owner.project_id=NEW.project_id
      AND owner.response_mode='INTERNAL_MCP'
      AND owner.source_revision=NEW.source_revision
      AND owner.version=NEW.prepared_owner_version)
    THEN RAISE(ABORT,'Convention candidate launch source revision mismatch') END;
END;

CREATE TRIGGER trg_project_convention_create_dispatch_source_gate
BEFORE UPDATE OF state,create_dispatch_attempted ON ai_candidate_internal_launch
WHEN OLD.candidate_kind='PROJECT_CONVENTION_V1'
  AND OLD.create_dispatch_attempted=0 AND NEW.create_dispatch_attempted=1
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM project_convention_candidate_source_snapshot snapshot
    JOIN project_convention_draft owner
      ON owner.id=snapshot.project_convention_draft_id
    WHERE snapshot.candidate_run_id=NEW.candidate_run_id
      AND snapshot.project_id=NEW.project_id
      AND snapshot.project_convention_draft_id=NEW.project_convention_draft_id
      AND snapshot.source_revision=NEW.source_revision
      AND snapshot.prepared_owner_version=NEW.prepared_owner_version
      AND snapshot.contract_version=NEW.contract_version
      AND owner.state='RUNNING'
      AND owner.external_session_id IS NULL
      AND owner.version=NEW.prepared_owner_version
      AND owner.response_mode='INTERNAL_MCP'
      AND owner.source_revision=snapshot.source_revision
      AND owner.source_exists=snapshot.source_exists
      AND owner.source_sha256=snapshot.source_agents_sha256
      AND owner.source_content=snapshot.source_content
      AND owner.source_sha256=snapshot.source_content_sha256
      AND owner.project_stack_profile_id=snapshot.project_stack_profile_id
      AND owner.stack_fingerprint=snapshot.stack_fingerprint)
    THEN RAISE(ABORT,'Convention create dispatch requires exact frozen source snapshot') END;
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

CREATE TRIGGER trg_project_convention_initial_prompt_source_gate
BEFORE INSERT ON ai_candidate_prompt_dispatch
WHEN NEW.dispatch_kind='INITIAL' AND EXISTS (
  SELECT 1 FROM ai_candidate_submission_run run
  WHERE run.id=NEW.run_id AND run.candidate_kind='PROJECT_CONVENTION_V1'
    AND run.submission_channel='INTERNAL_MCP')
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM ai_candidate_submission_run run
    JOIN project_convention_candidate_source_snapshot snapshot
      ON snapshot.candidate_run_id=run.id
    WHERE run.id=NEW.run_id
      AND snapshot.project_id=run.project_id
      AND snapshot.project_convention_draft_id=run.owner_id
      AND snapshot.source_revision=run.source_revision
      AND snapshot.prepared_owner_version+1=run.owner_version
      AND snapshot.contract_version=run.contract_version)
    THEN RAISE(ABORT,'Convention initial prompt requires exact frozen source snapshot') END;
END;

CREATE TRIGGER trg_project_convention_accepted_result_insert
BEFORE INSERT ON project_convention_candidate_accepted_result
BEGIN
  SELECT CASE
    WHEN NOT EXISTS (
      SELECT 1
      FROM ai_candidate_submission_run run
      JOIN project_convention_candidate_source_snapshot snapshot
        ON snapshot.candidate_run_id=run.id
      WHERE run.id=NEW.candidate_run_id
        AND run.candidate_kind='PROJECT_CONVENTION_V1'
        AND run.submission_channel='INTERNAL_MCP'
        AND run.workflow_step='PROJECT_CONVENTION_V1'
        AND run.owner_type='PROJECT_CONVENTION_DRAFT'
        AND run.owner_id=NEW.project_convention_draft_id
        AND run.project_id=NEW.project_id
        AND run.source_revision=NEW.source_revision
        AND run.owner_version=NEW.owner_version
        AND run.contract_version=NEW.contract_version
        AND run.max_attempts=3
        AND run.state IN ('OPEN','ACCEPTED')
        AND snapshot.project_id=NEW.project_id
        AND snapshot.project_convention_draft_id=NEW.project_convention_draft_id
        AND snapshot.source_revision=NEW.source_revision
        AND snapshot.prepared_owner_version+1=NEW.owner_version
        AND snapshot.contract_version=NEW.contract_version)
      THEN RAISE(ABORT,'Convention accepted result run mismatch')
    WHEN NEW.settled_draft_id IS NOT NULL
      AND NEW.settled_draft_id<>NEW.project_convention_draft_id
      THEN RAISE(ABORT,'Convention accepted result settlement owner mismatch')
  END;
END;

CREATE TRIGGER trg_project_convention_accepted_result_update
BEFORE UPDATE ON project_convention_candidate_accepted_result
BEGIN
  SELECT CASE
    WHEN NEW.candidate_run_id IS NOT OLD.candidate_run_id
      OR NEW.project_id IS NOT OLD.project_id
      OR NEW.project_convention_draft_id IS NOT OLD.project_convention_draft_id
      OR NEW.source_revision IS NOT OLD.source_revision
      OR NEW.owner_version IS NOT OLD.owner_version
      OR NEW.contract_version IS NOT OLD.contract_version
      OR NEW.canonical_candidate_json IS NOT OLD.canonical_candidate_json
      OR NEW.candidate_payload_sha256 IS NOT OLD.candidate_payload_sha256
      OR NEW.canonical_result_sha256 IS NOT OLD.canonical_result_sha256
      OR NEW.proposed_content IS NOT OLD.proposed_content
      OR NEW.proposed_content_sha256 IS NOT OLD.proposed_content_sha256
      OR NEW.created_at IS NOT OLD.created_at
      THEN RAISE(ABORT,'Convention accepted result payload is immutable')
    WHEN OLD.settled_draft_id IS NOT NULL
      AND NEW.settled_draft_id IS NOT OLD.settled_draft_id
      THEN RAISE(ABORT,'Convention accepted result settlement is irreversible')
    WHEN NEW.settled_draft_id IS NOT NULL
      AND NEW.settled_draft_id<>NEW.project_convention_draft_id
      THEN RAISE(ABORT,'Convention accepted result settlement owner mismatch')
    WHEN OLD.settled_draft_id IS NULL AND NEW.settled_draft_id IS NOT NULL
      AND NEW.version<>OLD.version+1
      THEN RAISE(ABORT,'Convention accepted result settlement version mismatch')
    WHEN OLD.settled_draft_id IS NULL AND NEW.settled_draft_id IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM ai_candidate_submission_run run
        WHERE run.id=NEW.candidate_run_id AND run.state='ACCEPTED')
      THEN RAISE(ABORT,'Convention accepted result settlement requires accepted run')
    WHEN NEW.settled_draft_id IS OLD.settled_draft_id
      AND (NEW.updated_at IS NOT OLD.updated_at OR NEW.version<>OLD.version)
      THEN RAISE(ABORT,'Convention accepted result only settlement may change')
  END;
END;

CREATE TRIGGER trg_project_convention_candidate_run_snapshot_cleanup
AFTER DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind='PROJECT_CONVENTION_V1'
BEGIN
  DELETE FROM project_convention_candidate_source_snapshot
  WHERE candidate_run_id=OLD.id;
END;
