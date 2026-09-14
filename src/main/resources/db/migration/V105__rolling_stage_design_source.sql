ALTER TABLE task_stage_contract ADD COLUMN design_message_id TEXT REFERENCES designer_message(id);
