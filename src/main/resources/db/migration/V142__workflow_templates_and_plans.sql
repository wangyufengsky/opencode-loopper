CREATE TABLE workflow_template (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
    description TEXT NOT NULL CHECK(length(description)<=4000),
    builtin INTEGER NOT NULL CHECK(builtin IN (0,1)),
    archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
    head_revision INTEGER NOT NULL CHECK(head_revision>=1),
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    layout_json TEXT NOT NULL CHECK(json_valid(layout_json) AND json_type(layout_json)='object'),
    layout_version INTEGER NOT NULL DEFAULT 0 CHECK(layout_version>=0),
    source_template_id TEXT,
    source_revision INTEGER,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(source_template_id,source_revision) REFERENCES workflow_template_revision(template_id,revision),
    CHECK((source_template_id IS NULL)=(source_revision IS NULL))
);
CREATE INDEX idx_workflow_template_page ON workflow_template(archived,created_at DESC,id DESC);
CREATE TABLE workflow_template_revision (
    template_id TEXT NOT NULL REFERENCES workflow_template(id),
    revision INTEGER NOT NULL CHECK(revision>=1),
    definition_json TEXT NOT NULL CHECK(json_valid(definition_json) AND json_type(definition_json)='object'
        AND length(CAST(definition_json AS BLOB))<=2097152),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
    created_at TEXT NOT NULL,
    PRIMARY KEY(template_id,revision)
);
CREATE TRIGGER trg_workflow_template_revision_immutable BEFORE UPDATE ON workflow_template_revision
BEGIN SELECT RAISE(ABORT,'workflow template revision is immutable'); END;
CREATE TRIGGER trg_workflow_template_revision_retained BEFORE DELETE ON workflow_template_revision
BEGIN SELECT RAISE(ABORT,'workflow template history must be retained'); END;
CREATE TRIGGER trg_workflow_template_identity BEFORE UPDATE OF id,builtin,source_template_id,source_revision,created_at ON workflow_template
WHEN OLD.id IS NOT NEW.id OR OLD.builtin IS NOT NEW.builtin OR OLD.source_template_id IS NOT NEW.source_template_id
    OR OLD.source_revision IS NOT NEW.source_revision OR OLD.created_at IS NOT NEW.created_at
BEGIN SELECT RAISE(ABORT,'workflow template identity is immutable'); END;

CREATE TABLE workflow_requirement (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES project(id),
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
    objective TEXT NOT NULL CHECK(length(objective) BETWEEN 1 AND 24000),
    state TEXT NOT NULL CHECK(state IN ('PLANNING','PENDING_START','RUNNING','PAUSED','STALLED','STOPPING','COMPLETED','FAILED','CANCELLED')),
    head_revision INTEGER NOT NULL CHECK(head_revision>=1),
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    source_template_id TEXT NOT NULL,
    source_revision INTEGER NOT NULL,
    layout_json TEXT NOT NULL CHECK(json_valid(layout_json) AND json_type(layout_json)='object'),
    layout_version INTEGER NOT NULL DEFAULT 0 CHECK(layout_version>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(source_template_id,source_revision) REFERENCES workflow_template_revision(template_id,revision)
);
CREATE INDEX idx_workflow_requirement_page ON workflow_requirement(created_at DESC,id DESC);
CREATE INDEX idx_workflow_requirement_project_page ON workflow_requirement(project_id,created_at DESC,id DESC);
CREATE TABLE workflow_plan_revision (
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    revision INTEGER NOT NULL CHECK(revision>=1),
    definition_json TEXT NOT NULL CHECK(json_valid(definition_json) AND json_type(definition_json)='object'
        AND length(CAST(definition_json AS BLOB))<=2097152),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
    source TEXT NOT NULL CHECK(source IN ('TEMPLATE','USER','AI_CONFIRMED')),
    base_revision INTEGER,
    created_at TEXT NOT NULL,
    PRIMARY KEY(requirement_id,revision),
    CHECK((revision=1 AND base_revision IS NULL) OR (revision>1 AND base_revision=revision-1))
);
CREATE TRIGGER trg_workflow_plan_revision_immutable BEFORE UPDATE ON workflow_plan_revision
BEGIN SELECT RAISE(ABORT,'workflow plan revision is immutable'); END;
CREATE TRIGGER trg_workflow_plan_revision_retained BEFORE DELETE ON workflow_plan_revision
BEGIN SELECT RAISE(ABORT,'workflow plan history must be retained'); END;
CREATE TRIGGER trg_workflow_requirement_identity BEFORE UPDATE OF id,project_id,source_template_id,source_revision,created_at ON workflow_requirement
WHEN OLD.id IS NOT NEW.id OR OLD.project_id IS NOT NEW.project_id OR OLD.source_template_id IS NOT NEW.source_template_id
    OR OLD.source_revision IS NOT NEW.source_revision OR OLD.created_at IS NOT NEW.created_at
BEGIN SELECT RAISE(ABORT,'workflow requirement identity is immutable'); END;

CREATE TABLE workflow_command (
    request_key TEXT PRIMARY KEY CHECK(length(request_key) BETWEEN 16 AND 100),
    request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
    entity_type TEXT NOT NULL CHECK(entity_type IN ('TEMPLATE','REQUIREMENT')),
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL,
    receipt_json TEXT NOT NULL CHECK(json_valid(receipt_json) AND length(CAST(receipt_json AS BLOB))<=16384),
    created_at TEXT NOT NULL
);
CREATE INDEX idx_workflow_command_owner ON workflow_command(entity_type,entity_id,created_at);
CREATE TRIGGER trg_workflow_command_immutable BEFORE UPDATE ON workflow_command
BEGIN SELECT RAISE(ABORT,'workflow command receipt is immutable'); END;
