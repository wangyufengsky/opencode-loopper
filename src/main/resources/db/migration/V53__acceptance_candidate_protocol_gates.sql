-- Close the Acceptance v7 internal-launch protocol at the SQLite boundary.
-- A deferred requirement/certificate pair makes a run INSERT visible to the
-- launch settlement statement while still rejecting a transaction that tries
-- to commit between those two steps.
ALTER TABLE ai_candidate_prompt_dispatch
    ADD COLUMN internal_launch_id TEXT
      REFERENCES acceptance_candidate_internal_launch(id) ON DELETE RESTRICT;

CREATE INDEX idx_candidate_prompt_dispatch_internal_launch
    ON ai_candidate_prompt_dispatch(internal_launch_id,state,id);

CREATE TABLE acceptance_candidate_internal_launch_settlement_certificate (
    launch_id TEXT NOT NULL,
    candidate_run_id TEXT NOT NULL,
    settled_owner_version INTEGER NOT NULL CHECK (settled_owner_version >= 0),
    settled_at TEXT NOT NULL,
    PRIMARY KEY(launch_id,candidate_run_id),
    UNIQUE(launch_id),
    UNIQUE(candidate_run_id),
    FOREIGN KEY(launch_id)
      REFERENCES acceptance_candidate_internal_launch(id) ON DELETE RESTRICT,
    FOREIGN KEY(candidate_run_id)
      REFERENCES ai_candidate_submission_run(id) ON DELETE RESTRICT
);

CREATE TABLE acceptance_candidate_internal_launch_run_requirement (
    candidate_run_id TEXT PRIMARY KEY,
    launch_id TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    FOREIGN KEY(candidate_run_id)
      REFERENCES ai_candidate_submission_run(id) ON DELETE RESTRICT,
    FOREIGN KEY(launch_id)
      REFERENCES acceptance_candidate_internal_launch(id) ON DELETE RESTRICT,
    FOREIGN KEY(launch_id,candidate_run_id)
      REFERENCES acceptance_candidate_internal_launch_settlement_certificate(launch_id,candidate_run_id)
      ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER acceptance_candidate_run_launch_gate_v53
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
BEGIN
  SELECT CASE WHEN NEW.submission_channel='INTERNAL_MCP' AND NOT EXISTS (
    SELECT 1
    FROM acceptance_candidate_internal_launch launch
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
      AND NEW.state='OPEN'
      AND NEW.attempts_used=0
      AND NEW.max_attempts=2
      AND NOT EXISTS (
        SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
        WHERE cleanup.launch_id=launch.id)
  ) THEN RAISE(ABORT,'Acceptance INTERNAL_MCP run requires its exact CREATED internal launch gate') END;

  SELECT CASE WHEN NEW.submission_channel='IN_PROCESS_LEGACY' AND NOT EXISTS (
    SELECT 1
    FROM acceptance_candidate_legacy_handoff handoff
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
      AND NEW.state='OPEN'
      AND NEW.attempts_used=0
      AND NEW.max_attempts=2
      AND NOT EXISTS (
        SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
        WHERE cleanup.handoff_id=handoff.id AND cleanup.state<>'STOPPED')
  ) THEN RAISE(ABORT,'Acceptance IN_PROCESS_LEGACY run requires its exact LEGACY_CREATED handoff gate') END;

  SELECT CASE WHEN NEW.submission_channel NOT IN ('INTERNAL_MCP','IN_PROCESS_LEGACY')
    THEN RAISE(ABORT,'Acceptance run submission channel is outside the closed compatibility set') END;
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

CREATE TRIGGER acceptance_internal_launch_settlement_gate_v53
BEFORE UPDATE OF state ON acceptance_candidate_internal_launch
WHEN NEW.state='SETTLED' AND OLD.state<>'SETTLED'
BEGIN
  SELECT CASE WHEN OLD.state<>'CREATED'
    THEN RAISE(ABORT,'Acceptance internal launch settlement requires CREATED') END;
  SELECT CASE WHEN EXISTS (
      SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
      WHERE cleanup.launch_id=NEW.id)
    THEN RAISE(ABORT,'Acceptance internal launch settlement forbids cleanup remotes') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM ai_candidate_submission_run run
    JOIN loop_spec_compilation compilation ON compilation.id=NEW.compilation_id
    JOIN acceptance_candidate_internal_launch_run_requirement requirement
      ON requirement.candidate_run_id=run.id AND requirement.launch_id=NEW.id
    WHERE run.id=NEW.candidate_run_id
      AND run.designer_session_id=NEW.designer_session_id
      AND run.owner_type='LOOP_SPEC_COMPILATION'
      AND run.owner_id=NEW.compilation_id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.workflow_step=NEW.workflow_step
      AND run.source_revision=NEW.source_design_revision
      AND run.owner_version=NEW.settled_owner_version
      AND run.submission_channel='INTERNAL_MCP'
      AND run.contract_version=NEW.contract_version
      AND run.runtime_generation_id=NEW.runtime_generation_id
      AND run.external_session_id=NEW.external_session_id
      AND run.state='OPEN'
      AND run.max_attempts=2
      AND run.attempts_used=0
      AND NEW.settled_owner_version=NEW.prepared_owner_version+1
      AND compilation.designer_session_id=NEW.designer_session_id
      AND compilation.work_package_id=NEW.work_package_id
      AND compilation.design_revision=NEW.source_design_revision
      AND compilation.source_design_message_id=NEW.source_design_message_id
      AND compilation.source_draft_version=NEW.source_draft_version
      AND compilation.state='RUNNING'
      AND compilation.external_session_id=NEW.external_session_id
      AND compilation.external_session_state='CANDIDATE_PROMPT_PENDING'
      AND compilation.version=NEW.settled_owner_version
  ) THEN RAISE(ABORT,'Acceptance internal launch settlement requires its exact OPEN run and RUNNING owner') END;
