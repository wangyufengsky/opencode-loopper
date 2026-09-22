CREATE TABLE ppt_document (
 id TEXT PRIMARY KEY, title TEXT NOT NULL, project_id TEXT REFERENCES project(id), model TEXT NOT NULL,
 phase TEXT NOT NULL CHECK(phase IN ('BRIEFING','DIRECTION','DESIGN','PRODUCING','REVIEW','EXPORTED')),
 revision INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0,
 archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
 create_digest TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX ppt_document_history ON ppt_document(archived,updated_at,id);
CREATE TABLE ppt_revision (
 document_id TEXT NOT NULL REFERENCES ppt_document(id), revision INTEGER NOT NULL,
 deck_json TEXT NOT NULL, plan_json TEXT NOT NULL, reason TEXT NOT NULL, created_at TEXT NOT NULL,
 PRIMARY KEY(document_id,revision)
);
CREATE TABLE ppt_edit_receipt (
 document_id TEXT NOT NULL REFERENCES ppt_document(id), request_key TEXT NOT NULL,
 digest TEXT NOT NULL, result_json TEXT NOT NULL, created_at TEXT NOT NULL,
 PRIMARY KEY(document_id,request_key)
);
CREATE TABLE ppt_resource (
 id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES ppt_document(id),
 kind TEXT NOT NULL CHECK(kind IN ('DOCUMENT','IMAGE')), name TEXT NOT NULL, media_type TEXT NOT NULL,
 storage_key TEXT NOT NULL, sha256 TEXT NOT NULL, bytes INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PREPARING','READY','FAILED')), detail TEXT NOT NULL DEFAULT '',
 body_json TEXT, request_key TEXT NOT NULL, digest TEXT NOT NULL, created_at TEXT NOT NULL,
 UNIQUE(document_id,request_key)
);
CREATE INDEX ppt_resource_document ON ppt_resource(document_id,kind,created_at,id);
CREATE TABLE ppt_job (
 id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES ppt_document(id),
 kind TEXT NOT NULL CHECK(kind IN ('PREVIEW','EXPORT')), revision INTEGER NOT NULL, slide_id TEXT,
 state TEXT NOT NULL CHECK(state IN ('PREPARED','RUNNING','COMPLETED','FAILED','CANCELLED')),
 completed INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL,detail TEXT NOT NULL DEFAULT '',
 request_key TEXT NOT NULL,digest TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(document_id,request_key),FOREIGN KEY(document_id,revision) REFERENCES ppt_revision(document_id,revision)
);
CREATE INDEX ppt_job_document ON ppt_job(document_id,created_at,id);
CREATE TABLE ppt_artifact (
 id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES ppt_document(id),job_id TEXT NOT NULL REFERENCES ppt_job(id),
 revision INTEGER NOT NULL,slide_id TEXT,name TEXT NOT NULL,media_type TEXT NOT NULL,storage_key TEXT NOT NULL,
 sha256 TEXT NOT NULL,bytes INTEGER NOT NULL,created_at TEXT NOT NULL,
 UNIQUE(job_id,storage_key)
);
CREATE INDEX ppt_artifact_job ON ppt_artifact(document_id,job_id,created_at,id);
