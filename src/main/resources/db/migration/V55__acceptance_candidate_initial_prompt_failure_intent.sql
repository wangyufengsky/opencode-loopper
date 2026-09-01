-- Extend the durable internal-launch termination ledger for failures that occur
-- before an INITIAL Acceptance prompt can be acknowledged. SQLite cannot widen
-- the closed CHECK enums in place, so preserve the only child ledger, rebuild
-- the parent, and restore the child with its original foreign-key guarantees.
CREATE TABLE acceptance_candidate_internal_launch_cleanup_remote_v55_backup AS
SELECT * FROM acceptance_candidate_internal_launch_cleanup_remote;

-- SQLite validates every persisted trigger whenever a referenced table is
-- rebuilt. Drop the complete V52-V54 trigger closure first; the intent/child
-- triggers are recreated below and the cross-table guards are restored last.
DROP TRIGGER acceptance_candidate_run_launch_gate_v53;
DROP TRIGGER acceptance_compilation_delete_guard_v53;
DROP TRIGGER acceptance_compilation_terminal_guard_v53;
DROP TRIGGER acceptance_designer_delete_guard_v53;
DROP TRIGGER acceptance_designer_terminal_guard_v53;
DROP TRIGGER acceptance_internal_cleanup_live_delete_v53;
DROP TRIGGER acceptance_internal_cleanup_v54_identity_immutable;
DROP TRIGGER acceptance_internal_intent_launch_progress_gate_v54;
DROP TRIGGER acceptance_internal_intent_prompt_insert_gate_v54;
DROP TRIGGER acceptance_internal_intent_prompt_progress_gate_v54;
DROP TRIGGER acceptance_internal_intent_run_insert_gate_v54;
DROP TRIGGER acceptance_internal_intent_run_progress_gate_v54;
DROP TRIGGER acceptance_internal_intent_submission_insert_gate_v54;
DROP TRIGGER acceptance_internal_launch_cleanup_before_terminal;
DROP TRIGGER acceptance_internal_launch_cleanup_checkpoint_irreversible;
DROP TRIGGER acceptance_internal_launch_cleanup_fence_monotonic;
DROP TRIGGER acceptance_internal_launch_cleanup_identity_immutable;
DROP TRIGGER acceptance_internal_launch_cleanup_parent_insert;
DROP TRIGGER acceptance_internal_launch_cleanup_proof_irreversible;
DROP TRIGGER acceptance_internal_launch_live_delete_v53;
DROP TRIGGER acceptance_internal_launch_settlement_gate_v53;
DROP TRIGGER acceptance_internal_launch_settlement_once;
DROP TRIGGER acceptance_internal_settled_terminal_gate_v54;
DROP TRIGGER acceptance_internal_termination_completed_gate_v54;
DROP TRIGGER acceptance_internal_termination_fsm_v54;
DROP TRIGGER acceptance_internal_termination_identity_immutable_v54;
DROP TRIGGER acceptance_internal_termination_insert_gate_v54;
DROP TRIGGER acceptance_internal_termination_no_delete_v54;
DROP TRIGGER acceptance_internal_termination_ready_gate_v54;

DROP TABLE acceptance_candidate_internal_launch_cleanup_remote;

CREATE TABLE acceptance_candidate_internal_termination_intent_v55 (
    id TEXT PRIMARY KEY,
    launch_id TEXT NOT NULL UNIQUE
      REFERENCES acceptance_candidate_internal_launch(id) ON DELETE RESTRICT,
    designer_session_id TEXT NOT NULL
      REFERENCES designer_session(id) ON DELETE RESTRICT,
    compilation_id TEXT NOT NULL
      REFERENCES loop_spec_compilation(id) ON DELETE RESTRICT,
    candidate_run_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN (
      'DESIGNER_CANCEL','OWNER_REPLACEMENT','INITIAL_PROMPT_FAILURE')),
    target_state TEXT NOT NULL CHECK (target_state IN ('CANCELLED','STALE','FAILED_STOPPED')),
    archive_when_complete INTEGER NOT NULL DEFAULT 0 CHECK (archive_when_complete IN (0,1)),
    reason_code TEXT CHECK (reason_code IS NULL OR reason_code IN (
      'BUDGET_EXHAUSTED','LOOKUP_UNSUPPORTED','RESULT_UNKNOWN')),
    parent_action TEXT NOT NULL CHECK (parent_action IN (
      'NONE','DESIGNER_CANCEL','OWNER_REPLACEMENT')),
    state TEXT NOT NULL CHECK (state IN ('REQUESTED','DISCONNECTED','READY','COMPLETED')),
    anchor_designer_version INTEGER NOT NULL CHECK (anchor_designer_version >= 0),
    anchor_requirement_revision_id TEXT
      REFERENCES design_requirement_revision(id) ON DELETE RESTRICT,
    anchor_discussion_revision INTEGER CHECK (anchor_discussion_revision >= 0),
    ready_at TEXT,
    completed_at TEXT,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((kind='DESIGNER_CANCEL' AND target_state='CANCELLED'
              AND reason_code IS NULL
              AND parent_action='DESIGNER_CANCEL'
              AND anchor_requirement_revision_id IS NULL AND anchor_discussion_revision IS NULL)
        OR (kind='OWNER_REPLACEMENT' AND target_state='STALE'
              AND archive_when_complete=0 AND reason_code IS NULL
              AND parent_action='OWNER_REPLACEMENT'
              AND anchor_requirement_revision_id IS NOT NULL AND anchor_discussion_revision IS NOT NULL)
        OR (kind='INITIAL_PROMPT_FAILURE' AND target_state='FAILED_STOPPED'
              AND reason_code IS NOT NULL
              AND ((parent_action='DESIGNER_CANCEL')
                OR (parent_action IN ('NONE','OWNER_REPLACEMENT') AND archive_when_complete=0))
              AND anchor_requirement_revision_id IS NOT NULL AND anchor_discussion_revision IS NOT NULL)),
    CHECK ((state IN ('READY','COMPLETED'))=(ready_at IS NOT NULL)),
    CHECK ((state='COMPLETED')=(completed_at IS NOT NULL))
);

