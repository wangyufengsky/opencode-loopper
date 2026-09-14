-- Durable upload identity and replan anchor. Originals, old requirements and executed packages remain immutable.
CREATE TABLE document_requirement_supplement (
    run_id TEXT NOT NULL REFERENCES document_template_run(id) ON DELETE CASCADE,
    request_key TEXT NOT NULL,
    request_sha256 TEXT NOT NULL,
    target_revision INTEGER NOT NULL CHECK(target_revision>1),
    base_run_version INTEGER NOT NULL,
    base_task_version INTEGER NOT NULL,
    base_package_id TEXT,
    base_package_version INTEGER NOT NULL,
    first_file_ordinal INTEGER NOT NULL,
    file_count INTEGER NOT NULL CHECK(file_count BETWEEN 1 AND 10),
    plan_revision_floor INTEGER NOT NULL,
    plan_id TEXT REFERENCES task_package_plan_revision(id) ON DELETE RESTRICT,
    upload_ready INTEGER NOT NULL DEFAULT 0 CHECK(upload_ready IN (0,1)),
    created_at TEXT NOT NULL,
    applied_at TEXT,
    PRIMARY KEY(run_id,request_key)
);
CREATE UNIQUE INDEX ux_document_supplement_pending ON document_requirement_supplement(run_id) WHERE applied_at IS NULL;
