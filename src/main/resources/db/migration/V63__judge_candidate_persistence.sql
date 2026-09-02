-- JUDGE_DECISION_V1 freezes the exact two-role review generation before any
-- remote create/prompt I/O. Accepted model candidates are recompiled by the
-- server and remain immutable; settlement additionally requires V59's
-- positive remote-stop proof.
CREATE TABLE judge_review_batch (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    execution_cycle_id TEXT NOT NULL
        REFERENCES task_execution_cycle(id) ON DELETE CASCADE,
    final_attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
    generation INTEGER NOT NULL CHECK (generation>0),
    state TEXT NOT NULL CHECK (state IN (
        'RUNNING','COMPLETED','WAITING_INPUT','CANCELLED')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    ended_at TEXT,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version>=0),
    UNIQUE(task_id,generation)
);

CREATE INDEX idx_judge_review_batch_task_generation
  ON judge_review_batch(task_id,generation DESC,id);
CREATE UNIQUE INDEX ux_judge_review_batch_active_task
  ON judge_review_batch(task_id)
  WHERE state='RUNNING';

CREATE TRIGGER trg_judge_review_batch_insert_anchor
BEFORE INSERT ON judge_review_batch
BEGIN
  SELECT CASE WHEN NEW.state<>'RUNNING' OR NEW.version<>0 OR NEW.ended_at IS NOT NULL
    THEN RAISE(ABORT,'Judge review batch must start RUNNING') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM task owner
    JOIN task_execution_cycle cycle ON cycle.task_id=owner.id
    JOIN attempt final_attempt
      ON final_attempt.task_id=owner.id
    WHERE owner.id=NEW.task_id AND owner.state='JUDGING'
      AND cycle.id=NEW.execution_cycle_id AND cycle.state='RUNNING'
      AND final_attempt.id=NEW.final_attempt_id
      AND final_attempt.state='SUCCEEDED'
      AND (final_attempt.execution_cycle_id=cycle.id OR (
        cycle.cycle_type='FINAL_REVIEW' AND EXISTS (
          SELECT 1 FROM package_fact_snapshot fact
          WHERE fact.task_id=owner.id
            AND fact.successful_attempt_id=final_attempt.id))))
    THEN RAISE(ABORT,'Judge review batch owner anchor mismatch') END;
END;

CREATE TRIGGER trg_judge_review_batch_identity_immutable
BEFORE UPDATE ON judge_review_batch
WHEN NEW.id<>OLD.id OR NEW.task_id<>OLD.task_id
  OR NEW.execution_cycle_id<>OLD.execution_cycle_id
  OR NEW.final_attempt_id<>OLD.final_attempt_id
  OR NEW.generation<>OLD.generation OR NEW.created_at<>OLD.created_at
BEGIN
  SELECT RAISE(ABORT,'Judge review batch identity is immutable');
END;

CREATE TRIGGER trg_judge_review_batch_update_shape
BEFORE UPDATE ON judge_review_batch
WHEN NEW.id=OLD.id AND NEW.task_id=OLD.task_id
  AND NEW.execution_cycle_id=OLD.execution_cycle_id
  AND NEW.final_attempt_id=OLD.final_attempt_id
  AND NEW.generation=OLD.generation AND NEW.created_at=OLD.created_at
  AND (OLD.state<>'RUNNING'
    OR NEW.state NOT IN ('COMPLETED','WAITING_INPUT','CANCELLED')
    OR NEW.ended_at IS NULL
    OR NEW.updated_at=OLD.updated_at
    OR NEW.version<>OLD.version+1)
BEGIN
  SELECT RAISE(ABORT,'Judge review batch update requires one optimistic state step');
END;

ALTER TABLE judge_run
  ADD COLUMN review_batch_id TEXT
    REFERENCES judge_review_batch(id) ON DELETE CASCADE;
ALTER TABLE judge_run
  ADD COLUMN source_revision INTEGER
    CHECK (source_revision IS NULL OR source_revision>0);

CREATE INDEX idx_judge_run_batch_role
  ON judge_run(review_batch_id,role,ordinal DESC,id);