INSERT INTO acceptance_candidate_internal_termination_intent_v55(
    id,launch_id,designer_session_id,compilation_id,candidate_run_id,kind,target_state,
    archive_when_complete,reason_code,parent_action,state,anchor_designer_version,
    anchor_requirement_revision_id,anchor_discussion_revision,ready_at,completed_at,
    last_error_code,last_error_detail,created_at,updated_at,version)
SELECT id,launch_id,designer_session_id,compilation_id,candidate_run_id,kind,target_state,
    archive_when_complete,NULL,
    CASE kind WHEN 'DESIGNER_CANCEL' THEN 'DESIGNER_CANCEL' ELSE 'OWNER_REPLACEMENT' END,
    state,anchor_designer_version,
    anchor_requirement_revision_id,anchor_discussion_revision,ready_at,completed_at,
    last_error_code,last_error_detail,created_at,updated_at,version
FROM acceptance_candidate_internal_termination_intent;

DROP TABLE acceptance_candidate_internal_termination_intent;
ALTER TABLE acceptance_candidate_internal_termination_intent_v55
  RENAME TO acceptance_candidate_internal_termination_intent;

CREATE INDEX idx_acceptance_internal_termination_recovery
    ON acceptance_candidate_internal_termination_intent(state,updated_at,id);
CREATE INDEX idx_acceptance_internal_termination_designer
    ON acceptance_candidate_internal_termination_intent(designer_session_id,state,id);
CREATE INDEX idx_acceptance_internal_termination_compilation
    ON acceptance_candidate_internal_termination_intent(compilation_id,state,id);

