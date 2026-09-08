CREATE TABLE automation_poll_health (
    rule_id TEXT PRIMARY KEY REFERENCES automation_rule(id) ON DELETE CASCADE,
    rule_version INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('CHECKED','FAILED')),
    last_checked_at TEXT NOT NULL,
    last_success_at TEXT,
    consecutive_failures INTEGER NOT NULL CHECK(consecutive_failures >= 0),
    error_code TEXT,
    error_message TEXT
);
CREATE INDEX idx_task_monitor_active ON task(created_at DESC,id)
    WHERE state IN ('STOPPING','JUDGING','RUNNING');
CREATE INDEX idx_automation_reconcile_active ON automation_run(rule_id,detected_at,id)
    WHERE task_id IS NOT NULL AND state IN ('DETECTED','REVIEW_REQUIRED','QUEUED','RUNNING');
