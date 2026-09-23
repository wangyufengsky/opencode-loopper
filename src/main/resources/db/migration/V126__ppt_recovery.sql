-- Opt-in per generation: historical authorizations retain their original recovery policy.
CREATE TABLE ppt_generation_recovery (
  generation_id TEXT PRIMARY KEY REFERENCES ppt_generation(id),
  observed_attempt INTEGER NOT NULL DEFAULT -1,
  revision INTEGER NOT NULL DEFAULT 0,
  fingerprint TEXT NOT NULL DEFAULT '',
  failures INTEGER NOT NULL DEFAULT 0,
  retry_at TEXT,
  checkpoint_json TEXT NOT NULL DEFAULT '{}',
  error_code TEXT NOT NULL DEFAULT ''
);
CREATE TABLE ppt_agent_failure (
  run_id TEXT PRIMARY KEY REFERENCES ppt_agent_run(id),
  category TEXT NOT NULL,
  error_code TEXT NOT NULL,
  detail TEXT NOT NULL,
  created_at TEXT NOT NULL
);