CREATE TRIGGER acceptance_internal_termination_insert_gate_v55
BEFORE INSERT ON acceptance_candidate_internal_termination_intent
BEGIN
  SELECT CASE WHEN NEW.state<>'REQUESTED'
    THEN RAISE(ABORT,'acceptance internal termination intent must start REQUESTED') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM acceptance_candidate_internal_launch launch
    JOIN loop_spec_compilation compilation ON compilation.id=launch.compilation_id
    JOIN designer_session designer ON designer.id=launch.designer_session_id
    WHERE launch.id=NEW.launch_id
      AND launch.designer_session_id=NEW.designer_session_id
      AND launch.compilation_id=NEW.compilation_id
      AND launch.candidate_run_id=NEW.candidate_run_id
      AND launch.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE')
      AND compilation.id=NEW.compilation_id
      AND compilation.designer_session_id=NEW.designer_session_id
      AND designer.id=NEW.designer_session_id
      AND designer.version=NEW.anchor_designer_version
  ) THEN RAISE(ABORT,'acceptance internal termination owner anchor mismatch') END;
  SELECT CASE WHEN NEW.kind='DESIGNER_CANCEL' AND NOT EXISTS (
    SELECT 1 FROM designer_session designer
    WHERE designer.id=NEW.designer_session_id
      AND designer.version=NEW.anchor_designer_version
      AND designer.state='STOPPING'
  ) THEN RAISE(ABORT,'acceptance internal cancellation anchor mismatch') END;
  SELECT CASE WHEN NEW.kind='OWNER_REPLACEMENT' AND NOT EXISTS (
    SELECT 1
    FROM designer_session designer
    JOIN design_requirement_revision revision
      ON revision.id=NEW.anchor_requirement_revision_id
     AND revision.designer_session_id=designer.id
     AND revision.revision=designer.current_requirement_revision
    WHERE designer.id=NEW.designer_session_id
      AND designer.version=NEW.anchor_designer_version
      AND designer.discussion_revision=NEW.anchor_discussion_revision
      AND designer.state NOT IN ('STOPPING','CANCELLED')
      AND revision.state IN ('ACTIVE','WAITING_INPUT','COMPLETED')
  ) THEN RAISE(ABORT,'acceptance internal replacement anchor mismatch') END;

  -- The INITIAL failure intent is admitted only from one exact, fully settled
  -- launch/run/certificate tuple and one immutable failure fact. Application
  -- status values alone can never manufacture the termination authority.
  SELECT CASE WHEN NEW.kind='INITIAL_PROMPT_FAILURE' AND NOT EXISTS (
    SELECT 1
    FROM acceptance_candidate_internal_launch launch
    JOIN acceptance_candidate_internal_launch_settlement_certificate certificate
      ON certificate.launch_id=launch.id
     AND certificate.candidate_run_id=launch.candidate_run_id
     AND certificate.settled_owner_version=launch.settled_owner_version
     AND certificate.settled_at=launch.settled_at
    JOIN acceptance_candidate_internal_launch_run_requirement requirement
      ON requirement.launch_id=launch.id
     AND requirement.candidate_run_id=launch.candidate_run_id
    JOIN ai_candidate_submission_run run ON run.id=launch.candidate_run_id
    JOIN loop_spec_compilation compilation ON compilation.id=launch.compilation_id
    JOIN designer_session designer ON designer.id=launch.designer_session_id
    JOIN design_requirement_revision revision
      ON revision.id=NEW.anchor_requirement_revision_id
     AND revision.designer_session_id=designer.id
     AND revision.revision=designer.current_requirement_revision
    WHERE launch.id=NEW.launch_id
      AND launch.state='SETTLED'
      AND launch.designer_session_id=NEW.designer_session_id
      AND launch.compilation_id=NEW.compilation_id
      AND launch.candidate_run_id=NEW.candidate_run_id
      AND run.id=NEW.candidate_run_id
      AND run.designer_session_id=NEW.designer_session_id
      AND run.owner_type='LOOP_SPEC_COMPILATION'
      AND run.owner_id=NEW.compilation_id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.workflow_step=launch.workflow_step
      AND run.source_revision=launch.source_design_revision
      AND run.owner_version=launch.settled_owner_version
      AND run.submission_channel='INTERNAL_MCP'
      AND run.contract_version=launch.contract_version
      AND run.runtime_generation_id=launch.runtime_generation_id
      AND run.external_session_id=launch.external_session_id
      AND run.state='OPEN' AND run.max_attempts=2 AND run.attempts_used=0
      AND run.terminal_attempt_id IS NULL
      AND compilation.designer_session_id=NEW.designer_session_id
      AND compilation.work_package_id=launch.work_package_id
      AND compilation.design_revision=launch.source_design_revision
      AND compilation.source_design_message_id=launch.source_design_message_id
      AND compilation.source_draft_version=launch.source_draft_version
      AND compilation.state='RUNNING'
      AND compilation.external_session_id=launch.external_session_id
      AND compilation.external_session_state='CANDIDATE_PROMPT_PENDING'
      AND compilation.version=launch.settled_owner_version
      AND designer.version=NEW.anchor_designer_version
      AND designer.discussion_revision=NEW.anchor_discussion_revision
      AND designer.state NOT IN ('STOPPING','CANCELLED')
      AND revision.state IN ('ACTIVE','WAITING_INPUT','COMPLETED')
      AND NOT EXISTS (
        SELECT 1 FROM ai_candidate_submission_attempt attempt WHERE attempt.run_id=run.id)
      AND (
        (NEW.reason_code='BUDGET_EXHAUSTED'
          AND revision.model_calls_used>=revision.max_model_calls
          AND NOT EXISTS (
            SELECT 1 FROM ai_candidate_prompt_dispatch prompt WHERE prompt.run_id=run.id))
        OR
        (NEW.reason_code='LOOKUP_UNSUPPORTED'
          AND EXISTS (
            SELECT 1 FROM ai_candidate_prompt_dispatch prompt
            WHERE prompt.run_id=run.id
              AND prompt.internal_launch_id=launch.id
              AND prompt.dispatch_kind='INITIAL'
              AND prompt.source_attempt_ordinal IS NULL
              AND prompt.external_session_id=run.external_session_id
              AND prompt.runtime_generation_id=run.runtime_generation_id
              AND prompt.state='DISCONNECTED'
              AND prompt.model_call_consumed=1
              AND prompt.claim_owner IS NULL
              AND prompt.dispatch_attempted=0
              AND prompt.acknowledged=0
              AND prompt.last_error_code IN (
                'OPENCODE_PROMPT_LOOKUP_UNAVAILABLE','OPENCODE_PROMPT_REQUEST_STALE'))
          AND NOT EXISTS (
            SELECT 1 FROM ai_candidate_prompt_dispatch extra
            WHERE extra.run_id=run.id AND extra.dispatch_kind<>'INITIAL'))
        OR
        (NEW.reason_code='RESULT_UNKNOWN'
          AND EXISTS (
            SELECT 1 FROM ai_candidate_prompt_dispatch prompt
            WHERE prompt.run_id=run.id
              AND prompt.internal_launch_id=launch.id
              AND prompt.dispatch_kind='INITIAL'
              AND prompt.source_attempt_ordinal IS NULL
              AND prompt.external_session_id=run.external_session_id
              AND prompt.runtime_generation_id=run.runtime_generation_id
              AND prompt.state='DISCONNECTED'
              AND prompt.model_call_consumed=1
              AND prompt.claim_owner IS NULL
              AND prompt.dispatch_attempted=1
              AND prompt.acknowledged=0
              AND prompt.last_error_code='OPENCODE_PROMPT_RESULT_UNKNOWN')
          AND NOT EXISTS (
            SELECT 1 FROM ai_candidate_prompt_dispatch extra
            WHERE extra.run_id=run.id AND extra.dispatch_kind<>'INITIAL'))
      )
  ) THEN RAISE(ABORT,'acceptance initial prompt failure evidence mismatch') END;
  SELECT CASE WHEN NEW.kind='INITIAL_PROMPT_FAILURE' AND NEW.parent_action<>'NONE'
    THEN RAISE(ABORT,'acceptance initial prompt failure must start without a parent override') END;
END;

