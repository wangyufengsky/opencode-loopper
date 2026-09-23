-- Both detail entry points retain the same server-side archive scope.
CREATE TRIGGER source_template_archive_task AFTER INSERT ON task_archive
BEGIN
 UPDATE source_template_run SET archived=1,updated_at=NEW.archived_at,version=version+1
 WHERE task_id=NEW.task_id AND archived=0;
END;
CREATE TRIGGER source_template_restore_task AFTER DELETE ON task_archive
BEGIN
 UPDATE source_template_run SET archived=0,version=version+1
 WHERE task_id=OLD.task_id AND archived=1;
END;