CREATE TRIGGER trg_judge_candidate_restart_identity_insert
BEFORE INSERT ON judge_run
BEGIN
  SELECT CASE
    WHEN (NEW.review_batch_id IS NULL)<>(NEW.source_revision IS NULL)
      THEN RAISE(ABORT,'Judge candidate restart identity is incomplete')
    WHEN NEW.review_batch_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM judge_review_batch batch
      WHERE batch.id=NEW.review_batch_id
        AND batch.task_id=NEW.task_id
        AND batch.final_attempt_id=NEW.attempt_id
        AND batch.generation=NEW.source_revision
        AND batch.state='RUNNING'
        AND NEW.role IN ('REQUIREMENT','RISK')
        AND NEW.state='CREATING'
        AND NEW.external_session_id IS NULL)
      THEN RAISE(ABORT,'Judge candidate restart identity owner mismatch')
  END;
END;

CREATE TRIGGER trg_judge_candidate_restart_identity_update
BEFORE UPDATE ON judge_run
WHEN NEW.review_batch_id IS NOT OLD.review_batch_id
  OR NEW.source_revision IS NOT OLD.source_revision
BEGIN
  SELECT RAISE(ABORT,'Judge candidate restart identity is immutable');
END;

CREATE TABLE judge_candidate_source_snapshot (
    -- The deterministic candidate run ID is allocated before remote create,
    -- therefore it cannot yet reference ai_candidate_submission_run.
    candidate_run_id TEXT PRIMARY KEY CHECK (length(candidate_run_id)>0),
    judge_run_id TEXT NOT NULL UNIQUE
        REFERENCES judge_run(id) ON DELETE CASCADE,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    execution_cycle_id TEXT NOT NULL
        REFERENCES task_execution_cycle(id) ON DELETE CASCADE,
    final_attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
    review_batch_id TEXT NOT NULL
        REFERENCES judge_review_batch(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('REQUIREMENT','RISK')),
    ordinal INTEGER NOT NULL CHECK (ordinal>0),
    source_revision INTEGER NOT NULL CHECK (source_revision>0),
    prepared_owner_version INTEGER NOT NULL CHECK (prepared_owner_version>=0),
    contract_version TEXT NOT NULL CHECK (contract_version='JUDGE_DECISION_V1'),
    source_prompt TEXT NOT NULL CHECK (
        length(source_prompt) BETWEEN 1 AND 131072),
    source_prompt_sha256 TEXT NOT NULL CHECK (length(source_prompt_sha256)=64),
    canonical_evidence_json TEXT NOT NULL CHECK (
        length(canonical_evidence_json) BETWEEN 2 AND 1048576
        AND json_valid(canonical_evidence_json)
        AND json_type(canonical_evidence_json)='object'),
    evidence_sha256 TEXT NOT NULL CHECK (length(evidence_sha256)=64),
    created_at TEXT NOT NULL,
    UNIQUE(review_batch_id,role,ordinal)
);

CREATE INDEX idx_judge_candidate_source_owner
  ON judge_candidate_source_snapshot(
    task_id,review_batch_id,role,source_revision,prepared_owner_version,created_at);

CREATE TRIGGER trg_judge_candidate_source_snapshot_insert
BEFORE INSERT ON judge_candidate_source_snapshot
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM judge_run owner
    JOIN judge_review_batch batch ON batch.id=owner.review_batch_id
    JOIN task_execution_cycle cycle ON cycle.id=batch.execution_cycle_id
    JOIN attempt final_attempt ON final_attempt.id=batch.final_attempt_id
    WHERE owner.id=NEW.judge_run_id
      AND owner.task_id=NEW.task_id
      AND owner.attempt_id=NEW.final_attempt_id
      AND owner.review_batch_id=NEW.review_batch_id
      AND owner.role=NEW.role AND owner.ordinal=NEW.ordinal
      AND owner.source_revision=NEW.source_revision
      AND owner.state='CREATING' AND owner.external_session_id IS NULL
      AND owner.version=NEW.prepared_owner_version
      AND owner.response_mode='INTERNAL_MCP'
      AND batch.task_id=NEW.task_id
      AND batch.execution_cycle_id=NEW.execution_cycle_id
      AND batch.final_attempt_id=NEW.final_attempt_id
      AND batch.generation=NEW.source_revision
      AND batch.state='RUNNING'
      AND cycle.task_id=NEW.task_id AND cycle.state='RUNNING'
      AND final_attempt.task_id=NEW.task_id
      AND final_attempt.state='SUCCEEDED'
      AND (final_attempt.execution_cycle_id=NEW.execution_cycle_id OR (
        cycle.cycle_type='FINAL_REVIEW' AND EXISTS (
          SELECT 1 FROM package_fact_snapshot fact
          WHERE fact.task_id=NEW.task_id
            AND fact.successful_attempt_id=final_attempt.id))))
    THEN RAISE(ABORT,'Judge source snapshot owner mismatch') END;
