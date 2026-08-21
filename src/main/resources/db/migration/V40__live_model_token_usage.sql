CREATE TABLE model_token_usage (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT REFERENCES designer_session(id) ON DELETE CASCADE,
    task_id TEXT REFERENCES task(id) ON DELETE CASCADE,
    external_session_id TEXT NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_tokens INTEGER,
    reliable INTEGER NOT NULL DEFAULT 0 CHECK (reliable IN (0, 1)),
    complete INTEGER NOT NULL DEFAULT 0 CHECK (complete IN (0, 1)),
    observed_at TEXT NOT NULL,
    CHECK (
        (designer_session_id IS NOT NULL AND task_id IS NULL)
        OR (designer_session_id IS NULL AND task_id IS NOT NULL)
    ),
    CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CHECK (total_tokens IS NULL OR total_tokens >= 0),
    UNIQUE (designer_session_id, external_session_id),
    UNIQUE (task_id, external_session_id)
);

CREATE INDEX idx_model_token_usage_designer
    ON model_token_usage(designer_session_id, observed_at);

CREATE INDEX idx_model_token_usage_task
    ON model_token_usage(task_id, observed_at);

CREATE TRIGGER trg_model_token_usage_identity_immutable
BEFORE UPDATE OF designer_session_id, task_id, external_session_id ON model_token_usage
BEGIN
    SELECT RAISE(ABORT, 'model token usage identity is immutable');
END;

INSERT INTO model_token_usage(
    id, task_id, external_session_id, input_tokens, output_tokens, total_tokens,
    reliable, complete, observed_at
)
SELECT
    lower(hex(randomblob(16))), scoped.task_id, scoped.external_session_id,
    SUM(CASE WHEN usage.reliable=1 THEN usage.input_tokens END),
    SUM(CASE WHEN usage.reliable=1 THEN usage.output_tokens END),
    SUM(CASE WHEN usage.reliable=1 THEN usage.total_tokens END),
    MAX(CASE WHEN usage.reliable=1 AND usage.total_tokens IS NOT NULL THEN 1 ELSE 0 END),
    1,
    MAX(usage.observed_at)
FROM (
    SELECT id AS local_session_id, task_id, external_session_id, 'EXECUTION' AS session_kind
    FROM execution_session
    WHERE external_session_id IS NOT NULL
    UNION ALL
    SELECT id AS local_session_id, task_id, external_session_id, 'JUDGE' AS session_kind
    FROM judge_run
    WHERE external_session_id IS NOT NULL
) scoped
JOIN session_usage usage
  ON usage.task_id=scoped.task_id
 AND ((scoped.session_kind='EXECUTION' AND usage.execution_session_id=scoped.local_session_id)
   OR (scoped.session_kind='JUDGE' AND usage.judge_run_id=scoped.local_session_id))
GROUP BY scoped.task_id, scoped.external_session_id;

INSERT OR IGNORE INTO model_token_usage(
    id, designer_session_id, external_session_id, reliable, complete, observed_at
)
SELECT lower(hex(randomblob(16))), scoped.designer_session_id, scoped.external_session_id,
       0, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM (
    SELECT id AS designer_session_id, external_session_id
    FROM designer_session WHERE external_session_id IS NOT NULL
    UNION
    SELECT designer_session_id, external_session_id
    FROM task_profile_router_run WHERE external_session_id IS NOT NULL
    UNION
    SELECT designer_session_id, external_session_id
    FROM task_decomposition WHERE external_session_id IS NOT NULL
    UNION
    SELECT designer_session_id, designer_external_session_id
    FROM design_work_package WHERE designer_external_session_id IS NOT NULL
    UNION
    SELECT designer_session_id, external_session_id
    FROM loop_spec_compilation WHERE external_session_id IS NOT NULL
    UNION
    SELECT designer_session_id, external_session_id
    FROM analysis_report WHERE external_session_id IS NOT NULL
) scoped;

INSERT OR IGNORE INTO model_token_usage(
    id, task_id, external_session_id, reliable, complete, observed_at
)
SELECT lower(hex(randomblob(16))), scoped.task_id, scoped.external_session_id,
       0, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM (
    SELECT task_id, external_session_id
    FROM execution_session WHERE external_session_id IS NOT NULL
    UNION
    SELECT task_id, external_session_id
    FROM judge_run WHERE external_session_id IS NOT NULL
) scoped;

CREATE TRIGGER trg_designer_token_usage_insert
AFTER INSERT ON designer_session
WHEN NEW.external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_designer_token_usage_update
AFTER UPDATE OF external_session_id ON designer_session
WHEN NEW.external_session_id IS NOT NULL AND NEW.external_session_id IS NOT OLD.external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_router_token_usage_insert
AFTER INSERT ON task_profile_router_run
WHEN NEW.external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_router_token_usage_update
AFTER UPDATE OF external_session_id ON task_profile_router_run
WHEN NEW.external_session_id IS NOT NULL AND NEW.external_session_id IS NOT OLD.external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_decomposition_token_usage_insert
AFTER INSERT ON task_decomposition
WHEN NEW.external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_decomposition_token_usage_update
AFTER UPDATE OF external_session_id ON task_decomposition
WHEN NEW.external_session_id IS NOT NULL AND NEW.external_session_id IS NOT OLD.external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_work_package_token_usage_insert
AFTER INSERT ON design_work_package
WHEN NEW.designer_external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.designer_external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_work_package_token_usage_update
AFTER UPDATE OF designer_external_session_id ON design_work_package
WHEN NEW.designer_external_session_id IS NOT NULL
 AND NEW.designer_external_session_id IS NOT OLD.designer_external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.designer_external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_compilation_token_usage_insert
AFTER INSERT ON loop_spec_compilation
WHEN NEW.external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_compilation_token_usage_update
AFTER UPDATE OF external_session_id ON loop_spec_compilation
WHEN NEW.external_session_id IS NOT NULL AND NEW.external_session_id IS NOT OLD.external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_report_token_usage_insert
AFTER INSERT ON analysis_report
WHEN NEW.external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_report_token_usage_update
AFTER UPDATE OF external_session_id ON analysis_report
WHEN NEW.external_session_id IS NOT NULL AND NEW.external_session_id IS NOT OLD.external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_execution_token_usage_insert
AFTER INSERT ON execution_session
WHEN NEW.external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,task_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.task_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_execution_token_usage_update
AFTER UPDATE OF external_session_id ON execution_session
WHEN NEW.external_session_id IS NOT NULL AND NEW.external_session_id IS NOT OLD.external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,task_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.task_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_judge_token_usage_insert
AFTER INSERT ON judge_run
WHEN NEW.external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,task_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.task_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TRIGGER trg_judge_token_usage_update
AFTER UPDATE OF external_session_id ON judge_run
WHEN NEW.external_session_id IS NOT NULL AND NEW.external_session_id IS NOT OLD.external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,task_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.task_id,NEW.external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;
