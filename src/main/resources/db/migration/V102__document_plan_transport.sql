CREATE TABLE document_plan_transport (
    plan_id TEXT PRIMARY KEY REFERENCES task_package_plan_revision(id) ON DELETE CASCADE,
    creation_plan_json TEXT NOT NULL,
    prompt_json TEXT,
    prompt_sha256 TEXT,
    created_at TEXT NOT NULL,
    CHECK ((prompt_json IS NULL)=(prompt_sha256 IS NULL))
);
