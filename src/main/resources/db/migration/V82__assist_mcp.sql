CREATE TABLE database_connection (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, config_json TEXT NOT NULL,
 credential_ref TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
 archived INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE database_connection_project (
 connection_id TEXT NOT NULL REFERENCES database_connection(id),
 project_id TEXT NOT NULL REFERENCES project(id), PRIMARY KEY(connection_id,project_id)
);
CREATE INDEX idx_database_connection_page ON database_connection(created_at,id);
CREATE TABLE assist_tool_policy (
 scope TEXT NOT NULL, server_id TEXT NOT NULL, tool_name TEXT NOT NULL,
 enabled INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL,
 PRIMARY KEY(scope,server_id,tool_name)
);
CREATE TABLE assist_catalog_registration (server_id TEXT PRIMARY KEY, created_at TEXT NOT NULL);
CREATE TABLE assist_policy_audit (
 id TEXT PRIMARY KEY, scope TEXT NOT NULL, server_id TEXT NOT NULL, tool_name TEXT NOT NULL,
 action TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE assist_session (
 external_session_id TEXT PRIMARY KEY, generation TEXT NOT NULL, directory TEXT NOT NULL,
 profile TEXT NOT NULL, permissions_json TEXT NOT NULL, tools_json TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE assist_resource_binding (
 owner_key TEXT PRIMARY KEY, connections_json TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE assist_scope_binding (external_session_id TEXT PRIMARY KEY REFERENCES assist_session(external_session_id), owner_json TEXT NOT NULL);
-- Task admission freezes data authorization even if no model has used a tool yet.
INSERT INTO assist_resource_binding SELECT 'TASK:'||id,'[]',created_at FROM task;
CREATE TRIGGER assist_task_resource_freeze AFTER INSERT ON task BEGIN
  INSERT INTO assist_resource_binding(owner_key,connections_json,created_at)
  SELECT 'TASK:'||NEW.id,coalesce(json_group_array(json_object(
    'id',d.id,'name',d.name,'config',json(d.config_json),'credentialRef',d.credential_ref,'version',d.version)),'[]'),NEW.created_at
  FROM database_connection d JOIN database_connection_project p ON p.connection_id=d.id
  WHERE p.project_id=NEW.project_id AND d.enabled=1 AND d.archived=0;
END;
CREATE TABLE assist_call (
 id TEXT PRIMARY KEY, owner_key TEXT NOT NULL, external_session_id TEXT NOT NULL,
 tool_name TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT, created_at TEXT NOT NULL,
 completed_at TEXT
);
CREATE INDEX idx_assist_call_owner ON assist_call(owner_key,created_at,id);
CREATE TABLE assist_word_receipt (
 owner_key TEXT NOT NULL, idempotency_key TEXT NOT NULL, request_hash TEXT NOT NULL,
 target_path TEXT NOT NULL, source_hash TEXT NOT NULL, content_ref TEXT NOT NULL,
 output_hash TEXT NOT NULL, state TEXT NOT NULL, created_at TEXT NOT NULL,
 PRIMARY KEY(owner_key,idempotency_key)
);
