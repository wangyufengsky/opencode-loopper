-- Upload intent, fixed parsed content, and durable availability are separate facts.
CREATE TABLE workflow_upload (
    id TEXT PRIMARY KEY,
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    plan_revision INTEGER NOT NULL,
    request_key TEXT NOT NULL UNIQUE,
    request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
    manifest_json TEXT NOT NULL CHECK(json_valid(manifest_json) AND length(CAST(manifest_json AS BLOB))<=16777216
        AND json_extract(manifest_json,'$.version')=1 AND json_extract(manifest_json,'$.type')='UPLOADED_DOCUMENTS'
        AND json_array_length(manifest_json,'$.originals') BETWEEN 1 AND 10
        AND json_array_length(manifest_json,'$.files') BETWEEN 2 AND 20490),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    summary_json TEXT NOT NULL CHECK(json_valid(summary_json)),
    created_at TEXT NOT NULL,
    FOREIGN KEY(requirement_id,plan_revision) REFERENCES workflow_plan_revision(requirement_id,revision)
);
CREATE INDEX idx_workflow_upload_page ON workflow_upload(requirement_id,created_at DESC,id DESC);
CREATE TABLE workflow_upload_file (
    upload_id TEXT NOT NULL REFERENCES workflow_upload(id),
    path TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 33554432),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    storage_path TEXT,
    content TEXT,
    CHECK((storage_path IS NULL AND content IS NOT NULL AND length(CAST(content AS BLOB))=size_bytes)
        OR (storage_path IS NOT NULL AND content IS NULL AND size_bytes<=20971520)),
    PRIMARY KEY(upload_id,path)
);
CREATE TABLE workflow_upload_ready (
    upload_id TEXT PRIMARY KEY REFERENCES workflow_upload(id),
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_upload_owner BEFORE INSERT ON workflow_upload
WHEN NOT EXISTS(SELECT 1 FROM workflow_requirement r WHERE r.id=NEW.requirement_id
    AND r.head_revision=NEW.plan_revision AND r.state IN ('PLANNING','PENDING_START')
    AND NOT EXISTS(SELECT 1 FROM workflow_input_snapshot p WHERE p.requirement_id=r.id))
BEGIN SELECT RAISE(ABORT,'workflow upload owner unavailable'); END;
CREATE TRIGGER trg_workflow_upload_file_owner BEFORE INSERT ON workflow_upload_file
WHEN EXISTS(SELECT 1 FROM workflow_upload_ready r WHERE r.upload_id=NEW.upload_id)
BEGIN SELECT RAISE(ABORT,'workflow upload already ready'); END;
CREATE TRIGGER trg_workflow_upload_ready_owner BEFORE INSERT ON workflow_upload_ready
WHEN NOT EXISTS(SELECT 1 FROM workflow_upload u JOIN workflow_requirement r ON r.id=u.requirement_id
    WHERE u.id=NEW.upload_id AND r.head_revision=u.plan_revision AND r.state IN ('PLANNING','PENDING_START')
    AND NOT EXISTS(SELECT 1 FROM workflow_input_snapshot p WHERE p.requirement_id=r.id)
    AND (SELECT count(*) FROM workflow_upload_file f WHERE f.upload_id=u.id)=json_array_length(u.manifest_json,'$.files')
    AND NOT EXISTS(SELECT 1 FROM json_each(u.manifest_json,'$.files') entry
        WHERE NOT EXISTS(SELECT 1 FROM workflow_upload_file f WHERE f.upload_id=u.id
            AND f.path=json_extract(entry.value,'$.path') AND f.sha256=json_extract(entry.value,'$.sha256')
            AND f.size_bytes=json_extract(entry.value,'$.sizeBytes'))))
BEGIN SELECT RAISE(ABORT,'workflow upload incomplete or owner changed'); END;
CREATE TRIGGER trg_workflow_upload_immutable BEFORE UPDATE ON workflow_upload
BEGIN SELECT RAISE(ABORT,'workflow upload is immutable'); END;
CREATE TRIGGER trg_workflow_upload_no_delete BEFORE DELETE ON workflow_upload
BEGIN SELECT RAISE(ABORT,'workflow upload history is immutable'); END;
CREATE TRIGGER trg_workflow_upload_file_immutable BEFORE UPDATE ON workflow_upload_file
BEGIN SELECT RAISE(ABORT,'workflow uploaded content is immutable'); END;
CREATE TRIGGER trg_workflow_upload_file_no_delete BEFORE DELETE ON workflow_upload_file
BEGIN SELECT RAISE(ABORT,'workflow uploaded content history is immutable'); END;
CREATE TRIGGER trg_workflow_upload_ready_immutable BEFORE UPDATE ON workflow_upload_ready
BEGIN SELECT RAISE(ABORT,'workflow upload availability is immutable'); END;
CREATE TRIGGER trg_workflow_upload_ready_no_delete BEFORE DELETE ON workflow_upload_ready
BEGIN SELECT RAISE(ABORT,'workflow upload availability history is immutable'); END;