END;

CREATE TRIGGER trg_judge_candidate_source_snapshot_update
BEFORE UPDATE ON judge_candidate_source_snapshot
BEGIN
  SELECT RAISE(ABORT,'Judge source snapshot is immutable');
END;

CREATE TRIGGER trg_judge_candidate_launch_source_anchor
BEFORE INSERT ON ai_candidate_internal_launch
WHEN NEW.candidate_kind='JUDGE_DECISION_V1'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM judge_candidate_source_snapshot snapshot
    JOIN judge_run owner ON owner.id=snapshot.judge_run_id
    WHERE snapshot.candidate_run_id=NEW.candidate_run_id
      AND snapshot.judge_run_id=NEW.judge_run_id
      AND snapshot.task_id=NEW.task_id
      AND snapshot.source_revision=NEW.source_revision
      AND snapshot.prepared_owner_version=NEW.prepared_owner_version
      AND snapshot.contract_version=NEW.contract_version
      AND owner.task_id=NEW.task_id
      AND owner.review_batch_id=snapshot.review_batch_id
      AND owner.source_revision=snapshot.source_revision
      AND owner.role=snapshot.role AND owner.ordinal=snapshot.ordinal
      AND owner.state='CREATING' AND owner.external_session_id IS NULL
      AND owner.version=NEW.prepared_owner_version)
    THEN RAISE(ABORT,'Judge candidate launch requires exact frozen source snapshot') END;
END;

CREATE TRIGGER trg_judge_candidate_create_dispatch_source_gate
BEFORE UPDATE OF state,create_dispatch_attempted ON ai_candidate_internal_launch
WHEN OLD.candidate_kind='JUDGE_DECISION_V1'
  AND OLD.create_dispatch_attempted=0 AND NEW.create_dispatch_attempted=1
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM judge_candidate_source_snapshot snapshot
    JOIN judge_run owner ON owner.id=snapshot.judge_run_id
    JOIN judge_review_batch batch ON batch.id=snapshot.review_batch_id
    WHERE snapshot.candidate_run_id=NEW.candidate_run_id
      AND snapshot.judge_run_id=NEW.judge_run_id
      AND snapshot.task_id=NEW.task_id
      AND snapshot.source_revision=NEW.source_revision
      AND snapshot.prepared_owner_version=NEW.prepared_owner_version
      AND snapshot.contract_version=NEW.contract_version
      AND owner.task_id=NEW.task_id
      AND owner.attempt_id=snapshot.final_attempt_id
      AND owner.review_batch_id=snapshot.review_batch_id
      AND owner.source_revision=snapshot.source_revision
      AND owner.role=snapshot.role AND owner.ordinal=snapshot.ordinal
      AND owner.state='CREATING' AND owner.external_session_id IS NULL
      AND owner.version=NEW.prepared_owner_version
      AND batch.task_id=NEW.task_id
      AND batch.execution_cycle_id=snapshot.execution_cycle_id
      AND batch.final_attempt_id=snapshot.final_attempt_id
      AND batch.generation=snapshot.source_revision
      AND batch.state='RUNNING')
    THEN RAISE(ABORT,'Judge create dispatch requires exact frozen source snapshot') END;
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

CREATE TRIGGER trg_judge_candidate_initial_prompt_source_gate
BEFORE INSERT ON ai_candidate_prompt_dispatch
WHEN NEW.dispatch_kind='INITIAL' AND EXISTS (
  SELECT 1 FROM ai_candidate_submission_run run
  WHERE run.id=NEW.run_id AND run.candidate_kind='JUDGE_DECISION_V1'
    AND run.submission_channel='INTERNAL_MCP')
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM ai_candidate_submission_run run
    JOIN judge_candidate_source_snapshot snapshot
      ON snapshot.candidate_run_id=run.id
    WHERE run.id=NEW.run_id
      AND snapshot.judge_run_id=run.owner_id
      AND snapshot.task_id=run.task_id
      AND snapshot.source_revision=run.source_revision
      AND snapshot.prepared_owner_version+1=run.owner_version
      AND snapshot.contract_version=run.contract_version)
    THEN RAISE(ABORT,'Judge initial prompt requires exact frozen source snapshot') END;