END;

CREATE TRIGGER acceptance_internal_launch_settlement_certificate_v53
AFTER UPDATE OF state ON acceptance_candidate_internal_launch
WHEN NEW.state='SETTLED' AND OLD.state<>'SETTLED'
BEGIN
  INSERT INTO acceptance_candidate_internal_launch_settlement_certificate(
    launch_id,candidate_run_id,settled_owner_version,settled_at)
  VALUES(NEW.id,NEW.candidate_run_id,NEW.settled_owner_version,NEW.settled_at);
END;

CREATE TRIGGER acceptance_internal_requirement_insert_gate_v53
BEFORE INSERT ON acceptance_candidate_internal_launch_run_requirement
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    JOIN acceptance_candidate_internal_launch launch
      ON launch.id=NEW.launch_id AND launch.candidate_run_id=run.id
    WHERE run.id=NEW.candidate_run_id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.submission_channel='INTERNAL_MCP'
      AND run.state='OPEN'
      AND launch.state='CREATED'
  ) THEN RAISE(ABORT,'Acceptance internal launch run requirement cannot be forged') END;
END;

CREATE TRIGGER acceptance_internal_certificate_insert_gate_v53
BEFORE INSERT ON acceptance_candidate_internal_launch_settlement_certificate
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch launch
    JOIN ai_candidate_submission_run run ON run.id=NEW.candidate_run_id
    JOIN acceptance_candidate_internal_launch_run_requirement requirement
      ON requirement.launch_id=launch.id AND requirement.candidate_run_id=run.id
    WHERE launch.id=NEW.launch_id
      AND launch.candidate_run_id=run.id
      AND launch.state='SETTLED'
      AND launch.settled_owner_version=NEW.settled_owner_version
      AND launch.settled_at=NEW.settled_at
      AND run.state='OPEN'
  ) THEN RAISE(ABORT,'Acceptance internal launch settlement certificate cannot be forged') END;
END;

CREATE TRIGGER acceptance_internal_requirement_immutable_v53
BEFORE UPDATE ON acceptance_candidate_internal_launch_run_requirement
BEGIN
  SELECT RAISE(ABORT,'Acceptance internal launch run requirement is immutable');
END;
CREATE TRIGGER acceptance_internal_requirement_delete_v53
BEFORE DELETE ON acceptance_candidate_internal_launch_run_requirement
BEGIN
  SELECT RAISE(ABORT,'Acceptance internal launch run requirement cannot be deleted');
END;
CREATE TRIGGER acceptance_internal_certificate_immutable_v53
BEFORE UPDATE ON acceptance_candidate_internal_launch_settlement_certificate
BEGIN
  SELECT RAISE(ABORT,'Acceptance internal launch settlement certificate is immutable');
