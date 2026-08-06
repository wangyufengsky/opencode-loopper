CREATE TABLE loopspec_template (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE loopspec_template_version (
    id TEXT PRIMARY KEY,
    template_id TEXT NOT NULL REFERENCES loopspec_template(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    spec_json TEXT NOT NULL,
    spec_sha256 TEXT NOT NULL,
    immutable INTEGER NOT NULL DEFAULT 1 CHECK (immutable = 1),
    auto_start_approved INTEGER NOT NULL DEFAULT 0 CHECK (auto_start_approved IN (0, 1)),
    created_at TEXT NOT NULL,
    UNIQUE(template_id, version_number),
    UNIQUE(template_id, spec_sha256)
);

CREATE INDEX idx_template_version_template_created
    ON loopspec_template_version(template_id, version_number DESC);

-- A published template version is an immutable approval subject.  In
-- particular, changing spec_json after an AUTO_START approval must never be
-- possible without creating a new version and approving that exact hash.
CREATE TRIGGER loopspec_template_version_no_update
BEFORE UPDATE ON loopspec_template_version
BEGIN
    SELECT RAISE(ABORT, 'template versions are immutable');
END;

CREATE TRIGGER loopspec_template_version_no_delete
BEFORE DELETE ON loopspec_template_version
BEGIN
    SELECT RAISE(ABORT, 'template versions are immutable');
END;

CREATE TABLE automation_rule (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    template_version_id TEXT NOT NULL REFERENCES loopspec_template_version(id) ON DELETE RESTRICT,
    trigger_type TEXT NOT NULL CHECK (trigger_type IN ('MANUAL', 'CRON', 'GIT_HEAD_CHANGED', 'WEBHOOK')),
    state TEXT NOT NULL DEFAULT 'DISABLED' CHECK (state IN ('DISABLED', 'ENABLED')),
    approval_mode TEXT NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (approval_mode IN ('REVIEW_REQUIRED', 'AUTO_START')),
    trigger_config_json TEXT NOT NULL,
    webhook_token_hash TEXT,
    last_observed_head TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE TRIGGER automation_rule_auto_start_insert_guard
BEFORE INSERT ON automation_rule
WHEN NEW.approval_mode = 'AUTO_START'
     AND NOT EXISTS (
         SELECT 1 FROM loopspec_template_version v
         WHERE v.id = NEW.template_version_id
           AND v.immutable = 1
           AND v.auto_start_approved = 1
     )
BEGIN
    SELECT RAISE(ABORT, 'AUTO_START requires an approved immutable template version');
END;

CREATE TRIGGER automation_rule_auto_start_update_guard
BEFORE UPDATE ON automation_rule
WHEN NEW.approval_mode = 'AUTO_START'
     AND NOT EXISTS (
         SELECT 1 FROM loopspec_template_version v
         WHERE v.id = NEW.template_version_id
           AND v.immutable = 1
           AND v.auto_start_approved = 1
     )
BEGIN
    SELECT RAISE(ABORT, 'AUTO_START requires an approved immutable template version');
END;

CREATE INDEX idx_automation_rule_trigger_state
    ON automation_rule(trigger_type, state, updated_at);

CREATE TABLE automation_run (
    id TEXT PRIMARY KEY,
    rule_id TEXT NOT NULL REFERENCES automation_rule(id) ON DELETE CASCADE,
    trigger_type TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (state IN ('DETECTED', 'REVIEW_REQUIRED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    draft_id TEXT REFERENCES loop_draft(id) ON DELETE SET NULL,
    task_id TEXT REFERENCES task(id) ON DELETE SET NULL,
    evidence_json TEXT NOT NULL,
    detected_at TEXT NOT NULL,
    started_at TEXT,
    ended_at TEXT
);

CREATE INDEX idx_automation_run_rule_detected
    ON automation_run(rule_id, detected_at DESC);
