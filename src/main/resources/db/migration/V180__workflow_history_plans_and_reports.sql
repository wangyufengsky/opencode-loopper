-- Extend report packages with portable Markdown subdirectories; existing flat document identities remain unchanged.
CREATE TABLE workflow_document_next (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    manifest_json TEXT NOT NULL CHECK(json_valid(manifest_json) AND length(CAST(manifest_json AS BLOB))<=CASE WHEN json_extract(manifest_json,'$.type')='HISTORY_DOCUMENT' THEN 4194304 ELSE 1048576 END
        AND json_extract(manifest_json,'$.version')=1 AND json_extract(manifest_json,'$.type') IN ('DESIGN_DOCUMENT','ASSESSMENT_DOCUMENT','HISTORY_DOCUMENT')
        AND json_array_length(manifest_json,'$.files') BETWEEN 1 AND CASE WHEN json_extract(manifest_json,'$.type')='HISTORY_DOCUMENT' THEN 10000 ELSE 1026 END),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TABLE workflow_document_file_next (
    attempt_id TEXT NOT NULL REFERENCES workflow_document_next(attempt_id),
    path TEXT NOT NULL CHECK(length(path) BETWEEN 1 AND 1024),
    size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 67108864),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    content TEXT NOT NULL CHECK(length(CAST(content AS BLOB))=size_bytes),
    PRIMARY KEY(attempt_id,path)
);
INSERT INTO workflow_document_next SELECT * FROM workflow_document;
INSERT INTO workflow_document_file_next SELECT * FROM workflow_document_file;
DROP TABLE workflow_document_file;
DROP TABLE workflow_document;
ALTER TABLE workflow_document_next RENAME TO workflow_document;
ALTER TABLE workflow_document_file_next RENAME TO workflow_document_file;
CREATE TRIGGER trg_workflow_document_owner BEFORE INSERT ON workflow_document
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
    AND r.state IN ('RUNNING','PAUSED','STALLED') 
    AND a.role_snapshot_json IS NULL AND a.external_session_id IS NULL
    AND json_extract(n.definition_json,'$.kind')='SYSTEM' AND ((a.adapter_key='system.source.design-document.v1' AND json_extract(n.definition_json,'$.moduleId')='system.source.design-document' AND json_extract(NEW.manifest_json,'$.type')='DESIGN_DOCUMENT')
        OR (a.adapter_key='system.document.review-report.v1' AND json_extract(n.definition_json,'$.moduleId')='system.document.review-report' AND json_extract(NEW.manifest_json,'$.type')='ASSESSMENT_DOCUMENT')
        OR (a.adapter_key='system.history.report.v1' AND json_extract(n.definition_json,'$.moduleId')='system.history.report' AND json_extract(NEW.manifest_json,'$.type')='HISTORY_DOCUMENT'))
    AND json_extract(n.definition_json,'$.moduleVersion')=1 AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow document owner mismatch'); END;