CREATE TRIGGER acceptance_internal_termination_identity_immutable_v55
BEFORE UPDATE ON acceptance_candidate_internal_termination_intent
WHEN NEW.id<>OLD.id OR NEW.launch_id<>OLD.launch_id
  OR NEW.designer_session_id<>OLD.designer_session_id
  OR NEW.compilation_id<>OLD.compilation_id
  OR NEW.candidate_run_id<>OLD.candidate_run_id
  OR NEW.kind<>OLD.kind OR NEW.target_state<>OLD.target_state
  OR (NEW.archive_when_complete<>OLD.archive_when_complete AND NOT (
      OLD.kind='INITIAL_PROMPT_FAILURE' AND OLD.parent_action='NONE'
      AND NEW.parent_action='DESIGNER_CANCEL' AND NEW.archive_when_complete IN (0,1)
      AND EXISTS (SELECT 1 FROM designer_session designer
        WHERE designer.id=OLD.designer_session_id AND designer.state='STOPPING')))
  OR NEW.reason_code IS NOT OLD.reason_code
  OR (NEW.parent_action<>OLD.parent_action AND NOT (
      OLD.kind='INITIAL_PROMPT_FAILURE' AND OLD.parent_action='NONE'
      AND NEW.parent_action IN ('DESIGNER_CANCEL','OWNER_REPLACEMENT')
      AND ((NEW.parent_action='DESIGNER_CANCEL' AND EXISTS (
        SELECT 1 FROM designer_session designer
        WHERE designer.id=OLD.designer_session_id AND designer.state='STOPPING'))
      OR (NEW.parent_action='OWNER_REPLACEMENT' AND EXISTS (
        SELECT 1 FROM designer_session designer
        JOIN design_requirement_revision revision ON revision.id=OLD.anchor_requirement_revision_id
        WHERE designer.id=OLD.designer_session_id
          AND designer.current_requirement_revision=revision.revision
          AND designer.discussion_revision=OLD.anchor_discussion_revision
          AND designer.state NOT IN ('STOPPING','CANCELLED'))))))
  OR NEW.anchor_designer_version<>OLD.anchor_designer_version
  OR NEW.anchor_requirement_revision_id IS NOT OLD.anchor_requirement_revision_id
  OR NEW.anchor_discussion_revision IS NOT OLD.anchor_discussion_revision
  OR NEW.created_at<>OLD.created_at
BEGIN
  SELECT RAISE(ABORT,'acceptance internal termination identity is immutable');
END;

CREATE TRIGGER acceptance_internal_termination_fsm_v55
BEFORE UPDATE OF state ON acceptance_candidate_internal_termination_intent
WHEN NEW.state<>OLD.state AND NOT (
  (OLD.state='REQUESTED' AND NEW.state IN ('DISCONNECTED','READY')) OR
  (OLD.state='DISCONNECTED' AND NEW.state IN ('REQUESTED','READY')) OR
  (OLD.state='READY' AND NEW.state='COMPLETED'))
BEGIN
  SELECT RAISE(ABORT,'acceptance internal termination state transition invalid');
END;

CREATE TRIGGER acceptance_internal_termination_ready_gate_v55
BEFORE UPDATE OF state ON acceptance_candidate_internal_termination_intent
WHEN OLD.state<>NEW.state AND NEW.state='READY'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch launch
    WHERE launch.id=NEW.launch_id
      AND launch.state IN (NEW.target_state,'FAILED_STOPPED')
      AND NOT EXISTS (
        SELECT 1 FROM ai_candidate_submission_run run
        WHERE run.id=launch.candidate_run_id AND run.state='OPEN')
      AND NOT EXISTS (
        SELECT 1 FROM ai_candidate_prompt_dispatch prompt
        WHERE prompt.internal_launch_id=launch.id
          AND prompt.state NOT IN ('STOPPED','CANCELLED'))
      AND NOT EXISTS (
        SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
        WHERE cleanup.launch_id=launch.id AND cleanup.state<>'STOPPED')
  ) THEN RAISE(ABORT,'acceptance internal termination is not ready') END;
END;

CREATE TRIGGER acceptance_internal_termination_completed_gate_v55
BEFORE UPDATE OF state ON acceptance_candidate_internal_termination_intent
WHEN OLD.state<>NEW.state AND NEW.state='COMPLETED'
BEGIN
  SELECT CASE WHEN NEW.parent_action='DESIGNER_CANCEL' AND NOT EXISTS (
    SELECT 1 FROM designer_session designer
    WHERE designer.id=NEW.designer_session_id AND designer.state='CANCELLED'
  ) THEN RAISE(ABORT,'acceptance internal cancellation parent is not terminal') END;
  SELECT CASE WHEN NEW.parent_action='OWNER_REPLACEMENT' AND NOT EXISTS (
    SELECT 1 FROM design_requirement_revision revision
    WHERE revision.id=NEW.anchor_requirement_revision_id AND revision.state='SUPERSEDED'
  ) THEN RAISE(ABORT,'acceptance internal replacement parent is not superseded') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch launch
    WHERE launch.id=NEW.launch_id
      AND launch.state IN (NEW.target_state,'FAILED_STOPPED')
      AND NOT EXISTS (
        SELECT 1 FROM ai_candidate_submission_run run
        WHERE run.id=launch.candidate_run_id AND run.state='OPEN')
      AND NOT EXISTS (
        SELECT 1 FROM ai_candidate_prompt_dispatch prompt
        WHERE prompt.internal_launch_id=launch.id
          AND prompt.state NOT IN ('STOPPED','CANCELLED'))
      AND NOT EXISTS (
        SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
        WHERE cleanup.launch_id=launch.id AND cleanup.state<>'STOPPED')
  ) THEN RAISE(ABORT,'acceptance internal termination completion is not quiescent') END;
  SELECT CASE WHEN NEW.parent_action='DESIGNER_CANCEL' AND NOT EXISTS (
    SELECT 1 FROM designer_session designer
    WHERE designer.id=NEW.designer_session_id AND designer.state='CANCELLED'
  ) THEN RAISE(ABORT,'acceptance internal cancellation parent is not terminal') END;
  SELECT CASE WHEN NEW.parent_action='OWNER_REPLACEMENT' AND NOT EXISTS (
    SELECT 1 FROM design_requirement_revision revision
    WHERE revision.id=NEW.anchor_requirement_revision_id AND revision.state='SUPERSEDED'
  ) THEN RAISE(ABORT,'acceptance internal replacement parent is not superseded') END;
  SELECT CASE WHEN NEW.kind='INITIAL_PROMPT_FAILURE' AND NEW.parent_action='NONE' AND NOT EXISTS (
    SELECT 1
    FROM loop_spec_compilation compilation
    JOIN designer_session designer ON designer.id=NEW.designer_session_id
    JOIN design_requirement_revision revision ON revision.id=NEW.anchor_requirement_revision_id
    WHERE compilation.id=NEW.compilation_id
      AND compilation.designer_session_id=NEW.designer_session_id
      AND revision.designer_session_id=NEW.designer_session_id
      AND (
        (revision.state='SUPERSEDED')
        OR (designer.state='CANCELLED'
          AND compilation.state='SESSION_ERROR'
          AND compilation.last_error_code='DESIGNER_CANCELLED')
        OR (NEW.reason_code='BUDGET_EXHAUSTED'
          AND compilation.state='DESIGN_INCOMPLETE'
          AND compilation.last_error_code='WORK_PACKAGE_MODEL_CALL_LIMIT')
        OR (NEW.reason_code='LOOKUP_UNSUPPORTED'
          AND compilation.state='DESIGN_INCOMPLETE'
          AND compilation.last_error_code='DESIGN_INCOMPLETE')
        OR (NEW.reason_code='RESULT_UNKNOWN'
          AND compilation.state='SESSION_ERROR'
          AND compilation.last_error_code='OPENCODE_PROMPT_RESULT_UNKNOWN')
        OR EXISTS (
          SELECT 1 FROM ai_candidate_submission_run run
          WHERE run.id=NEW.candidate_run_id
            AND (
              (run.state='ACCEPTED'
                AND compilation.state IN ('COMPLETED','DESIGN_INCOMPLETE','SESSION_ERROR'))
              OR (run.state='WAITING_INPUT'
                AND compilation.state='DESIGN_INCOMPLETE'
                AND compilation.last_error_code='ACCEPTANCE_CANDIDATE_WAITING_INPUT')
              OR (run.state='FALLBACK_REQUIRED'
                AND EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
                  WHERE handoff.compilation_id=NEW.compilation_id))
              OR (run.state='CLOSED'
                AND ((run.close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION'
                    AND EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
                      WHERE handoff.compilation_id=NEW.compilation_id))
                  OR (run.close_reason IN ('INTERACTION_FORBIDDEN','TIMEOUT','REMOTE_FAILED')
                    AND compilation.state='SESSION_ERROR')))
            )
        )
      )
  ) THEN RAISE(ABORT,'acceptance initial prompt failure parent is not terminal') END;
