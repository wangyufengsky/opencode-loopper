CREATE TABLE source_development_scope (
    external_session_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES source_template_run(id),
    owner_json TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE source_development_read (
    external_session_id TEXT NOT NULL REFERENCES source_development_scope(external_session_id),
    path TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    total_lines INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY(external_session_id,path,start_line,end_line)
);
