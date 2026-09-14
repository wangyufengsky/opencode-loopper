-- Single-package attempts also need an explicit immutable source after later document revisions.
CREATE TABLE document_development_task_source (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    run_id TEXT NOT NULL REFERENCES document_template_run(id) ON DELETE CASCADE,
    document_revision INTEGER NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (run_id, document_revision) REFERENCES document_requirement_revision(run_id, revision)
);
INSERT INTO document_development_task_source(task_id,run_id,document_revision,manifest_sha256,created_at)
SELECT d.task_id,d.id,b.document_revision,b.manifest_sha256,t.created_at
FROM document_template_run d JOIN task t ON t.id=d.task_id
JOIN designer_session s ON s.id=d.designer_id
JOIN design_requirement_revision r ON r.designer_session_id=s.id AND r.revision=s.current_requirement_revision
JOIN document_development_design b ON b.requirement_revision_id=r.id AND b.run_id=d.id;
