-- Durable external intent for cancelling or replacing an Acceptance v7
-- internal launch.  The row is the crash-recovery authority; callers may not
-- infer intent from process-local arguments after restart.
CREATE TABLE acceptance_candidate_internal_termination_intent (
    id TEXT PRIMARY KEY,
    launch_id TEXT NOT NULL UNIQUE
      REFERENCES acceptance_candidate_internal_launch(id) ON DELETE RESTRICT,
    designer_session_id TEXT NOT NULL
      REFERENCES designer_session(id) ON DELETE RESTRICT,
    compilation_id TEXT NOT NULL
      REFERENCES loop_spec_compilation(id) ON DELETE RESTRICT,
    candidate_run_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('DESIGNER_CANCEL','OWNER_REPLACEMENT')),
    target_state TEXT NOT NULL CHECK (target_state IN ('CANCELLED','STALE')),
    archive_when_complete INTEGER NOT NULL DEFAULT 0 CHECK (archive_when_complete IN (0,1)),
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
              AND anchor_requirement_revision_id IS NULL AND anchor_discussion_revision IS NULL)
        OR (kind='OWNER_REPLACEMENT' AND target_state='STALE'
              AND archive_when_complete=0
              AND anchor_requirement_revision_id IS NOT NULL AND anchor_discussion_revision IS NOT NULL)),
    CHECK ((state IN ('READY','COMPLETED'))=(ready_at IS NOT NULL)),
    CHECK ((state='COMPLETED')=(completed_at IS NOT NULL))
);

CREATE INDEX idx_acceptance_internal_termination_recovery
    ON acceptance_candidate_internal_termination_intent(state,updated_at,id);
CREATE INDEX idx_acceptance_internal_termination_designer
    ON acceptance_candidate_internal_termination_intent(designer_session_id,state,id);

CREATE TRIGGER acceptance_internal_termination_insert_gate_v54
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
END;

CREATE TRIGGER acceptance_internal_termination_identity_immutable_v54
BEFORE UPDATE ON acceptance_candidate_internal_termination_intent
WHEN NEW.id<>OLD.id OR NEW.launch_id<>OLD.launch_id
  OR NEW.designer_session_id<>OLD.designer_session_id
  OR NEW.compilation_id<>OLD.compilation_id
  OR NEW.candidate_run_id<>OLD.candidate_run_id
  OR NEW.kind<>OLD.kind OR NEW.target_state<>OLD.target_state
  OR NEW.archive_when_complete<>OLD.archive_when_complete
  OR NEW.anchor_designer_version<>OLD.anchor_designer_version
  OR NEW.anchor_requirement_revision_id IS NOT OLD.anchor_requirement_revision_id
  OR NEW.anchor_discussion_revision IS NOT OLD.anchor_discussion_revision
  OR NEW.created_at<>OLD.created_at
BEGIN
  SELECT RAISE(ABORT,'acceptance internal termination identity is immutable');
END;

CREATE TRIGGER acceptance_internal_termination_fsm_v54
BEFORE UPDATE OF state ON acceptance_candidate_internal_termination_intent
WHEN NEW.state<>OLD.state AND NOT (
  (OLD.state='REQUESTED' AND NEW.state IN ('DISCONNECTED','READY')) OR
  (OLD.state='DISCONNECTED' AND NEW.state IN ('REQUESTED','READY')) OR
  (OLD.state='READY' AND NEW.state='COMPLETED'))
BEGIN
  SELECT RAISE(ABORT,'acceptance internal termination state transition invalid');
END;

CREATE TRIGGER acceptance_internal_termination_ready_gate_v54
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

