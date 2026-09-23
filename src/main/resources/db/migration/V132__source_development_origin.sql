CREATE TABLE source_development_design (
    requirement_revision_id TEXT PRIMARY KEY REFERENCES design_requirement_revision(id),
    run_id TEXT NOT NULL REFERENCES source_template_run(id),
    manifest_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE source_development_task (
    task_id TEXT PRIMARY KEY REFERENCES task(id),
    run_id TEXT NOT NULL REFERENCES source_template_run(id),
    manifest_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE source_development_plan (
    plan_revision_id TEXT PRIMARY KEY REFERENCES task_package_plan_revision(id),
    run_id TEXT NOT NULL REFERENCES source_template_run(id),
    manifest_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE source_test_profile (
    run_id TEXT PRIMARY KEY REFERENCES source_template_run(id),
    profile_json TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
