CREATE TABLE designer_attachment_submission (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    scope_key TEXT NOT NULL,
    work_package_id TEXT,
    request_sha256 TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'PREPARED','STOPPED_OLD_SESSION','REMOTE_ACCEPTED','PUBLISHED','DELIVERY_UNKNOWN','FAILED')),
    old_external_session_id TEXT,
    new_external_session_id TEXT,
    external_message_id TEXT,
    error_code TEXT,
    error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE designer_attachment (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    designer_message_id TEXT NOT NULL REFERENCES designer_message(id) ON DELETE CASCADE,
    submission_id TEXT NOT NULL REFERENCES designer_attachment_submission(id) ON DELETE RESTRICT,
    scope_key TEXT NOT NULL,
    work_package_id TEXT,
    original_filename TEXT NOT NULL,
    detected_media_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    sha256 TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    extractor_id TEXT,
    extractor_version TEXT,
    extracted_media_type TEXT,
    extracted_size_bytes INTEGER,
    extracted_sha256 TEXT,
    extracted_relative_path TEXT,
    preview_kind TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE','SUPERSEDED','STOPPED','FROZEN')),
    superseded_by_attachment_id TEXT REFERENCES designer_attachment(id) ON DELETE RESTRICT,
    sent_at TEXT,
    stopped_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_designer_attachment_active_name
    ON designer_attachment(designer_session_id,scope_key,original_filename COLLATE BINARY)
    WHERE state='ACTIVE';
CREATE INDEX idx_designer_attachment_message ON designer_attachment(designer_message_id,created_at);
CREATE INDEX idx_designer_attachment_session_state ON designer_attachment(designer_session_id,state,scope_key);

CREATE TABLE task_design_attachment (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    source_designer_attachment_id TEXT NOT NULL REFERENCES designer_attachment(id) ON DELETE RESTRICT,
    source_task_id TEXT REFERENCES task(id) ON DELETE RESTRICT,
    original_filename TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    work_package_id TEXT,
    detected_media_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    extractor_id TEXT,
    extractor_version TEXT,
    extracted_media_type TEXT,
    extracted_size_bytes INTEGER,
    extracted_sha256 TEXT,
    extracted_relative_path TEXT,
    frozen_at TEXT NOT NULL,
    UNIQUE(task_id,scope_key,original_filename)
);
CREATE INDEX idx_task_design_attachment_task ON task_design_attachment(task_id,scope_key,original_filename);