CREATE TRIGGER acceptance_internal_termination_completed_gate_v54
BEFORE UPDATE OF state ON acceptance_candidate_internal_termination_intent
WHEN OLD.state<>NEW.state AND NEW.state='COMPLETED'
BEGIN
  SELECT CASE WHEN NEW.kind='DESIGNER_CANCEL' AND NOT EXISTS (
    SELECT 1 FROM designer_session designer
    WHERE designer.id=NEW.designer_session_id AND designer.state='CANCELLED'
  ) THEN RAISE(ABORT,'acceptance internal cancellation parent is not terminal') END;
  SELECT CASE WHEN NEW.kind='OWNER_REPLACEMENT' AND NOT EXISTS (
    SELECT 1 FROM design_requirement_revision revision
    WHERE revision.id=NEW.anchor_requirement_revision_id AND revision.state='SUPERSEDED'
  ) THEN RAISE(ABORT,'acceptance internal replacement parent is not superseded') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch launch
    WHERE launch.id=NEW.launch_id
      AND launch.state IN (NEW.target_state,'FAILED_STOPPED')
      AND NOT EXISTS (
        SELECT 1 FROM ai_candidate_submission_run run
        WHERE run.id=launch.candidate_run_id AND run.state<>'CLOSED')
      AND NOT EXISTS (
        SELECT 1 FROM ai_candidate_prompt_dispatch prompt
        WHERE prompt.internal_launch_id=launch.id
          AND prompt.state NOT IN ('STOPPED','CANCELLED'))
      AND NOT EXISTS (
        SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
        WHERE cleanup.launch_id=launch.id AND cleanup.state<>'STOPPED')
  ) THEN RAISE(ABORT,'acceptance internal termination completion is not quiescent') END;
END;

CREATE TRIGGER acceptance_internal_termination_no_delete_v54
BEFORE DELETE ON acceptance_candidate_internal_termination_intent
BEGIN
  SELECT RAISE(ABORT,'acceptance internal termination intent is durable');
END;

-- Existing V52 cleanup rows keep their historical meaning.
ALTER TABLE acceptance_candidate_internal_launch_cleanup_remote
  ADD COLUMN purpose TEXT NOT NULL DEFAULT 'LAUNCH_AMBIGUITY'
    CHECK (purpose IN ('LAUNCH_AMBIGUITY','TERMINATION_INTENT'));
ALTER TABLE acceptance_candidate_internal_launch_cleanup_remote
  ADD COLUMN termination_intent_id TEXT
    REFERENCES acceptance_candidate_internal_termination_intent(id) ON DELETE RESTRICT;

DROP TRIGGER acceptance_internal_launch_cleanup_parent_insert;
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

CREATE TRIGGER acceptance_internal_cleanup_v54_identity_immutable
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN NEW.purpose<>OLD.purpose
  OR NEW.termination_intent_id IS NOT OLD.termination_intent_id
BEGIN
  SELECT RAISE(ABORT,'acceptance internal cleanup purpose is immutable');
END;

-- An externally requested termination fences all new Acceptance INTERNAL_MCP
-- work while leaving the no-intent V53 settlement path byte-for-byte valid.
CREATE TRIGGER acceptance_internal_intent_run_insert_gate_v54
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
  AND NEW.submission_channel='INTERNAL_MCP'
  AND EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.candidate_run_id=NEW.id AND intent.state<>'COMPLETED')
BEGIN
  SELECT RAISE(ABORT,'active acceptance internal termination blocks run insert');
END;

CREATE TRIGGER acceptance_internal_intent_prompt_insert_gate_v54
BEFORE INSERT ON ai_candidate_prompt_dispatch
WHEN NEW.internal_launch_id IS NOT NULL AND EXISTS (
  SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
  WHERE intent.launch_id=NEW.internal_launch_id AND intent.state<>'COMPLETED')
BEGIN
  SELECT RAISE(ABORT,'active acceptance internal termination blocks prompt insert');
END;

CREATE TRIGGER acceptance_internal_intent_submission_insert_gate_v54
BEFORE INSERT ON ai_candidate_submission_attempt
WHEN NEW.outcome='REJECTED' AND EXISTS (
  SELECT 1 FROM ai_candidate_submission_run run
  JOIN acceptance_candidate_internal_termination_intent intent
    ON intent.candidate_run_id=run.id AND intent.state<>'COMPLETED'
  WHERE run.id=NEW.run_id AND run.submission_channel='INTERNAL_MCP')
BEGIN
  SELECT RAISE(ABORT,'active acceptance internal termination blocks submission insert');
END;

CREATE TRIGGER acceptance_internal_intent_prompt_progress_gate_v54
BEFORE UPDATE OF state ON ai_candidate_prompt_dispatch
WHEN NEW.state<>OLD.state AND NEW.state IN ('PROMPTING','ACKNOWLEDGED')
  AND NEW.internal_launch_id IS NOT NULL AND EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.launch_id=NEW.internal_launch_id AND intent.state<>'COMPLETED')