CREATE TRIGGER trg_workflow_document_file_owner BEFORE INSERT ON workflow_document_file
WHEN NOT EXISTS(SELECT 1 FROM workflow_document d JOIN workflow_node_attempt a ON a.id=d.attempt_id
    JOIN workflow_node_run n ON n.id=a.node_run_id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN json_each(d.manifest_json,'$.files') f
    WHERE d.attempt_id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
    AND r.state IN ('RUNNING','PAUSED','STALLED') AND json_extract(f.value,'$.path')=NEW.path
    AND ((json_extract(d.manifest_json,'$.type')<>'HISTORY_DOCUMENT' AND length(NEW.path)<=160 AND NEW.path NOT IN ('.','..') AND NEW.path NOT GLOB '*[^A-Za-z0-9_.-]*')
        OR (json_extract(d.manifest_json,'$.type')='HISTORY_DOCUMENT' AND NEW.path LIKE '%.md'
            AND substr(NEW.path,1,1)<>'/' AND instr(NEW.path,'//')=0 AND instr('/'||NEW.path||'/','/../')=0 AND instr('/'||NEW.path||'/','/./')=0
            AND instr(NEW.path,char(92))=0 AND instr(NEW.path,char(0))=0 AND instr(NEW.path,char(9))=0 AND instr(NEW.path,char(10))=0 AND instr(NEW.path,char(13))=0
            AND NEW.path NOT GLOB '*[<>:"|?*]*' AND length(NEW.path)-length(replace(NEW.path,'/','')) BETWEEN 1 AND 2))
    AND json_extract(f.value,'$.sha256')=NEW.sha256 AND json_extract(f.value,'$.sizeBytes')=NEW.size_bytes
    AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
    OR (SELECT COALESCE(SUM(size_bytes),0) FROM workflow_document_file WHERE attempt_id=NEW.attempt_id)+NEW.size_bytes>67108864
BEGIN SELECT RAISE(ABORT,'workflow document file identity mismatch'); END;
CREATE TRIGGER trg_workflow_document_immutable BEFORE UPDATE ON workflow_document
BEGIN SELECT RAISE(ABORT,'workflow document is immutable'); END;
CREATE TRIGGER trg_workflow_document_no_delete BEFORE DELETE ON workflow_document
BEGIN SELECT RAISE(ABORT,'workflow document history is immutable'); END;
CREATE TRIGGER trg_workflow_document_file_immutable BEFORE UPDATE ON workflow_document_file
BEGIN SELECT RAISE(ABORT,'workflow document file is immutable'); END;
CREATE TRIGGER trg_workflow_document_file_no_delete BEFORE DELETE ON workflow_document_file
BEGIN SELECT RAISE(ABORT,'workflow document file history is immutable'); END;

-- History planning and report compilation add exact no-external-session identities; all previous stop checks remain.
DROP TRIGGER trg_workflow_stop_owner;
CREATE TRIGGER trg_workflow_stop_owner BEFORE INSERT ON workflow_attempt_stop
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND a.external_session_id IS NEW.external_session_id
    AND (a.adapter_key<>'system.verify.command.v1' OR NEW.kind IN ('COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED'))
    AND (NEW.kind<>'NO_EXTERNAL_WORK' OR a.adapter_key='human.v1'
        OR (a.adapter_key='system.verify.files.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.verify.files'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.snapshot.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.snapshot'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.test-profile.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.test-profile'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.test-plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.test-plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.test-summary.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.test-summary'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.design-plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.design-plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.design-document.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.design-document'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.document.review-plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.document.review-plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.document.review-report.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.document.review-report'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.history.plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.history.plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.history.report.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.history.report'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.review.dual.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.review.dual'
            AND json_extract(n.definition_json,'$.moduleVersion')=1))
    AND (NEW.kind<>'NO_SESSION_CREATED' OR EXISTS(SELECT 1 FROM workflow_model_launch l
        WHERE l.attempt_id=a.id AND l.creation_plan_json IS NULL AND l.state='STOPPING'))
    AND (NEW.kind NOT IN ('SESSION_ABSENT','CREATION_STOP_CONFIRMED') OR EXISTS(SELECT 1 FROM workflow_model_launch l
        WHERE l.attempt_id=a.id AND l.creation_plan_json IS NOT NULL AND l.state='STOPPING'))
    AND (NEW.kind NOT IN ('COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED') OR EXISTS(SELECT 1 FROM workflow_command_run c
        WHERE c.attempt_id=a.id AND a.adapter_key='system.verify.command.v1' AND a.role_snapshot_json IS NULL
        AND ((NEW.kind='COMMAND_TERMINAL' AND c.result_json IS NOT NULL
            AND json_extract(c.result_json,'$.stopConfirmed')=1
            AND json_extract(NEW.evidence_json,'$.requestSha256')=c.request_sha256
            AND json_extract(NEW.evidence_json,'$.resultSha256')=c.result_sha256)
        OR (NEW.kind='COMMAND_NOT_LAUNCHED' AND c.state='STOPPING' AND c.request_json IS NULL AND c.registration_json IS NULL)))))
BEGIN SELECT RAISE(ABORT,'workflow stop identity mismatch'); END;


