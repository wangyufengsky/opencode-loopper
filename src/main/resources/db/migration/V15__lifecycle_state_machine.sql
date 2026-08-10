CREATE TABLE state_transition_event (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    machine_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    scope_id TEXT NOT NULL,
    event TEXT NOT NULL,
    from_state TEXT,
    to_state TEXT NOT NULL,
    reason_code TEXT,
    metadata_json TEXT NOT NULL,
    occurred_at TEXT NOT NULL
);

CREATE INDEX idx_state_transition_machine_entity_sequence
    ON state_transition_event(machine_type, entity_id, sequence);

CREATE INDEX idx_state_transition_scope_sequence
    ON state_transition_event(scope_type, scope_id, sequence);

ALTER TABLE automation_run ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
