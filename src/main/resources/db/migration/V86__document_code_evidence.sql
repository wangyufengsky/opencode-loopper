-- Frozen trees and exact read receipts, independent of source working-tree state.
CREATE TABLE document_code_file (
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 path TEXT NOT NULL,
 blob_sha TEXT NOT NULL,
 mode TEXT NOT NULL,
 size_bytes INTEGER NOT NULL,
 limitation TEXT,
 PRIMARY KEY(run_id,path)
);
CREATE TABLE document_code_read (
 model_id TEXT NOT NULL REFERENCES document_template_model_run(id),
 path TEXT NOT NULL,
 blob_sha TEXT NOT NULL,
 start_line INTEGER NOT NULL,
 end_line INTEGER NOT NULL,
 content TEXT NOT NULL,
 sha256 TEXT NOT NULL,
 created_at TEXT NOT NULL,
 PRIMARY KEY(model_id,path,start_line,end_line)
);
CREATE INDEX idx_document_code_read_model ON document_code_read(model_id,path);