CREATE TABLE workflow_history_report_format (
    node_run_id TEXT PRIMARY KEY REFERENCES workflow_node_run(id),
    kind TEXT NOT NULL CHECK(kind IN ('CODE_REVIEW','CONTRIBUTION_REPORT')),
    layout_json TEXT NOT NULL CHECK(json_valid(layout_json) AND length(CAST(layout_json AS BLOB))<=1048576),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TABLE workflow_history_report_bundle (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    namespace_key TEXT NOT NULL REFERENCES template_report_sequence(namespace_key),
    sequence INTEGER NOT NULL CHECK(sequence>0),
    project_name TEXT NOT NULL,
    source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64),
    folder_name TEXT NOT NULL,
    main_path TEXT NOT NULL,
    UNIQUE(namespace_key,sequence)
);
CREATE TRIGGER trg_workflow_history_report_format_owner BEFORE INSERT ON workflow_history_report_format
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_run n JOIN workflow_requirement r ON r.id=n.requirement_id
    WHERE n.id=NEW.node_run_id AND n.state='PENDING' AND n.latest_attempt_id IS NULL
    AND r.state IN ('PLANNING','PENDING_START','RUNNING','PAUSED','STALLED')
    AND json_extract(n.definition_json,'$.kind')='SYSTEM' AND json_extract(n.definition_json,'$.moduleId')='system.history.report'
    AND json_extract(n.definition_json,'$.moduleVersion')=1 AND json_extract(n.definition_json,'$.roleId') IS NULL
    AND json_extract(n.definition_json,'$.roleRevisionId') IS NULL
    AND COALESCE(json_extract(n.definition_json,'$.parameters.historyReportKind'),'CODE_REVIEW')=NEW.kind
    AND json_extract(NEW.layout_json,'$.version')=CASE WHEN NEW.kind='CODE_REVIEW' THEN 'HISTORY_REPORT_LAYOUT_V1' ELSE 'REPORT_LAYOUT_V3' END)
BEGIN SELECT RAISE(ABORT,'workflow history report format owner mismatch'); END;
CREATE TRIGGER trg_workflow_history_report_bundle_owner BEFORE INSERT ON workflow_history_report_bundle
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_history_report_format f ON f.node_run_id=n.id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN json_each(a.inputs_json,'$.values') i JOIN workflow_node_attempt p ON p.id=json_extract(i.value,'$.attemptId')
    JOIN workflow_node_run pn ON pn.id=p.node_run_id JOIN workflow_history_snapshot h ON h.node_run_id=pn.id
    JOIN template_report_sequence seq ON seq.namespace_key=NEW.namespace_key
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
    AND r.state IN ('RUNNING','PAUSED','STALLED') AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent stop WHERE stop.requirement_id=r.id)
    AND a.adapter_key='system.history.report.v1' AND a.role_snapshot_json IS NULL AND a.external_session_id IS NULL
    AND json_extract(i.value,'$.name')='source' AND json_extract(i.value,'$.content.sha256')=NEW.source_sha256
    AND h.manifest_sha256=NEW.source_sha256 AND pn.requirement_id=n.requirement_id AND p.state='SUCCEEDED'
    AND seq.last_sequence=NEW.sequence AND EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=p.id)
    AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow history report bundle owner mismatch'); END;
CREATE TRIGGER trg_workflow_history_report_format_immutable BEFORE UPDATE ON workflow_history_report_format
BEGIN SELECT RAISE(ABORT,'history report identity is immutable'); END;
CREATE TRIGGER trg_workflow_history_report_format_retained BEFORE DELETE ON workflow_history_report_format
BEGIN SELECT RAISE(ABORT,'history report identity must be retained'); END;
CREATE TRIGGER trg_workflow_history_report_bundle_immutable BEFORE UPDATE ON workflow_history_report_bundle
BEGIN SELECT RAISE(ABORT,'history report identity is immutable'); END;
CREATE TRIGGER trg_workflow_history_report_bundle_retained BEFORE DELETE ON workflow_history_report_bundle
BEGIN SELECT RAISE(ABORT,'history report identity must be retained'); END;
