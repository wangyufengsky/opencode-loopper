ALTER TABLE app_settings ADD COLUMN settings_json TEXT NOT NULL DEFAULT '';

CREATE TABLE task_retry_schedule (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    stage_id TEXT NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    cause TEXT NOT NULL CHECK (cause IN ('RATE_LIMIT','SESSION','VERIFICATION')),
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    delay_seconds INTEGER NOT NULL CHECK (delay_seconds >= 0),
    due_at TEXT NOT NULL,
    remaining_seconds INTEGER,
    prompt TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('SCHEDULED','PAUSED','CLAIMED','CANCELLED')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_task_retry_schedule_active
    ON task_retry_schedule(task_id)
    WHERE state IN ('SCHEDULED','PAUSED');

CREATE INDEX idx_task_retry_schedule_due
    ON task_retry_schedule(state, due_at);
