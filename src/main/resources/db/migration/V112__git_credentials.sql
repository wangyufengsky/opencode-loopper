CREATE TABLE git_credential (
    scope_key TEXT PRIMARY KEY,
    project_id TEXT UNIQUE REFERENCES project(id) ON DELETE CASCADE,
    mode TEXT NOT NULL CHECK (mode IN ('CUSTOM','INHERIT','DISABLED')),
    server_url TEXT,
    username TEXT,
    kind TEXT CHECK (kind IN ('TOKEN','PASSWORD')),
    secret_ref TEXT,
    version INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK ((scope_key='global' AND project_id IS NULL AND mode != 'INHERIT')
        OR (project_id IS NOT NULL AND scope_key='project:' || project_id AND mode != 'DISABLED')),
    CHECK (mode != 'CUSTOM' OR (server_url IS NOT NULL AND username IS NOT NULL AND kind IS NOT NULL AND secret_ref IS NOT NULL))
);
CREATE TABLE git_credential_audit (
    id TEXT PRIMARY KEY,
    scope_key TEXT NOT NULL,
    action TEXT NOT NULL,
    version INTEGER NOT NULL,
    occurred_at TEXT NOT NULL
);