BEGIN
  SELECT RAISE(ABORT,'active acceptance internal termination blocks prompt progress');
END;

CREATE TRIGGER acceptance_internal_intent_run_progress_gate_v54
BEFORE UPDATE OF state ON ai_candidate_submission_run
WHEN OLD.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
  AND OLD.submission_channel='INTERNAL_MCP'
  AND NEW.state<>OLD.state
  AND NEW.state NOT IN ('ACCEPTED','WAITING_INPUT','FALLBACK_REQUIRED','CLOSED')
  AND EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.candidate_run_id=OLD.id AND intent.state<>'COMPLETED')
BEGIN
  SELECT RAISE(ABORT,'active acceptance internal termination blocks run progress');
END;

CREATE TRIGGER acceptance_internal_intent_launch_progress_gate_v54
BEFORE UPDATE OF state ON acceptance_candidate_internal_launch
WHEN NEW.state<>OLD.state AND NEW.state IN ('CREATING','CREATED','SETTLED')
  AND EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.launch_id=OLD.id AND intent.state<>'COMPLETED')
BEGIN
  SELECT RAISE(ABORT,'active acceptance internal termination blocks launch progress');
END;

-- V52 made settlement fields immutable and required them to be NULL outside
-- SETTLED.  V54 retains the immutable settlement certificate, but permits the
-- launch projection to clear those two fields only while completing a durable
-- externally requested termination.
DROP TRIGGER acceptance_internal_launch_settlement_once;
CREATE TRIGGER acceptance_internal_launch_settlement_once
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN OLD.settled_owner_version IS NOT NULL AND (
  NEW.settled_owner_version IS NOT OLD.settled_owner_version
  OR NEW.settled_at IS NOT OLD.settled_at)
BEGIN
  SELECT CASE WHEN NOT (
    OLD.state='SETTLED'
    AND NEW.state IN ('CANCELLED','STALE','FAILED_STOPPED')
    AND NEW.settled_owner_version IS NULL AND NEW.settled_at IS NULL
    AND EXISTS (
      SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
      WHERE intent.launch_id=OLD.id AND intent.state IN ('REQUESTED','DISCONNECTED')
        AND (NEW.state=intent.target_state OR NEW.state='FAILED_STOPPED'))
    AND EXISTS (
      SELECT 1 FROM acceptance_candidate_internal_launch_settlement_certificate certificate
      WHERE certificate.launch_id=OLD.id
        AND certificate.candidate_run_id=OLD.candidate_run_id
        AND certificate.settled_owner_version=OLD.settled_owner_version
        AND certificate.settled_at=OLD.settled_at)
  ) THEN RAISE(ABORT,'acceptance internal launch settlement is irreversible') END;
END;

-- Final database defense for a previously SETTLED launch.  It is deliberately
-- scoped to an external durable intent so normal V53 settlement is unaffected.
CREATE TRIGGER acceptance_internal_settled_terminal_gate_v54
BEFORE UPDATE OF state ON acceptance_candidate_internal_launch
WHEN OLD.state='SETTLED' AND NEW.state IN ('CANCELLED','STALE','FAILED_STOPPED')
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
    WHERE intent.launch_id=OLD.id AND intent.state IN ('REQUESTED','DISCONNECTED')
      AND (NEW.state=intent.target_state OR NEW.state='FAILED_STOPPED')
  ) THEN RAISE(ABORT,'settled acceptance internal launch requires active termination intent') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=OLD.candidate_run_id AND run.state='OPEN')
    THEN RAISE(ABORT,'settled acceptance internal run must be terminal') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch prompt
    WHERE prompt.internal_launch_id=OLD.id AND prompt.state NOT IN ('STOPPED','CANCELLED'))
    THEN RAISE(ABORT,'settled acceptance internal prompts must be terminal') END;
  SELECT CASE WHEN OLD.external_session_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=OLD.id AND cleanup.termination_intent_id IS NOT NULL)
    THEN RAISE(ABORT,'settled acceptance internal remote cleanup proof is missing') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=OLD.id AND cleanup.state<>'STOPPED')
    THEN RAISE(ABORT,'settled acceptance internal cleanup is not stopped') END;
END;
