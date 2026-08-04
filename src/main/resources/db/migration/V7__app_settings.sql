CREATE TABLE app_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    cli_path TEXT NOT NULL,
    allowed_root TEXT NOT NULL DEFAULT '',
    provider_id TEXT NOT NULL,
    model_id TEXT NOT NULL,
    max_task_attempts INTEGER NOT NULL,
    attempt_timeout_minutes INTEGER NOT NULL,
    auto_approve INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);
