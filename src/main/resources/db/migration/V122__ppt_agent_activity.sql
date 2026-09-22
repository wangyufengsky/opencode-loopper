CREATE TABLE ppt_agent_activity (
    run_id TEXT PRIMARY KEY REFERENCES ppt_agent_run(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    thinking_prefix TEXT NOT NULL DEFAULT '',
    thinking TEXT NOT NULL DEFAULT '',
    calls_json TEXT NOT NULL DEFAULT '[]'
);