END;

CREATE TRIGGER acceptance_internal_termination_no_delete_v55
BEFORE DELETE ON acceptance_candidate_internal_termination_intent
BEGIN
  SELECT RAISE(ABORT,'acceptance internal termination intent is durable');
END;

-- Intent creation and acknowledgement/submission writes are serialized SQLite
-- writers. The insert gate rejects pre-existing progress; these reverse gates
-- reject every later attempt to manufacture success or submission after intent.
CREATE TRIGGER acceptance_initial_failure_prompt_evidence_immutable_v55
BEFORE UPDATE ON ai_candidate_prompt_dispatch
WHEN OLD.dispatch_kind='INITIAL'
  AND EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.launch_id=OLD.internal_launch_id
      AND intent.kind='INITIAL_PROMPT_FAILURE' AND intent.state<>'COMPLETED')
  AND (
    NEW.model_call_consumed<>OLD.model_call_consumed
    OR NEW.model_call_consumed_at IS NOT OLD.model_call_consumed_at
    OR NEW.dispatch_attempted<>OLD.dispatch_attempted
    OR NEW.dispatch_started_at IS NOT OLD.dispatch_started_at
    OR NEW.acknowledged<>OLD.acknowledged
    OR NEW.acked_at IS NOT OLD.acked_at
    OR NEW.last_error_code IS NOT OLD.last_error_code
    OR NEW.last_error_detail IS NOT OLD.last_error_detail
    OR NEW.state IN ('PROMPTING','ACKNOWLEDGED'))
BEGIN
  SELECT RAISE(ABORT,'active initial prompt failure freezes prompt evidence');
END;

-- Restore the V54 cleanup ledger exactly, now referencing the rebuilt intent.
CREATE TABLE acceptance_candidate_internal_launch_cleanup_remote (
    launch_id TEXT NOT NULL REFERENCES acceptance_candidate_internal_launch(id) ON DELETE CASCADE,
    external_session_id TEXT NOT NULL UNIQUE,
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
    stop_dispatch_attempted INTEGER NOT NULL DEFAULT 0 CHECK (stop_dispatch_attempted IN (0,1)),
    stop_dispatch_started_at TEXT,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    purpose TEXT NOT NULL DEFAULT 'LAUNCH_AMBIGUITY'
      CHECK (purpose IN ('LAUNCH_AMBIGUITY','TERMINATION_INTENT')),
    termination_intent_id TEXT
      REFERENCES acceptance_candidate_internal_termination_intent(id) ON DELETE RESTRICT,
    PRIMARY KEY(launch_id,external_session_id),
    FOREIGN KEY(external_session_id,runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id) ON DELETE RESTRICT,
    CHECK ((termination_proof IS NULL)=(proof_at IS NULL)),
    CHECK ((state='STOPPED')=(termination_proof IS NOT NULL)),
    CHECK ((stop_claim_owner IS NULL)=(stop_claim_token IS NULL)),
    CHECK ((stop_claim_owner IS NULL)=(stop_claim_expires_at IS NULL)),
    CHECK ((state='STOPPING')=(stop_claim_owner IS NOT NULL)),
    CHECK ((stop_dispatch_attempted=0 AND stop_dispatch_started_at IS NULL)
      OR (stop_dispatch_attempted=1 AND stop_dispatch_started_at IS NOT NULL))
);

