CREATE TABLE designer_timeout_policy (
    designer_id TEXT PRIMARY KEY REFERENCES designer_session(id) ON DELETE CASCADE,
    enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
    seconds INTEGER NOT NULL CHECK (seconds > 0)
);
