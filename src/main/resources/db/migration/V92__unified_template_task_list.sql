-- One business list identity before and after attachment to an executable Task.
-- No runtime row is manufactured for a document intake or static review.
CREATE VIEW task_list_item AS
SELECT t.id,t.project_id,p.name AS project_name,t.title,substr(COALESCE(d.goal,''),1,240) AS goal_preview,
 t.branch_name,t.state,retry.cause AS retry_cause,retry.due_at AS retry_due_at,
 CASE WHEN d.id IS NULL THEN 0 ELSE 1 END AS has_design_history,
 CASE WHEN archive.task_id IS NULL THEN 0 ELSE 1 END AS archived,
 COALESCE(attempts.attempt_count,0) AS attempt_count,
 COALESCE(CAST(json_extract(d.spec_json,'$.limits.maxTaskAttempts') AS INTEGER),12) AS max_attempts,
 t.created_at,t.updated_at,t.version,t.execution_mode,
 NULL AS document_run_id,NULL AS document_state,t.id AS linked_task_id,origin.template_id AS source_template_id,COALESCE(d.goal,'') AS search_goal
FROM task t JOIN project p ON p.id=t.project_id
LEFT JOIN loop_draft d ON d.id=t.loop_draft_id
LEFT JOIN task_archive archive ON archive.task_id=t.id
LEFT JOIN template_task_run origin ON origin.task_id=t.id
LEFT JOIN (SELECT task_id,count(*) AS attempt_count FROM attempt GROUP BY task_id) attempts ON attempts.task_id=t.id
LEFT JOIN task_retry_schedule retry ON retry.id=(SELECT candidate.id FROM task_retry_schedule candidate
 WHERE candidate.task_id=t.id AND candidate.state IN ('SCHEDULED','PAUSED','CLAIMED')
 ORDER BY CASE candidate.state WHEN 'SCHEDULED' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END,
 candidate.updated_at DESC,candidate.id DESC LIMIT 1)
WHERE NOT EXISTS(SELECT 1 FROM document_template_run source WHERE source.task_id=t.id)
UNION ALL
SELECT source.id,source.project_id,p.name,source.title,source.title,
 COALESCE(t.branch_name,json_extract(source.branch_json,'$.label')),
 CASE WHEN source.state='EXECUTING' AND t.id IS NOT NULL THEN t.state ELSE source.state END,
 retry.cause,retry.due_at,CASE WHEN source.designer_id IS NULL THEN 0 ELSE 1 END,source.archived,
 COALESCE(attempts.attempt_count,0),COALESCE(CAST(json_extract(source.contract_json,'$.maxTaskAttempts') AS INTEGER),0),
 source.created_at,max(source.updated_at,COALESCE(t.updated_at,source.updated_at),COALESCE(models.updated_at,source.updated_at)),
 source.version,t.execution_mode,source.id,source.state,source.task_id,source.template_id,source.title
FROM document_template_run source JOIN project p ON p.id=source.project_id
LEFT JOIN task t ON t.id=source.task_id
LEFT JOIN (SELECT run_id,max(updated_at) AS updated_at FROM document_template_model_run GROUP BY run_id) models ON models.run_id=source.id
LEFT JOIN (SELECT task_id,count(*) AS attempt_count FROM attempt GROUP BY task_id) attempts ON attempts.task_id=source.task_id
LEFT JOIN task_retry_schedule retry ON retry.id=(SELECT candidate.id FROM task_retry_schedule candidate
 WHERE candidate.task_id=t.id AND candidate.state IN ('SCHEDULED','PAUSED','CLAIMED')
 ORDER BY CASE candidate.state WHEN 'SCHEDULED' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END,
 candidate.updated_at DESC,candidate.id DESC LIMIT 1);

CREATE TRIGGER document_source_archive_task AFTER INSERT ON task_archive
BEGIN
 UPDATE document_template_run SET archived=1,updated_at=NEW.archived_at,version=version+1 WHERE task_id=NEW.task_id;
END;
CREATE TRIGGER document_source_restore_task AFTER DELETE ON task_archive
BEGIN
 UPDATE document_template_run SET archived=0,version=version+1 WHERE task_id=OLD.task_id;
END;