INSERT INTO acceptance_candidate_internal_launch_cleanup_remote(
    launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
    directory_sha256,title_sha256,state,termination_proof,proof_at,stop_claim_owner,
    stop_claim_token,stop_claim_expires_at,stop_fence,stop_dispatch_attempted,
    stop_dispatch_started_at,last_error_code,last_error_detail,created_at,updated_at,
    version,purpose,termination_intent_id)
SELECT launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
    directory_sha256,title_sha256,state,termination_proof,proof_at,stop_claim_owner,
    stop_claim_token,stop_claim_expires_at,stop_fence,stop_dispatch_attempted,
    stop_dispatch_started_at,last_error_code,last_error_detail,created_at,updated_at,
    version,purpose,termination_intent_id
FROM acceptance_candidate_internal_launch_cleanup_remote_v55_backup;

DROP TABLE acceptance_candidate_internal_launch_cleanup_remote_v55_backup;

CREATE INDEX idx_acceptance_internal_launch_cleanup_active
    ON acceptance_candidate_internal_launch_cleanup_remote(launch_id,state,external_session_id);

CREATE TRIGGER acceptance_internal_launch_cleanup_parent_insert
BEFORE INSERT ON acceptance_candidate_internal_launch_cleanup_remote
BEGIN
  SELECT CASE WHEN (NEW.purpose='LAUNCH_AMBIGUITY')<>(NEW.termination_intent_id IS NULL)
    THEN RAISE(ABORT,'acceptance internal cleanup purpose and intent mismatch') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch parent
    JOIN open_code_session_runtime_binding binding
      ON binding.external_session_id=NEW.external_session_id
     AND binding.runtime_generation_id=NEW.runtime_generation_id
     AND binding.ownership_mode='MANAGED'
     AND binding.endpoint_fingerprint=NEW.endpoint_fingerprint
     AND binding.internal_mcp_server=parent.internal_mcp_server
    LEFT JOIN acceptance_candidate_internal_termination_intent intent
      ON intent.id=NEW.termination_intent_id AND intent.launch_id=parent.id
    WHERE parent.id=NEW.launch_id
      AND parent.runtime_generation_id=NEW.runtime_generation_id
      AND parent.endpoint_fingerprint=NEW.endpoint_fingerprint
      AND ((NEW.purpose='LAUNCH_AMBIGUITY' AND parent.state='STOPPING')
        OR (NEW.purpose='TERMINATION_INTENT'
          AND parent.state IN ('STOPPING','SETTLED')
          AND intent.state IN ('REQUESTED','DISCONNECTED')))
  ) THEN RAISE(ABORT,'acceptance internal launch cleanup parent or remote mismatch') END;
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_identity_immutable
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN NEW.launch_id<>OLD.launch_id
  OR NEW.external_session_id<>OLD.external_session_id
  OR NEW.runtime_generation_id<>OLD.runtime_generation_id
  OR NEW.endpoint_fingerprint<>OLD.endpoint_fingerprint
  OR NEW.directory_sha256<>OLD.directory_sha256
  OR NEW.title_sha256<>OLD.title_sha256
  OR NEW.created_at<>OLD.created_at
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup identity is immutable');
END;

CREATE TRIGGER acceptance_internal_cleanup_v54_identity_immutable
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN NEW.purpose<>OLD.purpose
  OR NEW.termination_intent_id IS NOT OLD.termination_intent_id
BEGIN
  SELECT RAISE(ABORT,'acceptance internal cleanup purpose is immutable');
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_fence_monotonic
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN NEW.stop_fence<OLD.stop_fence
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup fence cannot decrease');
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_checkpoint_irreversible
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN OLD.stop_dispatch_attempted=1 AND (
  NEW.stop_dispatch_attempted<>1
  OR NEW.stop_dispatch_started_at IS NOT OLD.stop_dispatch_started_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup stop checkpoint is irreversible');
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_proof_irreversible
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN OLD.termination_proof IS NOT NULL AND (
  NEW.termination_proof IS NOT OLD.termination_proof
  OR NEW.proof_at IS NOT OLD.proof_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup termination proof is irreversible');
END;

CREATE TRIGGER acceptance_internal_cleanup_live_delete_v53
BEFORE DELETE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN OLD.state<>'STOPPED'
BEGIN
  SELECT RAISE(ABORT,'live Acceptance internal cleanup cannot be deleted');
END;

-- Restore every cross-table trigger dropped for the SQLite parent/child
-- rebuild. These are the V52-V54 guards, not weaker V55 approximations.
CREATE TRIGGER acceptance_internal_launch_cleanup_before_terminal
BEFORE UPDATE OF state ON acceptance_candidate_internal_launch
WHEN NEW.state IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE') AND (
  EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=NEW.id AND cleanup.state<>'STOPPED')
  OR (NEW.create_dispatch_attempted=1 AND NEW.external_session_id IS NULL AND NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=NEW.id)))
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup remotes must be stopped before terminal');
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

