CREATE TABLE workflow_knowledge_binding (
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    sources_json TEXT NOT NULL CHECK (json_valid(sources_json)),
    frozen_at TEXT NOT NULL,
    PRIMARY KEY(owner_type,owner_id)
);
CREATE TRIGGER workflow_knowledge_binding_no_update BEFORE UPDATE ON workflow_knowledge_binding
BEGIN SELECT RAISE(ABORT,'workflow knowledge sources are immutable'); END;
CREATE TRIGGER workflow_knowledge_binding_no_delete BEFORE DELETE ON workflow_knowledge_binding
BEGIN SELECT RAISE(ABORT,'workflow knowledge sources are immutable'); END;

CREATE TABLE workflow_knowledge_git (
    external_session_id TEXT PRIMARY KEY,
    source_json TEXT NOT NULL CHECK (json_valid(source_json))
);

CREATE TRIGGER workflow_knowledge_git_no_update BEFORE UPDATE ON workflow_knowledge_git
BEGIN SELECT RAISE(ABORT,'workflow knowledge repository is immutable'); END;
CREATE TRIGGER workflow_knowledge_git_no_delete BEFORE DELETE ON workflow_knowledge_git
BEGIN SELECT RAISE(ABORT,'workflow knowledge repository is immutable'); END;

-- Enable only untouched built-in defaults. Preserve every explicit global or project decision.
CREATE TEMP TABLE workflow_default_tools AS
SELECT tool_name FROM assist_tool_policy p
WHERE scope='' AND server_id='@loopper-assist' AND enabled=0 AND version=0
  AND tool_name IN ('search_project_knowledge','list_test_failures','read_test_failure','search_evidence')
  AND NOT EXISTS (SELECT 1 FROM assist_policy_audit a
      WHERE a.scope=p.scope AND a.server_id=p.server_id AND a.tool_name=p.tool_name);
UPDATE assist_tool_policy SET enabled=1,version=version+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
WHERE scope='' AND server_id='@loopper-assist' AND tool_name IN (SELECT tool_name FROM workflow_default_tools);
INSERT INTO assist_policy_audit(id,scope,server_id,tool_name,action,created_at)
SELECT 'v140-default-'||tool_name,'','@loopper-assist',tool_name,'MIGRATION_DEFAULT_ENABLE',strftime('%Y-%m-%dT%H:%M:%fZ','now')
FROM workflow_default_tools;
DROP TABLE workflow_default_tools;