END;

CREATE TABLE judge_candidate_accepted_result (
    candidate_run_id TEXT PRIMARY KEY
        REFERENCES ai_candidate_submission_run(id) ON DELETE CASCADE,
    judge_run_id TEXT NOT NULL UNIQUE
        REFERENCES judge_run(id) ON DELETE CASCADE,
    review_batch_id TEXT NOT NULL
        REFERENCES judge_review_batch(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('REQUIREMENT','RISK')),
    source_revision INTEGER NOT NULL CHECK (source_revision>0),
    owner_version INTEGER NOT NULL CHECK (owner_version>=0),
    contract_version TEXT NOT NULL CHECK (contract_version='JUDGE_DECISION_V1'),
    canonical_candidate_json TEXT NOT NULL CHECK (
        length(canonical_candidate_json) BETWEEN 2 AND 131072
        AND json_valid(canonical_candidate_json)
        AND json_type(canonical_candidate_json)='object'),
    candidate_payload_sha256 TEXT NOT NULL CHECK (length(candidate_payload_sha256)=64),
    canonical_decision_json TEXT NOT NULL CHECK (
        length(canonical_decision_json) BETWEEN 2 AND 524288
        AND json_valid(canonical_decision_json)
        AND json_type(canonical_decision_json)='object'),
    canonical_result_sha256 TEXT NOT NULL CHECK (length(canonical_result_sha256)=64),
    verdict TEXT NOT NULL CHECK (verdict IN ('PASS','REVISE','BLOCKED')),
    reason TEXT NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 131072),
    evidence_json TEXT NOT NULL CHECK (
        length(evidence_json) BETWEEN 2 AND 1048576
        AND json_valid(evidence_json)
        AND json_type(evidence_json)='array'),
    settled_judge_run_id TEXT REFERENCES judge_run(id) ON DELETE RESTRICT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version>=0),
    UNIQUE(review_batch_id,role,source_revision),
    FOREIGN KEY(candidate_run_id)
      REFERENCES judge_candidate_source_snapshot(candidate_run_id) ON DELETE CASCADE
);

CREATE INDEX idx_judge_candidate_result_unsettled
  ON judge_candidate_accepted_result(
    settled_judge_run_id,review_batch_id,role,created_at,candidate_run_id);

CREATE TRIGGER trg_judge_candidate_accepted_result_insert
BEFORE INSERT ON judge_candidate_accepted_result
BEGIN
  SELECT CASE
    WHEN NEW.settled_judge_run_id IS NOT NULL
      THEN RAISE(ABORT,'Judge accepted result must start unsettled')
    WHEN NOT EXISTS (
      SELECT 1
      FROM ai_candidate_submission_run run
      JOIN judge_candidate_source_snapshot snapshot
        ON snapshot.candidate_run_id=run.id
      JOIN judge_run owner ON owner.id=snapshot.judge_run_id
      JOIN judge_review_batch batch ON batch.id=snapshot.review_batch_id
      WHERE run.id=NEW.candidate_run_id
        AND run.candidate_kind='JUDGE_DECISION_V1'
        AND run.submission_channel='INTERNAL_MCP'
        AND run.workflow_step='JUDGE_DECISION_V1'
        AND run.owner_type='JUDGE_RUN'
        AND run.owner_id=NEW.judge_run_id
        AND run.task_id=snapshot.task_id
        AND run.source_revision=NEW.source_revision
        AND run.owner_version=NEW.owner_version
        AND run.contract_version=NEW.contract_version
        AND run.max_attempts=2
        AND run.state IN ('OPEN','ACCEPTED')
        AND snapshot.judge_run_id=NEW.judge_run_id
        AND snapshot.review_batch_id=NEW.review_batch_id
        AND snapshot.role=NEW.role
        AND snapshot.source_revision=NEW.source_revision
        AND snapshot.prepared_owner_version+1=NEW.owner_version
        AND snapshot.contract_version=NEW.contract_version
        AND owner.review_batch_id=NEW.review_batch_id
        AND owner.role=NEW.role
        AND owner.source_revision=NEW.source_revision
        AND owner.version=NEW.owner_version
        AND batch.id=NEW.review_batch_id
        AND batch.generation=NEW.source_revision)
      THEN RAISE(ABORT,'Judge accepted result run mismatch')
  END;