CREATE TRIGGER acceptance_internal_launch_settlement_gate_v53
BEFORE UPDATE OF state ON acceptance_candidate_internal_launch
WHEN NEW.state='SETTLED' AND OLD.state<>'SETTLED'
BEGIN
  SELECT CASE WHEN OLD.state<>'CREATED'
    THEN RAISE(ABORT,'Acceptance internal launch settlement requires CREATED') END;
  SELECT CASE WHEN EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
      WHERE cleanup.launch_id=NEW.id)
    THEN RAISE(ABORT,'Acceptance internal launch settlement forbids cleanup remotes') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    JOIN loop_spec_compilation compilation ON compilation.id=NEW.compilation_id
    JOIN acceptance_candidate_internal_launch_run_requirement requirement
      ON requirement.candidate_run_id=run.id AND requirement.launch_id=NEW.id
    WHERE run.id=NEW.candidate_run_id
      AND run.designer_session_id=NEW.designer_session_id
      AND run.owner_type='LOOP_SPEC_COMPILATION' AND run.owner_id=NEW.compilation_id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.workflow_step=NEW.workflow_step AND run.source_revision=NEW.source_design_revision
      AND run.owner_version=NEW.settled_owner_version
      AND run.submission_channel='INTERNAL_MCP' AND run.contract_version=NEW.contract_version
      AND run.runtime_generation_id=NEW.runtime_generation_id
      AND run.external_session_id=NEW.external_session_id
      AND run.state='OPEN' AND run.max_attempts=2 AND run.attempts_used=0
      AND NEW.settled_owner_version=NEW.prepared_owner_version+1
      AND compilation.designer_session_id=NEW.designer_session_id
      AND compilation.work_package_id=NEW.work_package_id
      AND compilation.design_revision=NEW.source_design_revision
      AND compilation.source_design_message_id=NEW.source_design_message_id
      AND compilation.source_draft_version=NEW.source_draft_version
      AND compilation.state='RUNNING'
      AND compilation.external_session_id=NEW.external_session_id
      AND compilation.external_session_state='CANDIDATE_PROMPT_PENDING'
      AND compilation.version=NEW.settled_owner_version)
    THEN RAISE(ABORT,'Acceptance internal launch settlement requires its exact OPEN run and RUNNING owner') END;
END;

CREATE TRIGGER acceptance_internal_launch_live_delete_v53
BEFORE DELETE ON acceptance_candidate_internal_launch
WHEN OLD.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE')
  OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=OLD.candidate_run_id AND run.state='OPEN')
  OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    WHERE dispatch.internal_launch_id=OLD.id AND dispatch.state NOT IN ('STOPPED','CANCELLED'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=OLD.id AND cleanup.state<>'STOPPED')
BEGIN
  SELECT RAISE(ABORT,'live Acceptance internal launch cannot be deleted');
END;

CREATE TRIGGER acceptance_compilation_terminal_guard_v53
BEFORE UPDATE OF state ON loop_spec_compilation
WHEN NEW.state IN ('DESIGN_INCOMPLETE','COMPLETED','SESSION_ERROR') AND (
  EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch launch
    WHERE launch.compilation_id=OLD.id AND launch.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    JOIN acceptance_candidate_internal_launch launch ON launch.id=cleanup.launch_id
    WHERE launch.compilation_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
    WHERE handoff.compilation_id=OLD.id AND handoff.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
    JOIN acceptance_candidate_legacy_handoff handoff ON handoff.id=cleanup.handoff_id
    WHERE handoff.compilation_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.owner_type='LOOP_SPEC_COMPILATION' AND run.owner_id=OLD.id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND run.state='OPEN')
  OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    JOIN ai_candidate_submission_run run ON run.id=dispatch.run_id
    WHERE run.owner_type='LOOP_SPEC_COMPILATION' AND run.owner_id=OLD.id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND dispatch.state NOT IN ('STOPPED','CANCELLED')))
BEGIN
  SELECT RAISE(ABORT,'parent terminal state has nonterminal Acceptance candidate protocol');
END;

CREATE TRIGGER acceptance_compilation_delete_guard_v53
BEFORE DELETE ON loop_spec_compilation
WHEN EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch launch
    WHERE launch.compilation_id=OLD.id AND launch.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    JOIN acceptance_candidate_internal_launch launch ON launch.id=cleanup.launch_id
    WHERE launch.compilation_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
    WHERE handoff.compilation_id=OLD.id AND handoff.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
    JOIN acceptance_candidate_legacy_handoff handoff ON handoff.id=cleanup.handoff_id
    WHERE handoff.compilation_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.owner_type='LOOP_SPEC_COMPILATION' AND run.owner_id=OLD.id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND run.state='OPEN')
  OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    JOIN ai_candidate_submission_run run ON run.id=dispatch.run_id
    WHERE run.owner_type='LOOP_SPEC_COMPILATION' AND run.owner_id=OLD.id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND dispatch.state NOT IN ('STOPPED','CANCELLED'))
BEGIN
  SELECT RAISE(ABORT,'parent delete has nonterminal Acceptance candidate protocol');
END;

CREATE TRIGGER acceptance_designer_terminal_guard_v53
BEFORE UPDATE OF state ON designer_session
WHEN NEW.state IN ('COMPLETED','SESSION_ERROR','CANCELLED') AND (
  EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch launch
    WHERE launch.designer_session_id=OLD.id AND launch.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    JOIN acceptance_candidate_internal_launch launch ON launch.id=cleanup.launch_id
    WHERE launch.designer_session_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
    WHERE handoff.designer_session_id=OLD.id AND handoff.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
    JOIN acceptance_candidate_legacy_handoff handoff ON handoff.id=cleanup.handoff_id
    WHERE handoff.designer_session_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.designer_session_id=OLD.id AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND run.state='OPEN')
  OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    JOIN ai_candidate_submission_run run ON run.id=dispatch.run_id
    WHERE run.designer_session_id=OLD.id AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND dispatch.state NOT IN ('STOPPED','CANCELLED')))
BEGIN
  SELECT RAISE(ABORT,'parent terminal state has nonterminal Acceptance candidate protocol');
END;

CREATE TRIGGER acceptance_designer_delete_guard_v53
BEFORE DELETE ON designer_session
WHEN EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch launch
    WHERE launch.designer_session_id=OLD.id AND launch.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    JOIN acceptance_candidate_internal_launch launch ON launch.id=cleanup.launch_id
    WHERE launch.designer_session_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
    WHERE handoff.designer_session_id=OLD.id AND handoff.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
    JOIN acceptance_candidate_legacy_handoff handoff ON handoff.id=cleanup.handoff_id
    WHERE handoff.designer_session_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.designer_session_id=OLD.id AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND run.state='OPEN')
  OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    JOIN ai_candidate_submission_run run ON run.id=dispatch.run_id
    WHERE run.designer_session_id=OLD.id AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND dispatch.state NOT IN ('STOPPED','CANCELLED'))
