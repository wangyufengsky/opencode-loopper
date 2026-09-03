-- Explicit retries append a new call; old receipts, identities and dismissals remain immutable.
CREATE TABLE story_accounting_call_new (
    id TEXT PRIMARY KEY,
    accounting_session_id TEXT NOT NULL REFERENCES story_accounting_session(id) ON DELETE CASCADE,
    phase TEXT NOT NULL CHECK (phase IN ('BEGIN','COMPLETE')),
    message_id TEXT NOT NULL UNIQUE,
    operation TEXT NOT NULL CHECK (operation IN ('start','continue','complete')),
    arguments_text TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PREPARED','SUCCEEDED','FAILED','UNKNOWN','CANCELLING','CANCELLED')),
    plugin_run_id TEXT,
    result_text TEXT,
    error_code TEXT,
    error_detail TEXT,
    notification_emitted INTEGER NOT NULL DEFAULT 0 CHECK (notification_emitted IN (0,1)),
    started_at TEXT NOT NULL,
    finished_at TEXT,
    retry_of TEXT REFERENCES story_accounting_call_new(id)
);


INSERT INTO story_accounting_call_new SELECT *,NULL FROM story_accounting_call ORDER BY rowid;
CREATE TEMP TABLE story_activity_backup AS SELECT * FROM story_accounting_activity;
DROP TABLE story_accounting_activity;
DROP TABLE story_accounting_call;
ALTER TABLE story_accounting_call_new RENAME TO story_accounting_call;
CREATE INDEX idx_story_accounting_call_state ON story_accounting_call(state,started_at);
CREATE UNIQUE INDEX idx_story_accounting_retry_once ON story_accounting_call(retry_of) WHERE retry_of IS NOT NULL;
CREATE UNIQUE INDEX idx_story_accounting_active_call ON story_accounting_call(accounting_session_id)
    WHERE state IN ('PREPARED','CANCELLING');
CREATE TABLE story_accounting_activity (
    call_id TEXT PRIMARY KEY REFERENCES story_accounting_call(id) ON DELETE CASCADE,
    parts_json TEXT NOT NULL DEFAULT '[]',
    dismissed_at TEXT
);
INSERT INTO story_accounting_activity SELECT * FROM story_activity_backup;
DROP TABLE story_activity_backup;
