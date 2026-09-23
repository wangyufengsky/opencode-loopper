CREATE TABLE source_development_promotion (
    source_revision_id TEXT PRIMARY KEY REFERENCES design_requirement_revision(id),
    run_id TEXT NOT NULL UNIQUE REFERENCES source_template_run(id),
    designer_id TEXT NOT NULL REFERENCES designer_session(id),
    target_revision_id TEXT NOT NULL UNIQUE,
    profile_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);