BEGIN
  SELECT RAISE(ABORT,'parent delete has nonterminal Acceptance candidate protocol');
END;

CREATE TRIGGER acceptance_internal_intent_run_insert_gate_v54
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND NEW.submission_channel='INTERNAL_MCP'
  AND EXISTS (SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.candidate_run_id=NEW.id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'active acceptance internal termination blocks run insert'); END;

CREATE TRIGGER acceptance_internal_intent_prompt_insert_gate_v54
BEFORE INSERT ON ai_candidate_prompt_dispatch
WHEN NEW.internal_launch_id IS NOT NULL AND EXISTS (
  SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
  WHERE intent.launch_id=NEW.internal_launch_id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'active acceptance internal termination blocks prompt insert'); END;

CREATE TRIGGER acceptance_internal_intent_submission_insert_gate_v54
BEFORE INSERT ON ai_candidate_submission_attempt
WHEN NEW.outcome='REJECTED' AND EXISTS (
  SELECT 1 FROM ai_candidate_submission_run run
  JOIN acceptance_candidate_internal_termination_intent intent
    ON intent.candidate_run_id=run.id AND intent.state<>'COMPLETED'
  WHERE run.id=NEW.run_id AND run.submission_channel='INTERNAL_MCP')
BEGIN SELECT RAISE(ABORT,'active acceptance internal termination blocks submission insert'); END;

CREATE TRIGGER acceptance_internal_intent_prompt_progress_gate_v54
BEFORE UPDATE OF state ON ai_candidate_prompt_dispatch
WHEN NEW.state<>OLD.state AND NEW.state IN ('PROMPTING','ACKNOWLEDGED')
  AND NEW.internal_launch_id IS NOT NULL AND EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.launch_id=NEW.internal_launch_id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'active acceptance internal termination blocks prompt progress'); END;

CREATE TRIGGER acceptance_internal_intent_run_progress_gate_v54
BEFORE UPDATE OF state ON ai_candidate_submission_run
WHEN OLD.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
  AND OLD.submission_channel='INTERNAL_MCP' AND NEW.state<>OLD.state
  AND NEW.state NOT IN ('ACCEPTED','WAITING_INPUT','FALLBACK_REQUIRED','CLOSED')
  AND EXISTS (SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.candidate_run_id=OLD.id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'active acceptance internal termination blocks run progress'); END;

CREATE TRIGGER acceptance_internal_intent_launch_progress_gate_v54
BEFORE UPDATE OF state ON acceptance_candidate_internal_launch
WHEN NEW.state<>OLD.state AND NEW.state IN ('CREATING','CREATED','SETTLED')
  AND EXISTS (SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.launch_id=OLD.id AND intent.state<>'COMPLETED')
BEGIN SELECT RAISE(ABORT,'active acceptance internal termination blocks launch progress'); END;

CREATE TRIGGER acceptance_internal_launch_settlement_once
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN OLD.settled_owner_version IS NOT NULL AND (
  NEW.settled_owner_version IS NOT OLD.settled_owner_version OR NEW.settled_at IS NOT OLD.settled_at)
BEGIN
  SELECT CASE WHEN NOT (
    OLD.state='SETTLED' AND NEW.state IN ('CANCELLED','STALE','FAILED_STOPPED')
    AND NEW.settled_owner_version IS NULL AND NEW.settled_at IS NULL
    AND EXISTS (SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
      WHERE intent.launch_id=OLD.id AND intent.state IN ('REQUESTED','DISCONNECTED')
        AND (NEW.state=intent.target_state OR NEW.state='FAILED_STOPPED'))
    AND EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_settlement_certificate certificate
      WHERE certificate.launch_id=OLD.id AND certificate.candidate_run_id=OLD.candidate_run_id
        AND certificate.settled_owner_version=OLD.settled_owner_version
        AND certificate.settled_at=OLD.settled_at))
    THEN RAISE(ABORT,'acceptance internal launch settlement is irreversible') END;
END;

CREATE TRIGGER acceptance_internal_settled_terminal_gate_v54
BEFORE UPDATE OF state ON acceptance_candidate_internal_launch
WHEN OLD.state='SETTLED' AND NEW.state IN ('CANCELLED','STALE','FAILED_STOPPED')
BEGIN
  SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.launch_id=OLD.id AND intent.state IN ('REQUESTED','DISCONNECTED')
      AND (NEW.state=intent.target_state OR NEW.state='FAILED_STOPPED'))
    THEN RAISE(ABORT,'settled acceptance internal launch requires active termination intent') END;
  SELECT CASE WHEN EXISTS (SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=OLD.candidate_run_id AND run.state='OPEN')
    THEN RAISE(ABORT,'settled acceptance internal run must be terminal') END;
  SELECT CASE WHEN EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch prompt
    WHERE prompt.internal_launch_id=OLD.id AND prompt.state NOT IN ('STOPPED','CANCELLED'))
    THEN RAISE(ABORT,'settled acceptance internal prompts must be terminal') END;
  SELECT CASE WHEN OLD.external_session_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=OLD.id AND cleanup.termination_intent_id IS NOT NULL)
    THEN RAISE(ABORT,'settled acceptance internal remote cleanup proof is missing') END;
  SELECT CASE WHEN EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=OLD.id AND cleanup.state<>'STOPPED')
    THEN RAISE(ABORT,'settled acceptance internal cleanup is not stopped') END;
END;
