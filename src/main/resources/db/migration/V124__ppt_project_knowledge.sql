CREATE TABLE ppt_knowledge_scope (
    document_id TEXT PRIMARY KEY REFERENCES ppt_document(id) ON DELETE CASCADE,
    project_id TEXT NOT NULL,
    project_name TEXT NOT NULL,
    selection_json TEXT NOT NULL,
    sources_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE ppt_knowledge_evidence (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES ppt_document(id) ON DELETE CASCADE,
    run_id TEXT NOT NULL REFERENCES ppt_agent_run(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    body_json TEXT NOT NULL,
    digest TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(run_id, message_id, digest),
    FOREIGN KEY(run_id,document_id) REFERENCES ppt_agent_run(id,document_id)
);
CREATE INDEX idx_ppt_knowledge_evidence_document ON ppt_knowledge_evidence(document_id, created_at, id);
