CREATE TABLE document_requirement_clarification (
    run_id TEXT NOT NULL REFERENCES document_template_run(id),
    revision INTEGER NOT NULL CHECK(revision>1),
    previous_revision INTEGER NOT NULL CHECK(previous_revision>0),
    request_key TEXT NOT NULL,
    request_sha256 TEXT NOT NULL,
    answers_json TEXT NOT NULL CHECK(json_valid(answers_json)),
    created_at TEXT NOT NULL,
    PRIMARY KEY(run_id, revision),
    UNIQUE(run_id, request_key),
    FOREIGN KEY(run_id, previous_revision) REFERENCES document_requirement_revision(run_id, revision)
);