END;
CREATE TRIGGER acceptance_internal_certificate_delete_v53
BEFORE DELETE ON acceptance_candidate_internal_launch_settlement_certificate
BEGIN
  SELECT RAISE(ABORT,'Acceptance internal launch settlement certificate cannot be deleted');
END;

CREATE TRIGGER candidate_prompt_dispatch_launch_gate_v53
BEFORE INSERT ON ai_candidate_prompt_dispatch
BEGIN
  SELECT CASE WHEN NEW.internal_launch_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=NEW.run_id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.submission_channel='INTERNAL_MCP'
  ) THEN RAISE(ABORT,'internal_launch_id is reserved for Acceptance INTERNAL_MCP prompts') END;

  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=NEW.run_id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.submission_channel='INTERNAL_MCP'
  ) AND NOT EXISTS (
    SELECT 1
    FROM ai_candidate_submission_run run
    JOIN acceptance_candidate_internal_launch launch ON launch.id=NEW.internal_launch_id
    JOIN acceptance_candidate_internal_launch_settlement_certificate certificate
      ON certificate.launch_id=launch.id AND certificate.candidate_run_id=run.id
    JOIN acceptance_candidate_internal_launch_run_requirement requirement
      ON requirement.launch_id=launch.id AND requirement.candidate_run_id=run.id
    WHERE run.id=NEW.run_id
      AND run.state='OPEN'
      AND launch.state='SETTLED'
      AND launch.candidate_run_id=run.id
      AND launch.compilation_id=run.owner_id
      AND launch.designer_session_id=run.designer_session_id
      AND launch.source_design_revision=run.source_revision
      AND launch.settled_owner_version=run.owner_version
      AND launch.contract_version=run.contract_version
      AND launch.workflow_step=run.workflow_step
      AND launch.external_session_id=NEW.external_session_id
      AND launch.external_session_id=run.external_session_id
      AND launch.runtime_generation_id=NEW.runtime_generation_id
      AND launch.runtime_generation_id=run.runtime_generation_id
  ) THEN RAISE(ABORT,'Acceptance prompt requires its exact SETTLED internal launch gate') END;

  SELECT CASE WHEN NEW.dispatch_kind='CORRECTION' AND EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=NEW.run_id
      AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
      AND run.submission_channel='INTERNAL_MCP'
  ) AND NOT EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch initial
    WHERE initial.run_id=NEW.run_id
      AND initial.dispatch_kind='INITIAL'
      AND initial.internal_launch_id=NEW.internal_launch_id
      AND initial.state='ACKNOWLEDGED'
      AND initial.acknowledged=1
      AND initial.external_session_id=NEW.external_session_id
      AND initial.runtime_generation_id=NEW.runtime_generation_id
  ) THEN RAISE(ABORT,'Acceptance correction requires acknowledged INITIAL with same SETTLED internal launch') END;
END;

CREATE TRIGGER candidate_prompt_dispatch_launch_immutable_v53
BEFORE UPDATE OF internal_launch_id ON ai_candidate_prompt_dispatch
WHEN NEW.internal_launch_id IS NOT OLD.internal_launch_id
BEGIN
  SELECT RAISE(ABORT,'candidate prompt internal launch is immutable');
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

CREATE TRIGGER acceptance_internal_launch_live_delete_v53
BEFORE DELETE ON acceptance_candidate_internal_launch
WHEN OLD.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE')
  OR EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=OLD.candidate_run_id AND run.state='OPEN')
  OR EXISTS (
    SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
    WHERE dispatch.internal_launch_id=OLD.id AND dispatch.state NOT IN ('STOPPED','CANCELLED'))
  OR EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=OLD.id AND cleanup.state<>'STOPPED')
BEGIN
  SELECT RAISE(ABORT,'live Acceptance internal launch cannot be deleted');
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

CREATE TRIGGER candidate_prompt_dispatch_live_delete_v53
BEFORE DELETE ON ai_candidate_prompt_dispatch
WHEN OLD.state NOT IN ('STOPPED','CANCELLED')
BEGIN
  SELECT RAISE(ABORT,'live candidate prompt dispatch cannot be deleted');
END;