END;

CREATE TRIGGER trg_judge_candidate_accepted_result_update
BEFORE UPDATE ON judge_candidate_accepted_result
BEGIN
  SELECT CASE
    WHEN NEW.candidate_run_id IS NOT OLD.candidate_run_id
      OR NEW.judge_run_id IS NOT OLD.judge_run_id
      OR NEW.review_batch_id IS NOT OLD.review_batch_id
      OR NEW.role IS NOT OLD.role
      OR NEW.source_revision IS NOT OLD.source_revision
      OR NEW.owner_version IS NOT OLD.owner_version
      OR NEW.contract_version IS NOT OLD.contract_version
      OR NEW.canonical_candidate_json IS NOT OLD.canonical_candidate_json
      OR NEW.candidate_payload_sha256 IS NOT OLD.candidate_payload_sha256
      OR NEW.canonical_decision_json IS NOT OLD.canonical_decision_json
      OR NEW.canonical_result_sha256 IS NOT OLD.canonical_result_sha256
      OR NEW.verdict IS NOT OLD.verdict
      OR NEW.reason IS NOT OLD.reason
      OR NEW.evidence_json IS NOT OLD.evidence_json
      OR NEW.created_at IS NOT OLD.created_at
      THEN RAISE(ABORT,'Judge accepted result payload is immutable')
    WHEN OLD.settled_judge_run_id IS NOT NULL
      AND NEW.settled_judge_run_id IS NOT OLD.settled_judge_run_id
      THEN RAISE(ABORT,'Judge accepted result settlement is irreversible')
    WHEN OLD.settled_judge_run_id IS NULL
      AND NEW.settled_judge_run_id IS NOT NULL
      AND NEW.settled_judge_run_id<>NEW.judge_run_id
      THEN RAISE(ABORT,'Judge accepted result settlement owner mismatch')
    WHEN OLD.settled_judge_run_id IS NULL
      AND NEW.settled_judge_run_id IS NOT NULL
      AND NEW.version<>OLD.version+1
      THEN RAISE(ABORT,'Judge accepted result settlement version mismatch')
    WHEN OLD.settled_judge_run_id IS NULL
      AND NEW.settled_judge_run_id IS NOT NULL
      AND NOT EXISTS (
        SELECT 1
        FROM ai_candidate_submission_run run
        JOIN ai_candidate_internal_launch launch
          ON launch.candidate_run_id=run.id
        JOIN ai_candidate_internal_termination_intent intent
          ON intent.launch_id=launch.id AND intent.candidate_run_id=run.id
        WHERE run.id=NEW.candidate_run_id
          AND run.state='ACCEPTED'
          AND run.submission_channel='INTERNAL_MCP'
          AND launch.candidate_kind='JUDGE_DECISION_V1'
          AND launch.judge_run_id=NEW.judge_run_id
          AND launch.state='COMPLETED'
          AND launch.termination_proof IN (
            'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')
          AND launch.proof_at IS NOT NULL
          AND intent.intent_kind='RUN_COMPLETED'
          AND intent.target_launch_state='COMPLETED'
          AND intent.state IN ('READY','COMPLETED'))
      THEN RAISE(ABORT,'Judge accepted result settlement requires positive remote stop proof')
    WHEN OLD.settled_judge_run_id IS NULL
      AND NEW.settled_judge_run_id IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM judge_run owner
        WHERE owner.id=NEW.judge_run_id
          AND owner.review_batch_id=NEW.review_batch_id
          AND owner.role=NEW.role
          AND owner.source_revision=NEW.source_revision
          AND owner.state='COMPLETED'
          AND owner.verdict=NEW.verdict
          AND owner.reason=NEW.reason
          AND owner.version=NEW.owner_version+1)
      THEN RAISE(ABORT,'Judge accepted result settlement requires completed Judge owner')
    WHEN NEW.settled_judge_run_id IS OLD.settled_judge_run_id
      AND (NEW.updated_at IS NOT OLD.updated_at OR NEW.version<>OLD.version)
      THEN RAISE(ABORT,'Judge accepted result only settlement may change')
  END;
END;

CREATE TRIGGER trg_judge_candidate_run_snapshot_cleanup
AFTER DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind='JUDGE_DECISION_V1'
BEGIN
  DELETE FROM judge_candidate_source_snapshot WHERE candidate_run_id=OLD.id;
END;
