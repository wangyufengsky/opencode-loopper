CREATE TABLE document_supplement_design (
    run_id TEXT NOT NULL,
    request_key TEXT NOT NULL,
    designer_id TEXT NOT NULL REFERENCES designer_session(id),
    source_revision_id TEXT NOT NULL REFERENCES design_requirement_revision(id),
    target_revision_id TEXT NOT NULL UNIQUE,
    profile_json TEXT NOT NULL CHECK(json_valid(profile_json)),
    designer_version INTEGER NOT NULL,
    discussion_revision INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY(run_id,request_key),
    FOREIGN KEY(run_id,request_key) REFERENCES document_requirement_supplement(run_id,request_key)
);