CREATE TRIGGER acceptance_internal_cleanup_live_delete_v53
BEFORE DELETE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN OLD.state<>'STOPPED'
BEGIN
  SELECT RAISE(ABORT,'live Acceptance internal cleanup cannot be deleted');
END;

CREATE TRIGGER acceptance_legacy_handoff_live_delete_v53
BEFORE DELETE ON acceptance_candidate_legacy_handoff
WHEN OLD.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
BEGIN
  SELECT RAISE(ABORT,'live Acceptance legacy handoff cannot be deleted');
END;

CREATE TRIGGER acceptance_legacy_cleanup_live_delete_v53
BEFORE DELETE ON acceptance_candidate_handoff_cleanup_remote
WHEN OLD.state<>'STOPPED'
BEGIN
  SELECT RAISE(ABORT,'live Acceptance legacy cleanup cannot be deleted');
END;

CREATE TRIGGER acceptance_compilation_terminal_guard_v53
BEFORE UPDATE OF state ON loop_spec_compilation
WHEN NEW.state IN ('DESIGN_INCOMPLETE','COMPLETED','SESSION_ERROR')
  AND (
    EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch launch
      WHERE launch.compilation_id=OLD.id
        AND launch.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
    OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
      JOIN acceptance_candidate_internal_launch launch ON launch.id=cleanup.launch_id
      WHERE launch.compilation_id=OLD.id AND cleanup.state<>'STOPPED')
    OR EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
      WHERE handoff.compilation_id=OLD.id
        AND handoff.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
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
      WHERE launch.compilation_id=OLD.id
        AND launch.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
      JOIN acceptance_candidate_internal_launch launch ON launch.id=cleanup.launch_id
      WHERE launch.compilation_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
      WHERE handoff.compilation_id=OLD.id
        AND handoff.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
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
WHEN NEW.state IN ('COMPLETED','SESSION_ERROR','CANCELLED')
  AND (
    EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch launch
      WHERE launch.designer_session_id=OLD.id
        AND launch.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
    OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
      JOIN acceptance_candidate_internal_launch launch ON launch.id=cleanup.launch_id
      WHERE launch.designer_session_id=OLD.id AND cleanup.state<>'STOPPED')
    OR EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
      WHERE handoff.designer_session_id=OLD.id
        AND handoff.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
    OR EXISTS (SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
      JOIN acceptance_candidate_legacy_handoff handoff ON handoff.id=cleanup.handoff_id
      WHERE handoff.designer_session_id=OLD.id AND cleanup.state<>'STOPPED')
    OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
      WHERE run.designer_session_id=OLD.id
        AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND run.state='OPEN')
    OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
      JOIN ai_candidate_submission_run run ON run.id=dispatch.run_id
      WHERE run.designer_session_id=OLD.id
        AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
        AND dispatch.state NOT IN ('STOPPED','CANCELLED')))
BEGIN
  SELECT RAISE(ABORT,'parent terminal state has nonterminal Acceptance candidate protocol');
END;

CREATE TRIGGER acceptance_designer_delete_guard_v53
BEFORE DELETE ON designer_session
WHEN EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch launch
      WHERE launch.designer_session_id=OLD.id
        AND launch.state NOT IN ('FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
      JOIN acceptance_candidate_internal_launch launch ON launch.id=cleanup.launch_id
      WHERE launch.designer_session_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
      WHERE handoff.designer_session_id=OLD.id
        AND handoff.state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE'))
  OR EXISTS (SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
      JOIN acceptance_candidate_legacy_handoff handoff ON handoff.id=cleanup.handoff_id
      WHERE handoff.designer_session_id=OLD.id AND cleanup.state<>'STOPPED')
  OR EXISTS (SELECT 1 FROM ai_candidate_submission_run run
      WHERE run.designer_session_id=OLD.id
        AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7' AND run.state='OPEN')
  OR EXISTS (SELECT 1 FROM ai_candidate_prompt_dispatch dispatch
      JOIN ai_candidate_submission_run run ON run.id=dispatch.run_id
      WHERE run.designer_session_id=OLD.id
        AND run.candidate_kind='ACCEPTANCE_CLOSED_CHOICE_V7'
        AND dispatch.state NOT IN ('STOPPED','CANCELLED'))
BEGIN
  SELECT RAISE(ABORT,'parent delete has nonterminal Acceptance candidate protocol');
END;
