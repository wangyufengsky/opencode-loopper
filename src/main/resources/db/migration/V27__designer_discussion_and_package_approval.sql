-- Persist recoverable requirement/package discussions and make human review a
-- first-class state instead of overloading WAITING_INPUT.
ALTER TABLE designer_session ADD COLUMN discussion_scope TEXT NOT NULL DEFAULT 'REQUIREMENT';
ALTER TABLE designer_session ADD COLUMN discussion_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE designer_session ADD COLUMN candidate_sync_state TEXT NOT NULL DEFAULT 'NONE';

ALTER TABLE design_work_package ADD COLUMN approved_design_revision INTEGER;
ALTER TABLE design_work_package ADD COLUMN discussion_round_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE design_work_package ADD COLUMN invalidated_by_package_id TEXT;
ALTER TABLE design_work_package ADD COLUMN approved_at TEXT;

CREATE TABLE design_discussion_revision (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    requirement_revision INTEGER,
    scope_key TEXT NOT NULL,
    work_package_id TEXT,
    revision INTEGER NOT NULL,
    state TEXT NOT NULL,
    source_message_id TEXT REFERENCES designer_message(id),
    design_message_id TEXT REFERENCES designer_message(id),
    snapshot_markdown TEXT NOT NULL DEFAULT '',
    decision_log_json TEXT NOT NULL DEFAULT '[]',
    question_required INTEGER NOT NULL DEFAULT 1,
    question_answered INTEGER NOT NULL DEFAULT 0,
    question_retry_count INTEGER NOT NULL DEFAULT 0,
    candidate_compilation_id TEXT REFERENCES loop_spec_compilation(id),
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(designer_session_id, scope_key, revision)
);
CREATE INDEX idx_design_discussion_current
    ON design_discussion_revision(designer_session_id, scope_key, revision DESC);

-- Existing confirmed drafts/tasks keep their historical state. Only unfinished
-- design sessions are moved from automatic completion to explicit package review.
UPDATE design_work_package
SET state='REVIEWING'
WHERE state='COMPLETED'
  AND EXISTS (
      SELECT 1
      FROM designer_session session
      JOIN loop_draft draft ON draft.id=session.loop_draft_id
      WHERE session.id=design_work_package.designer_session_id
        AND draft.status<>'CONFIRMED'
  );

UPDATE designer_session
SET state='REVIEWING',
    workflow_phase='REVIEWING_PACKAGE',
    active_work_package_id=(
        SELECT package.package_id
        FROM design_work_package package
        WHERE package.designer_session_id=designer_session.id
          AND package.state='REVIEWING'
        ORDER BY package.ordinal
        LIMIT 1
    ),
    discussion_scope=COALESCE((
        SELECT package.package_id
        FROM design_work_package package
        WHERE package.designer_session_id=designer_session.id
          AND package.state='REVIEWING'
        ORDER BY package.ordinal
        LIMIT 1
    ), 'REQUIREMENT')
WHERE EXISTS (
    SELECT 1
    FROM loop_draft draft
    WHERE draft.id=designer_session.loop_draft_id
      AND draft.status<>'CONFIRMED'
)
  AND EXISTS (
    SELECT 1 FROM design_work_package package
    WHERE package.designer_session_id=designer_session.id
      AND package.state='REVIEWING'
  );

UPDATE design_requirement_revision
SET max_model_calls=96
WHERE state IN ('ACTIVE','WAITING_INPUT')
  AND max_model_calls<96;
