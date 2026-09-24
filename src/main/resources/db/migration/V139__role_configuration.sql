CREATE TABLE role_definition (
    role_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL CHECK (length(trim(display_name))>0),
    description TEXT NOT NULL DEFAULT '',
    group_key TEXT NOT NULL,
    group_label TEXT NOT NULL,
    origin TEXT NOT NULL CHECK (origin IN ('BUILTIN','IMPORTED')),
    created_at TEXT NOT NULL
);

CREATE TABLE role_configuration_bootstrap (
    name TEXT PRIMARY KEY CHECK (name='builtin'),
    source_sha256 TEXT NOT NULL CHECK (length(source_sha256)=64),
    bindings_sha256 TEXT NOT NULL CHECK (length(bindings_sha256)=64),
    binding_count INTEGER NOT NULL CHECK (binding_count>0),
    completed_at TEXT NOT NULL
);

CREATE TABLE role_revision (
    revision_id TEXT PRIMARY KEY,
    role_id TEXT NOT NULL REFERENCES role_definition(role_id) ON DELETE RESTRICT,
    revision_number INTEGER NOT NULL CHECK (revision_number>0),
    manifest_json TEXT NOT NULL CHECK (json_valid(manifest_json)),
    prompt_fragments_json TEXT NOT NULL CHECK (json_valid(prompt_fragments_json)),
    content_sha256 TEXT NOT NULL CHECK (length(content_sha256)=64),
    source_sha256 TEXT NOT NULL CHECK (length(source_sha256)=64),
    source_kind TEXT NOT NULL CHECK (source_kind IN ('BUILTIN','IMPORTED')),
    published_at TEXT NOT NULL,
    UNIQUE(role_id,revision_number),
    UNIQUE(role_id,content_sha256)
);
CREATE INDEX idx_role_revision_history ON role_revision(role_id,revision_number DESC);
CREATE TRIGGER role_revision_no_update BEFORE UPDATE ON role_revision
BEGIN SELECT RAISE(ABORT,'role revision is immutable'); END;
CREATE TRIGGER role_revision_no_delete BEFORE DELETE ON role_revision
BEGIN SELECT RAISE(ABORT,'role revision is immutable'); END;

CREATE TABLE role_binding (
    slot TEXT PRIMARY KEY,
    adapter_profile TEXT NOT NULL,
    display_name TEXT NOT NULL,
    purpose TEXT NOT NULL,
    revision_id TEXT REFERENCES role_revision(revision_id) ON DELETE RESTRICT,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version>=0),
    updated_at TEXT NOT NULL
);

CREATE TABLE role_owner_binding (
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    slot TEXT NOT NULL REFERENCES role_binding(slot) ON DELETE RESTRICT,
    revision_id TEXT NOT NULL REFERENCES role_revision(revision_id) ON DELETE RESTRICT,
    parent_type TEXT,
    parent_id TEXT,
    frozen_at TEXT NOT NULL,
    PRIMARY KEY(owner_type,owner_id,slot),
    CHECK ((parent_type IS NULL)=(parent_id IS NULL))
);
CREATE INDEX idx_role_owner_binding_revision ON role_owner_binding(revision_id);
CREATE TABLE role_owner_snapshot (
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    parent_type TEXT,
    parent_id TEXT,
    binding_count INTEGER NOT NULL CHECK (binding_count>=0),
    bindings_sha256 TEXT NOT NULL CHECK (length(bindings_sha256)=64),
    frozen_at TEXT NOT NULL,
    PRIMARY KEY(owner_type,owner_id),
    CHECK ((parent_type IS NULL)=(parent_id IS NULL))
);
CREATE TRIGGER role_owner_snapshot_no_update BEFORE UPDATE ON role_owner_snapshot
BEGIN SELECT RAISE(ABORT,'owner role snapshot is immutable'); END;
CREATE TRIGGER role_owner_snapshot_no_delete BEFORE DELETE ON role_owner_snapshot
BEGIN SELECT RAISE(ABORT,'owner role snapshot is immutable'); END;
CREATE TRIGGER role_owner_binding_no_update BEFORE UPDATE ON role_owner_binding
BEGIN SELECT RAISE(ABORT,'owner role binding is immutable'); END;
CREATE TRIGGER role_owner_binding_no_delete BEFORE DELETE ON role_owner_binding
BEGIN SELECT RAISE(ABORT,'owner role binding is immutable'); END;

CREATE TABLE role_session_snapshot (
    session_key TEXT PRIMARY KEY,
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    slot TEXT NOT NULL,
    revision_id TEXT NOT NULL REFERENCES role_revision(revision_id) ON DELETE RESTRICT,
    revision_sha256 TEXT NOT NULL CHECK (length(revision_sha256)=64),
    adapter_profile TEXT NOT NULL,
    adapter_version TEXT NOT NULL,
    permission_policy_json TEXT NOT NULL CHECK (json_valid(permission_policy_json)),
    permission_policy_sha256 TEXT NOT NULL CHECK (length(permission_policy_sha256)=64),
    safe_prompt_sha256 TEXT CHECK (safe_prompt_sha256 IS NULL OR length(safe_prompt_sha256)=64),
    frozen_at TEXT NOT NULL
);
CREATE TRIGGER role_session_snapshot_no_update BEFORE UPDATE ON role_session_snapshot
BEGIN SELECT RAISE(ABORT,'role session snapshot is immutable'); END;
CREATE TRIGGER role_session_snapshot_no_delete BEFORE DELETE ON role_session_snapshot
BEGIN SELECT RAISE(ABORT,'role session snapshot is immutable'); END;

CREATE TABLE role_prompt_dispatch (
    session_key TEXT NOT NULL REFERENCES role_session_snapshot(session_key) ON DELETE RESTRICT,
    message_key TEXT NOT NULL,
    business_sha256 TEXT NOT NULL CHECK (length(business_sha256)=64),
    effective_sha256 TEXT NOT NULL CHECK (length(effective_sha256)=64),
    created_at TEXT NOT NULL,
    PRIMARY KEY(session_key,message_key)
);
CREATE TRIGGER role_prompt_dispatch_no_update BEFORE UPDATE ON role_prompt_dispatch
BEGIN SELECT RAISE(ABORT,'role prompt identity is immutable'); END;
CREATE TRIGGER role_prompt_dispatch_no_delete BEFORE DELETE ON role_prompt_dispatch
BEGIN SELECT RAISE(ABORT,'role prompt identity is immutable'); END;

CREATE TABLE role_import_receipt (
    idempotency_key TEXT PRIMARY KEY,
    source_sha256 TEXT NOT NULL CHECK (length(source_sha256)=64),
    request_sha256 TEXT NOT NULL CHECK (length(request_sha256)=64),
    result_json TEXT NOT NULL CHECK (json_valid(result_json)),
    created_at TEXT NOT NULL
);
CREATE TABLE role_configuration_audit (
    id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    role_id TEXT,
    revision_id TEXT,
    slot TEXT,
    detail_json TEXT NOT NULL CHECK (json_valid(detail_json)),
    created_at TEXT NOT NULL
);
