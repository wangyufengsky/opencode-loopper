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
    UNIQUE(accounting_session_id,phase)
);

INSERT INTO story_accounting_call_new SELECT * FROM story_accounting_call;
DROP TABLE story_accounting_call;
ALTER TABLE story_accounting_call_new RENAME TO story_accounting_call;
CREATE INDEX idx_story_accounting_call_state ON story_accounting_call(state,started_at);
CREATE TABLE story_accounting_activity (
    call_id TEXT PRIMARY KEY REFERENCES story_accounting_call(id) ON DELETE CASCADE,
    parts_json TEXT NOT NULL DEFAULT '[]',
    dismissed_at TEXT
);
-- Upgrading must not reopen historical receipts. Active calls are recovered as unknown at startup.
INSERT INTO story_accounting_activity(call_id,dismissed_at)
    SELECT id,finished_at FROM story_accounting_call WHERE finished_at IS NOT NULL;
